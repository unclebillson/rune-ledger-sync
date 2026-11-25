package com.runeledger.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.jna.Platform;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import net.runelite.api.Client;
import okhttp3.Call;
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
        check(SnapshotTransport.endpoint("http://[::1]:3000") == null, "reject unverified IPv6 loopback");
        check(SnapshotTransport.endpoint("http://127.0.0.1:3000") == null, "reject unverified IPv4 loopback");
        check(SnapshotTransport.endpoint("http://localhost:3000") == null, "reject unverified localhost");
        check(SnapshotTransport.endpoint("https://EXAMPLE.ORG:443/").equals(SnapshotTransport.endpoint("https://example.org")),
            "normalize hosted destinations before choosing a code");
        check(SnapshotTransport.normalizeName(" Test_Iron ").equals("Test Iron"), "normalize display name");
        check(SnapshotTransport.normalizeName(" Test_\u00a0 Iron ").equals("Test Iron"), "collapse multiple display-name separators");
        check(!SnapshotTransport.validToken("short"), "reject weak token");
        check(!SnapshotTransport.validToken(null), "reject an unset token");
        String token = "a".repeat(64);
        check(SnapshotTransport.validToken(token), "allow an instance token");
        checkWindowsCredentialStore(token);
        JsonElement task = checkSlayerCapture();

        Gson gson = new GsonBuilder().serializeNulls().create();
        checkLocalApp(gson, token);
        SSLContext[] contexts = testTlsContexts();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(contexts[0]));
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
            URI origin = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
            URI snapshotEndpoint = origin.resolve("/api/sync/snapshot");
            SnapshotTransport transport = SnapshotTransport.forLocalApp(gson);
            HttpClient hosted = SnapshotTransport.hostedClient(contexts[1]);
            List<SnapshotTransport.SkillValue> skills = List.of(new SnapshotTransport.SkillValue("Smithing", 50, 101333));
            SnapshotTransport.Snapshot unobserved = new SnapshotTransport.Snapshot("Test Iron", skills, null, null);
            try
            {
                transport.newHostedCall(hosted, URI.create("http://127.0.0.1/api/sync/snapshot"), token, unobserved);
                throw new AssertionError("reject HTTP even when passed directly to the hosted transport");
            }
            catch (IllegalArgumentException expected)
            {
                // No hosted request is created for a cleartext URI.
            }
            rejectsCertificate(transport.newHostedCall(SnapshotTransport.hostedClient(), snapshotEndpoint, token, unobserved));
            URI wrongName = URI.create("https://localhost:" + server.getAddress().getPort() + "/api/sync/snapshot");
            rejectsCertificate(transport.newHostedCall(hosted, wrongName, token, unobserved));
            check(payload.get() == null, "rejected HTTPS peers received no snapshot");
            check(transport.newHostedCall(hosted, snapshotEndpoint, token, unobserved).execute() == 200, "upload skills");
            check(("Bearer " + token).equals(auth.get()), "write uses the scoped token");
            check(!payload.get().has("bank"), "unobserved bank is omitted");
            check(!payload.get().has("slayer"), "unobserved Slayer task is omitted");
            check(payload.get().get("schemaVersion").getAsInt() == 1, "versioned payload");
            check(payload.get().getAsJsonArray("skills").get(0).getAsJsonObject().get("xp").getAsInt() == 101333, "exact XP");
            SnapshotTransport.Snapshot empty = new SnapshotTransport.Snapshot("Test Iron", skills, List.of(), JsonNull.INSTANCE);
            check(transport.newHostedCall(hosted, snapshotEndpoint, token, empty).execute() == 200, "upload empty bank");
            check(payload.get().has("bank") && payload.get().getAsJsonArray("bank").size() == 0, "observed empty bank is explicit");
            check(payload.get().has("slayer") && payload.get().get("slayer").isJsonNull(), "confirmed no-task state is explicit");
            SnapshotTransport.Snapshot assignment = new SnapshotTransport.Snapshot("Test Iron", skills, null, task);
            check(transport.newHostedCall(hosted, snapshotEndpoint, token, assignment).execute() == 200, "upload Slayer update");
            check(payload.get().get("slayer").equals(task), "include Slayer task with both nullable fields");
            check(!payload.get().has("bank"), "Slayer update does not claim to observe the bank");
            check(transport.newHostedCall(hosted, origin.resolve("/redirect"), token, empty).execute() == 307, "never follow redirects");
            SnapshotTransport.HostedUpload slowCall = transport.newHostedCall(hosted, origin.resolve("/slow-response"), token, empty);
            int slowStatus = testSender.submit(slowCall::execute).get(4, TimeUnit.SECONDS);
            check(slowStatus == 202, "finish an upload once the response status is received");
            releaseResponse.countDown();
            SnapshotTransport.HostedUpload pendingCall =
                transport.newHostedCall(hosted, origin.resolve("/pending-response"), token, empty);
            var pending = testSender.submit(() ->
            {
                try
                {
                    pendingCall.execute();
                    return false;
                }
                catch (IOException ex)
                {
                    return true;
                }
            });
            check(waitingForHeaders.await(4, TimeUnit.SECONDS), "reach a request with pending response headers");
            pendingCall.cancel();
            check(pending.get(2, TimeUnit.SECONDS), "cancel an in-flight upload before its request timeout");
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

    private static void rejectsCertificate(SnapshotTransport.HostedUpload upload) throws IOException
    {
        try
        {
            upload.execute();
            throw new AssertionError("reject an invalid hosted TLS certificate");
        }
        catch (IOException exception)
        {
            for (Throwable cause = exception; cause != null; cause = cause.getCause())
            {
                if (cause instanceof SSLHandshakeException)
                {
                    return;
                }
            }
            throw new AssertionError("reject a certificate before sending HTTP", exception);
        }
    }

    private static SSLContext[] testTlsContexts() throws Exception
    {
        Path directory = Files.createTempDirectory("rune-ledger-test-tls-");
        Path keyFile = directory.resolve("test-key.p12");
        String password = "synthetic-test-password";
        try
        {
            Path keytool = Path.of(System.getProperty("java.home"), "bin", Platform.isWindows() ? "keytool.exe" : "keytool");
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "synthetic",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", keyFile.toString(),
                "-storepass", password, "-keypass", password, "-noprompt").redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                throw new AssertionError("temporary HTTPS certificate generation timed out");
            }
            check(process.exitValue() == 0, "generate a temporary HTTPS certificate");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(keyFile))
            {
                keys.load(input, password.toCharArray());
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password.toCharArray());
            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keyManagers.getKeyManagers(), null, null);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("synthetic", keys.getCertificate("synthetic"));
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, trustManagers.getTrustManagers(), null);
            return new SSLContext[]{server, client};
        }
        finally
        {
            Files.deleteIfExists(keyFile);
            Files.delete(directory);
        }
    }

    private static void checkWindowsCredentialStore(String token) throws IOException
    {
        if (!Platform.isWindows())
        {
            check(!WindowsCredentialStore.forCurrentUser().available(), "Windows persistence only applies to Windows");
            return;
        }
        Path directory = Files.createTempDirectory("rune-ledger-test-credentials-");
        WindowsCredentialStore store = new WindowsCredentialStore(directory.toRealPath());
        CredentialScope local = new CredentialScope("Test Iron", null);
        CredentialScope hosted = new CredentialScope("Test Iron", SnapshotTransport.endpoint("https://example.org"));
        CredentialScope other = new CredentialScope("Test Main", null);
        try
        {
            check(store.load(local) == null, "new scopes have no stored code");
            store.save(local, token);
            store.save(hosted, "d".repeat(64));
            check(token.equals(store.load(new CredentialScope("TEST IRON", null))), "read a saved Windows code with the same scope");
            check(!token.equals(store.load(hosted)) && store.load(other) == null, "separate Windows credentials by host and player");
            try (var files = Files.list(directory.resolve("Rune Ledger Sync Plugin")))
            {
                for (Path file : (Iterable<Path>) files::iterator)
                {
                    byte[] contents = Files.readAllBytes(file);
                    check(!Arrays.equals(contents, token.getBytes(StandardCharsets.US_ASCII))
                        && !new String(contents, StandardCharsets.ISO_8859_1).contains(token), "store encrypted data only");
                }
            }
            String replacement = "b".repeat(64);
            store.save(local, replacement);
            check(replacement.equals(store.load(local)), "replace an encrypted credential");
            store.clear(local);
            check(store.load(local) == null, "clear saved credentials");
        }
        finally
        {
            store.clear(local);
            store.clear(hosted);
            Files.deleteIfExists(directory.resolve("Rune Ledger Sync Plugin"));
            Files.delete(directory);
        }
    }

    private static void checkLocalApp(Gson gson, String token) throws IOException
    {
        String proofKey = "b".repeat(32);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Path descriptor = Files.createTempFile("rune-ledger-local", ".json");
        AtomicBoolean replay = new AtomicBoolean();
        AtomicBoolean closeProbe = new AtomicBoolean();
        AtomicBoolean probeHasSecrets = new AtomicBoolean();
        AtomicBoolean postHasProof = new AtomicBoolean();
        AtomicInteger posts = new AtomicInteger();
        AtomicReference<String> postAuth = new AtomicReference<>();
        AtomicReference<String> lastChallenge = new AtomicReference<>();
        AtomicReference<String> lastProof = new AtomicReference<>();
        server.createContext("/api/sync/snapshot", request ->
        {
            try
            {
                if ("HEAD".equals(request.getRequestMethod()))
                {
                    String challenge = request.getRequestHeaders().getFirst(LocalAppTarget.CHALLENGE_HEADER);
                    probeHasSecrets.set(request.getRequestHeaders().containsKey("Authorization")
                        || request.getRequestHeaders().containsKey(LocalAppTarget.PROOF_HEADER)
                        || request.getRequestHeaders().values().stream().flatMap(List::stream).anyMatch(proofKey::equals));
                    check(challenge != null && challenge.matches("[a-f0-9]{32}"), "send a fresh lowercase hexadecimal challenge");
                    lastChallenge.set(challenge);
                    String proof = replay.get() ? lastProof.get() : desktopProof(proofKey, challenge);
                    if (!replay.get())
                    {
                        lastProof.set(proof);
                    }
                    request.getResponseHeaders().set(LocalAppTarget.PROOF_HEADER, proof);
                    if (closeProbe.get())
                    {
                        request.getResponseHeaders().set("Connection", "close");
                    }
                    request.sendResponseHeaders(204, -1);
                }
                else
                {
                    posts.incrementAndGet();
                    postAuth.set(request.getRequestHeaders().getFirst("Authorization"));
                    postHasProof.set(request.getRequestHeaders().containsKey(LocalAppTarget.CHALLENGE_HEADER)
                        || request.getRequestHeaders().containsKey(LocalAppTarget.PROOF_HEADER)
                        || request.getRequestHeaders().values().stream().flatMap(List::stream).anyMatch(proofKey::equals));
                    request.getRequestBody().readAllBytes();
                    request.sendResponseHeaders(200, -1);
                }
            }
            finally
            {
                request.close();
            }
        });
        server.start();
        try
        {
            int port = server.getAddress().getPort();
            String valid = "{\"port\":" + port + ",\"proofKey\":\"" + proofKey + "\"}";
            for (String invalid : List.of(
                "", "[]", valid + "{}", valid + " ".repeat(256),
                valid.replace("\"port\":", "\"url\":"),
                valid.replace("\"port\":", "\"port\":1234,\"port\":"),
                valid.replace(String.valueOf(port), "\"1234\""),
                valid.replace(String.valueOf(port), "1.0"),
                valid.replace(String.valueOf(port), "65536"),
                valid.replace("\"proofKey\":", "\"instance\":"),
                valid.replace(proofKey, proofKey.toUpperCase(java.util.Locale.ROOT))))
            {
                Files.writeString(descriptor, invalid);
                check(LocalAppTarget.read(descriptor) == null, "reject invalid local descriptor");
            }
            Files.writeString(descriptor, valid);
            LocalAppTarget target = LocalAppTarget.read(descriptor);
            check(target != null && target.endpoint.getHost().equals("127.0.0.1") && target.endpoint.getPort() == port,
                "derive a fixed loopback endpoint from the descriptor");
            SnapshotTransport local = SnapshotTransport.forLocalApp(gson);
            SnapshotTransport.Snapshot snapshot = new SnapshotTransport.Snapshot("Test Iron",
                List.of(new SnapshotTransport.SkillValue("Smithing", 50, 101333)), null, null);

            SnapshotTransport.LocalProbe probe = new SnapshotTransport.LocalProbe(target);
            try (Response response = local.probeLocalApp(probe).execute())
            {
                check(probe.verify(response), "verify the expected desktop listener");
            }
            check(!probeHasSecrets.get(), "the local probe sends only a public challenge");
            check(status(local.newLocalCall(probe, token, snapshot)) == 200, "upload over the verified connection");
            check(("Bearer " + token).equals(postAuth.get()), "authenticate desktop uploads");
            check(!postHasProof.get(), "keep discovery proof material out of the desktop upload");

            String usedChallenge = lastChallenge.get();
            replay.set(true);
            SnapshotTransport.LocalProbe mismatch = new SnapshotTransport.LocalProbe(target);
            try (Response response = local.probeLocalApp(mismatch).execute())
            {
                check(!mismatch.verify(response), "reject a listener that replays a valid proof from a different challenge");
            }
            check(!usedChallenge.equals(lastChallenge.get()), "generate a different challenge for each probe");
            checkBlocked(local.newLocalCall(mismatch, token, snapshot));

            replay.set(false);
            closeProbe.set(true);
            SnapshotTransport.LocalProbe closed = new SnapshotTransport.LocalProbe(target);
            try (Response response = local.probeLocalApp(closed).execute())
            {
                check(closed.verify(response), "recognize the expected listener before it disconnects");
            }
            checkBlocked(local.newLocalCall(closed, token, snapshot));
            check(posts.get() == 1, "refuse to write over unverified or replacement connections");
        }
        finally
        {
            Files.deleteIfExists(descriptor);
            server.stop(0);
        }
    }

    private static String desktopProof(String key, String challenge)
    {
        try
        {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return String.format("%064x", new BigInteger(1, hmac.doFinal(challenge.getBytes(StandardCharsets.US_ASCII))));
        }
        catch (GeneralSecurityException exception)
        {
            throw new AssertionError("HMAC-SHA256 unavailable.", exception);
        }
    }

    private static void checkBlocked(Call call)
    {
        try
        {
            status(call);
            throw new AssertionError("An unverified local upload was sent.");
        }
        catch (IOException expected)
        {
            check(expected.getMessage().equals("The verified local app connection closed."), "cancel before writing the upload");
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
