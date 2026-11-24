package com.runeledger.sync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public final class SyncLauncher
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(RuneLedgerPlugin.class);
        RuneLite.main(args);
    }
}
