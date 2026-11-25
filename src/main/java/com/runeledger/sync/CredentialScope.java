package com.runeledger.sync;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

final class CredentialScope
{
    final String player;
    // A local code only applies to the verified desktop listener.
    final URI hostedEndpoint;

    CredentialScope(String player, URI hostedEndpoint)
    {
        this.player = player;
        this.hostedEndpoint = hostedEndpoint;
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
        return Objects.hash(player.toLowerCase(Locale.ROOT), hostedEndpoint);
    }
}
