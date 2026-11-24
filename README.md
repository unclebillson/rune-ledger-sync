# Rune Ledger Sync for RuneLite

Rune Ledger Sync sends your own OSRS character data from RuneLite to a hosted or self-hosted Rune Ledger site. Rune Ledger uses those snapshots for skill planning, bank advice, and Slayer tracking.

## Pair RuneLite

1. Open **Player lookup → RuneLite sync** on [Rune Ledger](https://rune-ledger.vercel.app/) or on your self-hosted site. Enter your OSRS display name and select **Generate pairing token**. Returning users can sign in to an existing Rune Ledger account first.
2. In RuneLite’s **Rune Ledger Sync** settings, enter the site’s root URL and the same display name. Paste the pairing token and enable **Enable account sync**. Use HTTPS for remote sites.
3. Enable **Include bank contents** or **Include Slayer task** if you want those features. Open your regular bank to send a bank snapshot. Load the snapshot from the site’s RuneLite sync panel.

## Data sent

Network sync defaults to off and runs only for the matching logged-in character. The configured server receives the display name, real skill levels, and exact XP and can see your IP address. It uses the Rune Ledger pairing token to authenticate uploads. Bank sync separately sends item IDs and quantities from the regular bank after you open it. Slayer sync separately sends the current assignment, remaining count, starting count when available, and assigned area. Both optional settings default to off.

## AI development disclosure

Rune Ledger Sync was built with OpenAI Codex, which generated plugin code, tests, and documentation. This disclosure is included for RuneLite Plugin Hub reviewers.

## Build and launch

With JDK 11 or 17 and Gradle 7.5.1 or newer, open a terminal in this directory.

```powershell
gradle -PruneLiteVersion=1.13.8 build
gradle -PruneLiteVersion=1.13.8 run
```

`build` runs the transport test and creates `build/libs/runelite-rune-ledger-0.1.0.jar`. The `run` task starts a RuneLite developer client and registers Rune Ledger Sync. Find it in the plugin list and follow the pairing steps above. Plugin Hub distribution depends on RuneLite review.

For an offline build using JARs from a previously run RuneLite version, pass the launcher’s local dependency cache. The runtime API in that cache omits the named game constants. Download the full [RuneLite 1.13.8 compile-time API](https://repo.runelite.net/net/runelite/runelite-api/1.13.8/runelite-api-1.13.8.jar) once and place it at `.gradle/runeledger-api/runelite-api-1.13.8.jar` inside this plugin directory. Then run:

```powershell
gradle --offline "-PruneLiteCache=$env:USERPROFILE/.runelite/repository2" -PruneLiteVersion=1.13.8 build
```

Use the same `-PruneLiteCache` option with `run` to launch from that cache. To use a different RuneLite release, download its matching compile-time API and update the version in both commands.

If Gradle is installed in the Windows wrapper cache but missing from PATH, you can launch the developer client from the cache:

```powershell
$gradle = Get-ChildItem "$env:USERPROFILE/.gradle/wrapper/dists/gradle-7.5.1-bin" -Recurse -Filter gradle.bat |
  Select-Object -First 1 -ExpandProperty FullName
& $gradle --offline "-PruneLiteCache=$env:USERPROFILE/.runelite/repository2" -PruneLiteVersion=1.13.8 run
```

Set `JAVA_HOME` to the JDK installation if Gradle cannot find Java.

## Sign in with a Jagex Account

Keep the RuneLite window started by the Gradle `run` task open. In its sidebar, open the **Jagex Auth** panel and choose **Add Jagex Account**. Complete sign-in through Jagex, select your character in the sidebar, and click **Play Now**. Launching from the separate Jagex Launcher opens the regular RuneLite installation, where this development plugin is absent. See [RuneLite’s Jagex Account guide](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) for the sidebar icon and full instructions.

If an offline cache launch logs `jagexauth not found`, the cached dependencies lack RuneLite’s sign-in helper. Use the ordinary Gradle `run` command to resolve RuneLite’s runtime dependencies, or place the official [1.13.8-compatible helper](https://repo.runelite.net/net/runelite/jagexauth/0.3/jagexauth-0.3.jar) at `build/dependencies/jagexauth-0.3.jar` before running from the cache. The helper is used only by the development launcher.

## Test without logging into OSRS

Run the `transportTest` task using the same Gradle and cache options as `build`. It sends synthetic snapshots to a temporary loopback HTTP server and checks authentication, exact XP, bank payloads, Slayer task states, redirect handling, and cancellation. It reads synthetic Slayer game variables and cache rows without starting the RuneLite client.

An in-game check also verified skills, bank contents, and an active Slayer task against the hosted site on RuneLite 1.13.8.

## License

This plugin is available under the [BSD 2-Clause License](LICENSE).
