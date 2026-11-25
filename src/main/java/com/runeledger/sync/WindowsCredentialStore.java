package com.runeledger.sync;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

final class WindowsCredentialStore
{
    private static final String DIRECTORY = "Rune Ledger Sync Plugin";
    private static final int MAX_CIPHERTEXT_BYTES = 4096;

    private final Path appData;

    WindowsCredentialStore(Path appData)
    {
        this.appData = appData;
    }

    static WindowsCredentialStore forCurrentUser()
    {
        try
        {
            String value = System.getenv("LOCALAPPDATA");
            if (Platform.isWindows() && value != null)
            {
                Path directory = Path.of(value).normalize();
                if (directory.isAbsolute() && directory.getRoot().toString().matches("[A-Za-z]:[\\\\/]"))
                {
                    return new WindowsCredentialStore(directory);
                }
            }
        }
        catch (IllegalArgumentException | SecurityException | LinkageError ignored)
        {
            // Session-only credentials are available without Windows storage.
        }
        return new WindowsCredentialStore(null);
    }

    boolean available()
    {
        return appData != null;
    }

    String load(CredentialScope scope) throws IOException
    {
        Path path = credentialPath(scope, false);
        if (path == null || Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return null;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Rune Ledger credential is not a regular file.");
        }
        byte[] encrypted;
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
        {
            encrypted = stream.readNBytes(MAX_CIPHERTEXT_BYTES + 1);
        }
        if (encrypted.length == 0 || encrypted.length > MAX_CIPHERTEXT_BYTES)
        {
            throw new IOException("Rune Ledger credential file has an invalid size.");
        }
        byte[] plaintext = Crypt32Util.cryptUnprotectData(encrypted, scope.entropy(),
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        try
        {
            String token = new String(plaintext, StandardCharsets.US_ASCII);
            if (!SnapshotTransport.validToken(token))
            {
                throw new IOException("Rune Ledger credential is invalid.");
            }
            return token;
        }
        finally
        {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    void save(CredentialScope scope, String token) throws IOException
    {
        if (!SnapshotTransport.validToken(token))
        {
            throw new IllegalArgumentException("Invalid Rune Ledger sync code.");
        }
        Path path = credentialPath(scope, true);
        if (path == null)
        {
            throw new IOException("Windows credential storage is unavailable.");
        }
        byte[] plaintext = token.getBytes(StandardCharsets.US_ASCII);
        byte[] encrypted;
        try
        {
            encrypted = Crypt32Util.cryptProtectData(plaintext, scope.entropy(),
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Rune Ledger Sync", null);
        }
        finally
        {
            Arrays.fill(plaintext, (byte) 0);
        }
        Path temporary = Files.createTempFile(path.getParent(), "credential-", ".tmp");
        try
        {
            Files.write(temporary, encrypted, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    void clear(CredentialScope scope) throws IOException
    {
        Path path = credentialPath(scope, false);
        if (path != null)
        {
            Files.deleteIfExists(path);
        }
    }

    private Path credentialPath(CredentialScope scope, boolean create) throws IOException
    {
        if (appData == null)
        {
            return null;
        }
        Path directory = appData.resolve(DIRECTORY);
        if (!appData.equals(appData.toRealPath()))
        {
            throw new IOException("Windows application data must be a local directory.");
        }
        if (create)
        {
            Files.createDirectories(directory);
        }
        else if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS))
        {
            return null;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath()))
        {
            throw new IOException("Rune Ledger credential directory must be a local directory.");
        }
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.entropy());
            return directory.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(digest) + ".bin");
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("Rune Ledger credential storage is unavailable.", exception);
        }
    }
}
