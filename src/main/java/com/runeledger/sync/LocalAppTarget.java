package com.runeledger.sync;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class LocalAppTarget
{
    static final String CHALLENGE_HEADER = "X-Rune-Ledger-Local-Challenge";
    static final String PROOF_HEADER = "X-Rune-Ledger-Local-Proof";
    private static final String DESCRIPTOR = "runelite-sync.json";
    private static final int MAX_DESCRIPTOR_BYTES = 256;
    private static final Pattern PORT = Pattern.compile("[1-9][0-9]{0,4}");
    private static final Pattern PROOF_KEY = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern PROOF = Pattern.compile("[a-f0-9]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HMAC = "HmacSHA256";

    final URI endpoint;
    private final String proofKey;

    private LocalAppTarget(int port, String proofKey)
    {
        this.endpoint = URI.create("http://127.0.0.1:" + port + "/api/sync/snapshot");
        this.proofKey = proofKey;
    }

    static LocalAppTarget find()
    {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank())
        {
            return null;
        }
        try
        {
            Path appData = Path.of(localAppData);
            return appData.isAbsolute() ? read(appData.resolve("Rune Ledger Desktop").resolve(DESCRIPTOR)) : null;
        }
        catch (IllegalArgumentException | SecurityException exception)
        {
            return null;
        }
    }

    static LocalAppTarget read(Path path)
    {
        try
        {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            {
                return null;
            }
            try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
            {
                byte[] bytes = stream.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
                return bytes.length <= MAX_DESCRIPTOR_BYTES ? parse(new String(bytes, StandardCharsets.UTF_8)) : null;
            }
        }
        catch (IOException | SecurityException exception)
        {
            return null;
        }
    }

    private static LocalAppTarget parse(String json) throws IOException
    {
        try (JsonReader reader = new JsonReader(new StringReader(json)))
        {
            int port = 0;
            String proofKey = null;
            reader.beginObject();
            while (reader.hasNext())
            {
                String key = reader.nextName();
                if ("port".equals(key) && port == 0 && reader.peek() == JsonToken.NUMBER)
                {
                    String value = reader.nextString();
                    if (!PORT.matcher(value).matches())
                    {
                        return null;
                    }
                    port = Integer.parseInt(value);
                }
                else if ("proofKey".equals(key) && proofKey == null && reader.peek() == JsonToken.STRING)
                {
                    proofKey = reader.nextString();
                }
                else
                {
                    return null;
                }
            }
            reader.endObject();
            if (port == 0 || port > 65535 || proofKey == null || !PROOF_KEY.matcher(proofKey).matches()
                || reader.peek() != JsonToken.END_DOCUMENT)
            {
                return null;
            }
            return new LocalAppTarget(port, proofKey);
        }
        catch (IllegalStateException exception)
        {
            return null;
        }
    }

    static String newChallenge()
    {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return hex(nonce);
    }

    boolean verifiesProof(String challenge, String presented)
    {
        if (!PROOF.matcher(presented).matches())
        {
            return false;
        }
        try
        {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(proofKey.getBytes(StandardCharsets.US_ASCII), HMAC));
            String expected = hex(mac.doFinal(challenge.getBytes(StandardCharsets.US_ASCII)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII));
        }
        catch (GeneralSecurityException exception)
        {
            return false;
        }
    }

    private static String hex(byte[] bytes)
    {
        String digits = "0123456789abcdef";
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++)
        {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits.charAt(value >>> 4);
            result[i * 2 + 1] = digits.charAt(value & 0x0f);
        }
        return new String(result);
    }

    @Override
    public boolean equals(Object object)
    {
        if (!(object instanceof LocalAppTarget))
        {
            return false;
        }
        LocalAppTarget target = (LocalAppTarget) object;
        return endpoint.equals(target.endpoint) && proofKey.equals(target.proofKey);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(endpoint, proofKey);
    }
}
