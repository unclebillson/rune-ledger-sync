package com.runeledger.sync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(RuneLedgerConfig.GROUP)
public interface RuneLedgerConfig extends Config
{
    String GROUP = "runeledgersync";

    @ConfigItem(
        keyName = "syncEnabled",
        name = "Enable account sync",
        description = "Sends your display name, skill levels, and XP to the Rune Ledger URL below.",
        warning = "This feature submits your IP address, RuneScape display name, skill levels, and XP to the configured "
            + "Rune Ledger server, which is not controlled or verified by RuneLite developers. Bank and Slayer data are optional.",
        position = 0
    )
    default boolean syncEnabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "serverUrl",
        name = "Rune Ledger URL",
        description = "The root URL of your Rune Ledger site. Use HTTPS for remote servers.",
        position = 1
    )
    default String serverUrl()
    {
        return "http://127.0.0.1:3000";
    }

    @ConfigItem(
        keyName = "playerName",
        name = "Player display name",
        description = "The paired player. Sync runs only while this character is logged in.",
        position = 2
    )
    default String playerName()
    {
        return "";
    }

    @ConfigItem(
        keyName = "token",
        name = "Pairing token",
        description = "The scoped sync token from your Rune Ledger instance.",
        secret = true,
        position = 3
    )
    default String token()
    {
        return "";
    }

    @ConfigItem(
        keyName = "syncBank",
        name = "Include bank contents",
        description = "Also sends item IDs and quantities from your regular bank after you open it.",
        warning = "This option sends item IDs and quantities from your opened bank to the configured third-party Rune Ledger server.",
        position = 4
    )
    default boolean syncBank()
    {
        return false;
    }

    @ConfigItem(
        keyName = "syncSlayer",
        name = "Include Slayer task",
        description = "Also sends your current Slayer assignment, remaining count, and assigned area to Rune Ledger.",
        warning = "This option sends your current Slayer assignment, count, and area to the configured third-party Rune Ledger server.",
        position = 5
    )
    default boolean syncSlayer()
    {
        return false;
    }
}
