package com.runeledger.sync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(RuneLedgerConfig.GROUP)
public interface RuneLedgerConfig extends Config
{
    String GROUP = "runeledgersync";

    @ConfigSection(
        name = "Hosted Rune Ledger",
        description = "Connection settings for a hosted Rune Ledger site. Local app mode uses only the local sync code.",
        position = 5,
        closedByDefault = true
    )
    String HOSTED = "hosted";

    @ConfigItem(
        keyName = "syncEnabled",
        name = "Enable account sync",
        description = "Sends your display name, skill levels, and XP to your selected Rune Ledger destination.",
        warning = "This feature submits your IP address, RuneScape display name, skill levels, and XP to the configured "
            + "Rune Ledger server, which is not controlled or verified by RuneLite developers. Bank and Slayer data are optional.",
        position = 0
    )
    default boolean syncEnabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "localApp",
        name = "Local app",
        description = "Finds Rune Ledger Desktop on this PC. Enable local sync in the Windows app and enter its sync code.",
        position = 1
    )
    default boolean localApp()
    {
        return false;
    }

    @ConfigItem(
        keyName = "serverUrl",
        name = "Rune Ledger URL",
        description = "The root URL of your Rune Ledger site. Use HTTPS for remote servers.",
        position = 0,
        section = HOSTED
    )
    default String serverUrl()
    {
        return "https://rune-ledger.vercel.app";
    }

    @ConfigItem(
        keyName = "playerName",
        name = "Player display name",
        description = "The paired player. Sync runs only while this character is logged in.",
        position = 1,
        section = HOSTED
    )
    default String playerName()
    {
        return "";
    }

    @ConfigItem(
        keyName = "syncBank",
        name = "Include bank contents",
        description = "Also sends item IDs and quantities from your regular bank after you open it.",
        warning = "This option sends item IDs and quantities from your opened bank to your selected Rune Ledger destination.",
        position = 3
    )
    default boolean syncBank()
    {
        return false;
    }

    @ConfigItem(
        keyName = "syncSlayer",
        name = "Include Slayer task",
        description = "Also sends your current Slayer assignment, remaining count, and assigned area to Rune Ledger.",
        warning = "This option sends your current Slayer assignment, count, and area to your selected Rune Ledger destination.",
        position = 4
    )
    default boolean syncSlayer()
    {
        return false;
    }
}
