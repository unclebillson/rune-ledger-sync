package com.runeledger.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

final class SnapshotTransport
{
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final Pattern NAME_SPACES = Pattern.compile("[ _\u00a0]+");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final Gson gson;

    SnapshotTransport(OkHttpClient client, Gson gson)
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
            if (host == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || (!base.getPath().isEmpty() && !base.getPath().equals("/"))
                || base.getPort() == 0 || base.getPort() > 65535)
            {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
            if (!"https".equalsIgnoreCase(base.getScheme())
                && !("http".equalsIgnoreCase(base.getScheme()) && loopback))
            {
                return null;
            }
            return base.resolve("/api/sync/snapshot");
        }
        catch (IllegalArgumentException ex)
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

    Call newCall(URI endpoint, String token, Snapshot snapshot)
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
        Request request = new Request.Builder()
            .url(endpoint.toString())
            .header("Authorization", "Bearer " + token)
            .post(RequestBody.create(JSON, payload.toString()))
            .build();
        return client.newCall(request);
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
