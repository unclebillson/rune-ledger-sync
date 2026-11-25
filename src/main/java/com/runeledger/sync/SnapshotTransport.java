package com.runeledger.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class SnapshotTransport
{
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final Pattern NAME_SPACES = Pattern.compile("[ _\u00a0]+");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final Gson gson;

    private SnapshotTransport(OkHttpClient client, Gson gson)
    {
        this.client = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
        this.gson = gson;
    }

    static HttpClient hostedClient()
    {
        // Use the JDK TLS stack and its default trust store independently of RuneLite's HTTP settings.
        try
        {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, null);
            return hostedClient(context);
        }
        catch (GeneralSecurityException exception)
        {
            throw new IllegalStateException("Rune Ledger could not initialize HTTPS.", exception);
        }
    }

    static HttpClient hostedClient(SSLContext context)
    {
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder()
            .sslContext(context)
            .sslParameters(parameters)
            .proxy(ProxySelector.of(null))
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    static SnapshotTransport forLocalApp(Gson gson)
    {
        OkHttpClient direct = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .addNetworkInterceptor(chain ->
            {
                Request request = chain.request();
                LocalProbe probe = request.tag(LocalProbe.class);
                if (probe == null || chain.connection() == null)
                {
                    throw new IOException("Missing local app verification.");
                }
                if ("HEAD".equals(request.method()))
                {
                    probe.connection = chain.connection();
                    probe.verified = false;
                }
                else if (!probe.verified || probe.connection != chain.connection())
                {
                    // A different process could have rebound this port since the probe.
                    throw new IOException("The verified local app connection closed.");
                }
                return chain.proceed(request);
            })
            .build();
        return new SnapshotTransport(direct, gson);
    }

    static URI endpoint(String value)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            URI base = URI.create(value.trim());
            String host = base.getHost();
            if (!"https".equalsIgnoreCase(base.getScheme()) || host == null
                || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || (!base.getPath().isEmpty() && !base.getPath().equals("/"))
                || base.getPort() == 0 || base.getPort() > 65535)
            {
                return null;
            }
            int port = base.getPort() == 443 ? -1 : base.getPort();
            return new URI("https", null, host.toLowerCase(Locale.ROOT), port, "/api/sync/snapshot", null, null);
        }
        catch (IllegalArgumentException | URISyntaxException ex)
        {
            return null;
        }
    }

    static boolean validToken(String token)
    {
        return token != null && TOKEN.matcher(token).matches();
    }

    String fingerprint(List<SkillValue> skills)
    {
        return gson.toJson(skills);
    }

    static String normalizeName(String name)
    {
        return name == null ? "" : NAME_SPACES.matcher(name).replaceAll(" ").trim();
    }

    HostedUpload newHostedCall(HttpClient hosted, URI endpoint, String token, Snapshot snapshot)
    {
        if (!"https".equals(endpoint.getScheme()))
        {
            throw new IllegalArgumentException("Rune Ledger hosted uploads require HTTPS.");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", JSON.toString())
            .POST(HttpRequest.BodyPublishers.ofString(payload(snapshot)))
            .build();
        return new HostedUpload(hosted, request);
    }

    Call probeLocalApp(LocalProbe probe)
    {
        return client.newCall(new Request.Builder()
            .url(probe.target.endpoint.toString())
            .header(LocalAppTarget.CHALLENGE_HEADER, probe.challenge)
            .tag(LocalProbe.class, probe)
            .head()
            .build());
    }

    Call newLocalCall(LocalProbe probe, String token, Snapshot snapshot)
    {
        Request request = new Request.Builder()
            .url(probe.target.endpoint.toString())
            .header("Authorization", "Bearer " + token)
            .post(RequestBody.create(JSON, payload(snapshot)))
            .tag(LocalProbe.class, probe)
            .build();
        return client.newCall(request);
    }

    private String payload(Snapshot snapshot)
    {
        JsonObject payload = gson.toJsonTree(snapshot).getAsJsonObject();
        if (snapshot.bank == null)
        {
            payload.remove("bank");
        }
        if (snapshot.slayer != null)
        {
            payload.add("slayer", snapshot.slayer);
        }
        return payload.toString();
    }

    static final class HostedUpload
    {
        private final HttpClient client;
        private final HttpRequest request;
        private boolean cancelled;
        private CompletableFuture<HttpResponse<InputStream>> pending;

        HostedUpload(HttpClient client, HttpRequest request)
        {
            this.client = client;
            this.request = request;
        }

        int execute() throws IOException
        {
            synchronized (this)
            {
                if (cancelled)
                {
                    throw new IOException("Rune Ledger upload was cancelled.");
                }
                pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            }
            try
            {
                HttpResponse<InputStream> response = pending.get(8, TimeUnit.SECONDS);
                try (InputStream ignored = response.body())
                {
                    return response.statusCode();
                }
            }
            catch (InterruptedException ex)
            {
                cancel();
                Thread.currentThread().interrupt();
                throw new IOException("Rune Ledger upload was interrupted.", ex);
            }
            catch (java.util.concurrent.TimeoutException ex)
            {
                cancel();
                throw new IOException("Rune Ledger upload timed out.", ex);
            }
            catch (ExecutionException | CancellationException ex)
            {
                throw new IOException("Rune Ledger upload failed.", ex);
            }
        }

        synchronized void cancel()
        {
            cancelled = true;
            if (pending != null)
            {
                pending.cancel(true);
            }
        }
    }

    static final class LocalProbe
    {
        final LocalAppTarget target;
        private final String challenge = LocalAppTarget.newChallenge();
        private Connection connection;
        private boolean verified;

        LocalProbe(LocalAppTarget target)
        {
            this.target = target;
        }

        boolean verify(Response response)
        {
            List<String> proofs = response.headers(LocalAppTarget.PROOF_HEADER);
            verified = response.code() == 204 && proofs.size() == 1 && target.verifiesProof(challenge, proofs.get(0));
            return verified;
        }
    }

    static final class Snapshot
    {
        final int schemaVersion = 1;
        final String player;
        final List<SkillValue> skills;
        final List<BankStack> bank;
        final transient JsonElement slayer;

        Snapshot(String player, List<SkillValue> skills, List<BankStack> bank, JsonElement slayer)
        {
            this.player = player;
            this.skills = skills;
            this.bank = bank;
            this.slayer = slayer;
        }
    }

    static final class SkillValue
    {
        final String name;
        final int level;
        final int xp;

        SkillValue(String name, int level, int xp)
        {
            this.name = name;
            this.level = level;
            this.xp = xp;
        }
    }

    static final class BankStack
    {
        final int id;
        final int quantity;

        BankStack(int id, int quantity)
        {
            this.id = id;
            this.quantity = quantity;
        }
    }
}
