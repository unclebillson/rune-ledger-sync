package com.runeledger.sync;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class CredentialScope
{
    final String player;
    // A local code only applies to the verified desktop listener.
    final URI hostedEndpoint;
    private final int hash;

    CredentialScope(String player, URI hostedEndpoint)
    {
        this.player = player;
        this.hostedEndpoint = hostedEndpoint;
        this.hash = Objects.hash(player.toLowerCase(Locale.ROOT), hostedEndpoint);
    }

    byte[] entropy()
    {
        String destination = hostedEndpoint == null ? "desktop" : hostedEndpoint.toASCIIString();
        return ("Rune Ledger Sync credential 1\n" + player.toLowerCase(Locale.ROOT) + "\n" + destination)
            .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object value)
    {
        if (this == value)
        {
            return true;
        }
        if (!(value instanceof CredentialScope))
        {
            return false;
        }
        CredentialScope other = (CredentialScope) value;
        return player.equalsIgnoreCase(other.player) && Objects.equals(hostedEndpoint, other.hostedEndpoint);
    }

    @Override
    public int hashCode()
    {
        return hash;
    }

    // Accessed on the game thread. Compare live inputs before using either an accepted or rejected scope.
    static final class Resolver
    {
        private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9 -]{1,12}");
        private String observedName;
        private boolean local;
        private String serverUrl;
        private String pairedName;
        private CredentialScope scope;

        CredentialScope resolve(String currentName, boolean useLocal, String configuredUrl, String configuredName)
        {
            if (local == useLocal && Objects.equals(observedName, currentName)
                && Objects.equals(serverUrl, configuredUrl) && Objects.equals(pairedName, configuredName))
            {
                return scope;
            }
            observedName = currentName;
            local = useLocal;
            serverUrl = configuredUrl;
            pairedName = configuredName;

            String player = SnapshotTransport.normalizeName(currentName);
            scope = null;
            if (PLAYER_NAME.matcher(player).matches())
            {
                if (useLocal)
                {
                    scope = new CredentialScope(player, null);
                }
                else if (player.equalsIgnoreCase(SnapshotTransport.normalizeName(configuredName)))
                {
                    URI endpoint = SnapshotTransport.endpoint(configuredUrl);
                    if (endpoint != null)
                    {
                        scope = new CredentialScope(player, endpoint);
                    }
                }
            }
            return scope;
        }
    }
}
