# Rune Ledger Sync for RuneLite

Rune Ledger Sync sends your own OSRS character data from RuneLite to a hosted or self-hosted Rune Ledger site. Rune Ledger uses those snapshots for skill planning, bank advice, and Slayer tracking.

## Pair the Windows app beta

This branch is for local testing. Use it with Rune Ledger for Windows `0.1.2-beta.1` or a compatible later desktop build.

1. In the Windows app, use **Player lookup → RuneLite sync** to generate or copy the sync code for your character. Turn on **RuneLite → Enable local sync** from the app menu.
2. In the beta plugin's settings, turn on **Local app** and **Enable account sync**. Log in to the matching character. Open the **Rune Ledger Sync** sidebar, paste the code and select **Use code**. The panel shows the character and destination.
3. To track multiple characters, repeat this for each logged-in character. Keep the Windows app open while syncing. **Hosted Rune Ledger** settings apply when **Local app** is off.

The app must use its default Windows data folder for discovery. The beta reads the current local listener address from a per-user descriptor and checks the listener's identity before submitting the code to `127.0.0.1`. The app publishes the descriptor only while local sync is enabled.

## Pair RuneLite

1. Open **Player lookup → RuneLite sync** on [Rune Ledger](https://rune-ledger.vercel.app/) or on your self-hosted site. Enter your OSRS display name and select **Generate pairing token**. Returning users can sign in to an existing Rune Ledger account first.
2. In RuneLite’s **Rune Ledger Sync** settings, leave **Local app** off and enter the site’s HTTPS root URL and the same display name. Enable **Enable account sync**. Log in to the matching character, paste the pairing token into the **Rune Ledger Sync** sidebar, and select **Use code**.
3. Enable **Include bank contents**, **Include Slayer task**, or **Include quest completion** for the features you want. Open your regular bank to send a bank snapshot. Load the snapshot from the site’s RuneLite sync panel.

## Sync code storage

The sidebar keeps codes separate from RuneLite configuration and cloud profiles. On Windows, it saves codes in user-scoped DPAPI-encrypted files, one per character and destination. Session-only use is available when Windows protection is unavailable or on another operating system. **Clear code** removes the code for the character and destination shown in the sidebar.

Replace any pairing tokens previously entered through RuneLite's plugin settings, then enter the new codes through the sidebar. Rotation invalidates copies that may remain in older profile exports or debug logs.

## Data sent

Network sync defaults to off and runs only for the matching logged-in character. The configured server receives the display name, real skill levels, and exact XP and can see your IP address. It uses the Rune Ledger pairing token to authenticate uploads. Bank sync separately sends item IDs and quantities from the regular bank after you open it. Slayer sync separately sends the current assignment, remaining count, starting count when available, and assigned area. Quest sync separately sends the completed quest and miniquest IDs alongside the number of entries RuneLite checked. All three optional settings default to off.

If you pause and delete quest data in Rune Ledger, disable **Include quest completion** in RuneLite to stop sending it. To send a fresh observation after you resume quest data in Rune Ledger, turn **Include quest completion** off and back on.

## AI development disclosure

Rune Ledger Sync was built with OpenAI Codex, which generated plugin code, tests, and documentation. This disclosure is included for RuneLite Plugin Hub reviewers.

## Build and launch

With JDK 17 and Gradle 7.5.1, open a terminal in this directory. The beta defaults to RuneLite 1.13.8.

```powershell
gradle -PruneLiteVersion=1.13.8 build
gradle -PruneLiteVersion=1.13.8 run
```

`build` runs the transport test and creates `build/libs/runelite-rune-ledger-0.1.1-beta.3.jar`. The `run` task starts a RuneLite developer client and registers Rune Ledger Sync. Find it in the plugin list and follow the pairing steps above. Plugin Hub distribution depends on RuneLite review.

Hosted uploads use JDK HTTPS with certificate and hostname verification. The numeric loopback connection uses RuneLite's HTTP library with the desktop HMAC check. RuneLite still supplies its own runtime libraries, so updates to those libraries follow the client release.

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

Run the `transportTest` task using the same Gradle and cache options as `build`. It checks desktop HMAC verification and connection binding, hosted TLS validation, authentication, payloads, redirect handling, and cancellation with synthetic loopback servers. On Windows, it also verifies that codes survive encrypted save and replace, stay separate by character and destination, and can be removed. It reads synthetic Slayer game variables and cache rows without starting RuneLite.

An in-game check also verified skills, bank contents, and an active Slayer task against the hosted site on RuneLite 1.13.8.

## License

This plugin is available under the [BSD 2-Clause License](LICENSE).
