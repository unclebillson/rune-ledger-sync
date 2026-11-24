package com.runeledger.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public final class SnapshotTransportTest
{
    public static void main(String[] args) throws Exception
    {
        check(SnapshotTransport.endpoint("http://example.org") == null, "reject remote HTTP");
        check(SnapshotTransport.endpoint(null) == null, "reject an unset URL");
        check(SnapshotTransport.endpoint("https://example.org/path") == null, "reject path prefixes");
        check(SnapshotTransport.endpoint("https://user@example.org") == null, "reject URL credentials");
        check(SnapshotTransport.endpoint("https://example.org/?name=example") == null, "reject query");
        check(SnapshotTransport.endpoint("https://example.org").getPath().equals("/api/sync/snapshot"), "allow HTTPS");
        check(SnapshotTransport.endpoint("http://[::1]:3000") != null, "allow IPv6 loopback");
        check(SnapshotTransport.normalizeName(" Test_Iron ").equals("Test Iron"), "normalize display name");
        check(SnapshotTransport.normalizeName(" Test_\u00a0 Iron ").equals("Test Iron"), "collapse multiple display-name separators");
        check(!SnapshotTransport.validToken("short"), "reject weak token");
        check(!SnapshotTransport.validToken(null), "reject an unset token");
        String token = "a".repeat(64);
        check(SnapshotTransport.validToken(token), "allow an instance token");
        JsonElement task = checkSlayerCapture();

        Gson gson = new GsonBuilder().serializeNulls().create();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch waitingForHeaders = new CountDownLatch(1);
        CountDownLatch releasePendingResponse = new CountDownLatch(1);
        server.createContext("/api/sync/snapshot", request ->
        {
            auth.set(request.getRequestHeaders().getFirst("Authorization"));
            String json = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            payload.set(gson.fromJson(json, JsonObject.class));
            request.sendResponseHeaders(200, -1);
            request.close();
        });
        server.createContext("/redirect", request ->
        {
            request.getResponseHeaders().set("Location", "/api/sync/snapshot");
            request.sendResponseHeaders(307, -1);
            request.close();
        });
        server.createContext("/slow-response", request ->
        {
            request.getRequestBody().readAllBytes();
            request.sendResponseHeaders(202, 0);
            try
            {
                releaseResponse.await();
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                request.close();
            }
        });
        server.createContext("/pending-response", request ->
        {
            request.getRequestBody().readAllBytes();
            waitingForHeaders.countDown();
            try
            {
                releasePendingResponse.await();
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                request.close();
            }
        });
        server.start();
        ExecutorService testSender = Executors.newSingleThreadExecutor();
        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            SnapshotTransport transport = new SnapshotTransport(new OkHttpClient(), gson);
            List<SnapshotTransport.SkillValue> skills = List.of(new SnapshotTransport.SkillValue("Smithing", 50, 101333));
            SnapshotTransport.Snapshot unobserved = new SnapshotTransport.Snapshot("Test Iron", skills, null, null);
            check(status(transport.newCall(origin.resolve("/api/sync/snapshot"), token, unobserved)) == 200, "upload skills");
            check(("Bearer " + token).equals(auth.get()), "write uses the scoped token");
            check(!payload.get().has("bank"), "unobserved bank is omitted");
            check(!payload.get().has("slayer"), "unobserved Slayer task is omitted");
            check(payload.get().get("schemaVersion").getAsInt() == 1, "versioned payload");
            check(payload.get().getAsJsonArray("skills").get(0).getAsJsonObject().get("xp").getAsInt() == 101333, "exact XP");
            SnapshotTransport.Snapshot empty = new SnapshotTransport.Snapshot("Test Iron", skills, List.of(), JsonNull.INSTANCE);
            check(status(transport.newCall(origin.resolve("/api/sync/snapshot"), token, empty)) == 200, "upload empty bank");
            check(payload.get().has("bank") && payload.get().getAsJsonArray("bank").size() == 0, "observed empty bank is explicit");
            check(payload.get().has("slayer") && payload.get().get("slayer").isJsonNull(), "confirmed no-task state is explicit");
            SnapshotTransport.Snapshot assignment = new SnapshotTransport.Snapshot("Test Iron", skills, null, task);
            check(status(transport.newCall(origin.resolve("/api/sync/snapshot"), token, assignment)) == 200, "upload Slayer update");
            check(payload.get().get("slayer").equals(task), "include Slayer task with both nullable fields");
            check(!payload.get().has("bank"), "Slayer update does not claim to observe the bank");
            check(status(transport.newCall(origin.resolve("/redirect"), token, empty)) == 307, "never follow redirects");
            Call slowCall = transport.newCall(origin.resolve("/slow-response"), token, empty);
            int slowStatus = testSender.submit(() -> status(slowCall)).get(4, TimeUnit.SECONDS);
            check(slowStatus == 202, "finish an upload once the response status is received");
            releaseResponse.countDown();
            Call pendingCall = transport.newCall(origin.resolve("/pending-response"), token, empty);
            var pending = testSender.submit(() ->
            {
                try
                {
                    status(pendingCall);
                    return false;
                }
                catch (IOException ex)
                {
                    return pendingCall.isCanceled();
                }
            });
            check(waitingForHeaders.await(4, TimeUnit.SECONDS), "reach a request with pending response headers");
            pendingCall.cancel();
            check(pending.get(4, TimeUnit.SECONDS), "cancel an in-flight upload");
            System.out.println("Rune Ledger transport checks passed.");
        }
        finally
        {
            releaseResponse.countDown();
            releasePendingResponse.countDown();
            testSender.shutdownNow();
            server.stop(0);
        }
    }

    private static int status(Call call) throws IOException
    {
        try (Response response = call.execute())
        {
            return response.code();
        }
    }

    private static JsonElement checkSlayerCapture()
    {
        Map<Integer, Integer> varps = new HashMap<>(Map.of(394, 48, 395, 42, 2096, 9, 4258, 120));
        Map<Integer, Integer> varbits = new HashMap<>(Map.of(4723, 5, 15776, 2, 15777, 50));
        Map<List<Object>, List<Integer>> rows = Map.of(
            List.of(113, 0, 0, 42), List.of(100),
            List.of(115, 0, 0, 9), List.of(101),
            List.of(116, 1, 0, 5), List.of(102));
        Map<List<Object>, Object[]> fields = Map.of(
            List.of(100, 10, 0), new Object[]{"GARGOYLES"},
            List.of(101, 3, 0), new Object[]{"Slayer Tower"},
            List.of(102, 4, 0), new Object[]{103},
            List.of(103, 10, 0), new Object[]{"KING BLACK DRAGON"});
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, values) ->
            {
                switch (method.getName())
                {
                    case "getVarpValue": return varps.getOrDefault(values[0], 0);
                    case "getVarbitValue": return varbits.getOrDefault(values[0], 0);
                    case "getDBRowsByValue": return rows.getOrDefault(List.of(values), List.of());
                    case "getDBTableField": return fields.get(List.of(values));
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });

        JsonObject restricted = SlayerTaskReader.read(client).getAsJsonObject();
        check(restricted.get("task").getAsString().equals("GARGOYLES"), "resolve an ordinary task from the live cache");
        check(restricted.get("remaining").getAsInt() == 48, "read the server's remaining task count");
        check(restricted.get("initial").getAsInt() == 170, "account for a task extension");
        check(restricted.get("location").getAsString().equals("Slayer Tower"), "include the assigned area");
        fields.get(List.of(101, 3, 0))[0] = "Slayer\u200b Tower";
        check(SlayerTaskReader.read(client) == null, "reject formatting characters disallowed by the sync API");
        fields.get(List.of(101, 3, 0))[0] = "Slayer Tower";
        varbits.put(15778, 1);
        check(SlayerTaskReader.read(client).getAsJsonObject().get("initial").getAsInt() == 70, "account for a task reduction");
        varps.put(2096, 99);
        check(SlayerTaskReader.read(client) == null, "unknown assigned area is never reported as unrestricted");

        varps.put(395, 98);
        varps.put(4258, 0);
        varps.put(2096, 0);
        JsonObject boss = SlayerTaskReader.read(client).getAsJsonObject();
        check(boss.get("task").getAsString().equals("KING BLACK DRAGON"), "resolve the assigned boss rather than the Bosses category");
        check(boss.get("initial").isJsonNull(), "omit a progress total when the starting count is not available");
        check(boss.get("location").isJsonNull(), "unrestricted assignment has a null location");
        varps.put(394, 0);
        check(SlayerTaskReader.read(client).isJsonNull(), "confirmed no-task state");
        return boss;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
