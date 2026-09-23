<p align="center">
  <img src=".github/logo.png" alt="Nitea" width="96">
</p>

<h1 align="center">Nitea for NeoForge</h1>

<p align="center">
  Free, privacy-first error tracking and player feedback for NeoForge mods.<br>
  <a href="https://nitea.cc">Website</a> · <a href="https://nitea.cc/d">Dashboard</a> · <a href="https://nitea.cc/legal/privacy-policy">Privacy policy</a>
</p>

---

Nitea is a small library you embed in your mod. It reports errors, uncaught exceptions and crashes caused by your mod to your [Nitea dashboard](https://nitea.cc/d), groups them into issues, and lets players send bug reports and suggestions that you can show on a public roadmap.

- **Opt-in only.** Nothing is sent until the player says yes. Nitea asks once, for every mod using it.
- **A library, not a mod.** It doesn't show up in the mod list. However many mods embed it, the game has one Nitea: one consent screen, one settings file.
- **Only your bugs.** Each mod only receives the errors and crashes its own code caused, never another mod's.
- **Anonymous.** No usernames, player UUIDs or IP addresses. Ever.
- **No dependencies.** Events are sent from a background thread and rate limited, so the game never waits on the network.

Supports NeoForge for Minecraft 26.2.

## Add Nitea to your mod

### 1. Add the dependency

Nitea is published on [JitPack](https://jitpack.io). In your mod's `build.gradle` (ModDevGradle), replace `<user>`, `<repo>` and `<tag>`:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    // Bundled in your jar with Jar-in-Jar. When several mods ship Nitea, NeoForge loads one copy, the newest.
    jarJar(implementation("com.github.<user>:<repo>:<tag>"))
}
```

### 2. Add your SDK key

Create a project on [nitea.cc](https://nitea.cc), copy its SDK key (`nt_…`) and put it in `src/main/resources/nitea/<modid>.properties`:

```properties
sdkKey=nt_your_sdk_key
```

Keep it out of git. The example mod generates this file at build time from a git-ignored `.env`. The key can only send events to your project, never read anything.

You can also set it with the system property `-Dnitea.<modid>.sdkKey=…` or the environment variable `NITEA_<MODID>_SDK_KEY`.

### 3. Start Nitea

At the very start of your mod's constructor:

```java
@Mod(MyMod.MODID)
public final class MyMod {
    public static final String MODID = "mymod";
    public static NiteaClient NITEA;

    public MyMod(IEventBus modEventBus, ModContainer modContainer) {
        NITEA = Nitea.init(NiteaOptions.builder(MODID)
                .owner(MyMod.class)                                  // your package is "your code"
                .release(modContainer.getModInfo().getVersion().toString())
                .gameDir(FMLPaths.GAMEDIR.get())
                .build());
        // ...
    }
}
```

That's all. You never show a consent screen yourself: Nitea does it for you.

### 4. Report things

```java
NITEA.addBreadcrumb("machine", "Generator started");   // context attached to the next event
NITEA.setTag("difficulty", "hard");

try {
    loadConfig();
} catch (IOException e) {
    NITEA.captureException(e);                          // or captureException(e, Level.WARNING, Map.of(...))
}

NITEA.captureMessage("Network has no controller", Level.WARNING);

// Player feedback, e.g. from a command or a button
NITEA.reportBug(textFromPlayer);
NITEA.reportSuggestion(textFromPlayer, link -> player.sendSystemMessage(Component.literal(link)));
```

Reported automatically once `Nitea.init` has run:

- uncaught exceptions your mod caused
- Minecraft crash reports your mod caused, sent on the next launch

When your project has a public page, `reportBug` and `reportSuggestion` get back a one-time link where the player adds a description, steps and screenshots. On the client Nitea opens it in the browser; on a server, pass a callback and send the player the link.

## Player consent

Consent belongs to **Nitea, not to the mod that embeds it**. A player who installs five mods using Nitea is asked once, and their answer applies to all five.

1. The first time the title screen opens, Nitea shows its consent screen instead. It lists every mod using Nitea and explains what a report contains. The player has to choose **Allow reports** or **Don't allow**.
2. The answer is saved in `config/nitea/nitea.properties` and never asked again.
3. A Nitea button next to the title screen's small icon buttons opens the preferences screen, where the player can opt in or out at any time.

| State | What happens |
| ----- | ------------ |
| Not chosen yet | Events wait in memory (up to 25 per mod). Nothing leaves the computer. |
| Allowed | Waiting and new events are sent. A random installation ID is created to count affected players. |
| Not allowed | Waiting events are dropped, nothing is ever sent, the installation ID is deleted and the player isn't asked again. |

Crash reports written before the player allowed reporting are never sent.

On a dedicated server there is no screen: nothing is sent until the server owner sets `consent=granted` in `config/nitea/nitea.properties`.

```properties
# config/nitea/nitea.properties
consent=granted            # or denied
examplemod.enabled=false   # optional: turn off a single mod
```

## Many mods, one Nitea

Mods don't talk to each other, so Nitea makes sure they don't need to:

- **One copy.** Every mod bundles Nitea with Jar-in-Jar and NeoForge loads a single copy, the newest. Nitea is a game library (`FMLModType: GAMELIBRARY`): it runs next to Minecraft but isn't a mod, and the first mod calling `Nitea.init` starts its screens.
- **Shared state.** Even when a mod shades its own copy, every copy shares the same state: the consent, the installation ID and the list of mods using Nitea live in one JVM-wide registry made only of JDK types, readable whatever class loader loaded the copy. Changing the consent from any copy updates all of them at once.
- **Attribution.** Each mod registers its packages and its Java module. When an uncaught exception or a Minecraft crash happens, Nitea starts from the root cause and walks the stack past JDK, Minecraft and loader frames. The first frame left decides:
  - it belongs to a mod using Nitea: only that mod reports it;
  - it belongs to another mod or library: nobody reports it;
  - it's Mixin code injected into the game (`handler$…$modid$…`): the mod that injected it.

  So when mod A calls mod B and B throws, B gets the issue, not A. When the game throws because A passed it bad data, A gets it.

Exceptions you pass to `captureException` yourself are always reported by your mod: you chose to send them.

## Options

| Option | Default | What it does |
| ------ | ------- | ------------ |
| `owner(Class)` | | A class of your mod. Its package marks your code, its class loader finds your resources. |
| `inAppPackages(String...)` | owner's package | Your code's packages, when it spans several. |
| `release(String)` | | Your mod version. |
| `gameDir(Path)` | working directory | Where `config/` and `crash-reports/` are. |
| `tag(String, String)` | | A tag sent with every event. |
| `maxBreadcrumbs(int)` | 100 | Breadcrumbs attached to each event (at most 100). |
| `captureUncaught(boolean)` | true | Report uncaught exceptions your mod caused. |
| `scanCrashReports(boolean)` | true | Report crash reports your mod caused, on the next launch. |
| `openReportLinks(boolean)` | true | Open the browser to complete player reports on the client. |
| `environment`, `minecraftVersion`, `loader`, `side` | detected | Override what Nitea detects. |
| `debug(boolean)` | false | Log every request and response. |

## Repository layout

| Path | What it is |
| ---- | ---------- |
| [`src/main/java/cc/nitea/`](src/main/java/cc/nitea) | The public API (`Nitea`, `NiteaClient`, `NiteaOptions`, `NiteaConsent`, `Level`) and its internals: capturing, attribution, consent, sending. |
| [`src/main/java/cc/nitea/neoforge/`](src/main/java/cc/nitea/neoforge) | The in-game screens: consent screen and title screen button. |

## Building

Requirements: JDK 21 or newer to run Gradle; Gradle downloads JDK 25 for the build if it's missing.

```sh
./gradlew build                 # the library jar, in build/libs
./gradlew publishToMavenLocal   # cc.nitea:nitea-neoforge, for testing in your own mod with mavenLocal()
```

### Example mod

The **Nitea NeoForge Example Mod** is a separate repository: a test mod that uses Nitea and triggers errors, crashes and player reports on demand. Clone it next to this repository and it compiles the library from here, so changes show up right away.

## Privacy

A report contains what went wrong (exception, stack trace, message), what the mod recorded (breadcrumbs, tags), the game setup (Minecraft, loader, Java and OS versions, CPU architecture and cores, maximum memory, client or server) and, only while the player allows reporting, a random installation ID. Never usernames, player UUIDs, IP addresses or chat. See the [privacy policy](https://nitea.cc/legal/privacy-policy).
