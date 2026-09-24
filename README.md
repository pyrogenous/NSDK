<p align="center">
  <img src=".github/logo.png" alt="Nitea" width="96">
</p>

<h1 align="center">Nitea</h1>

<p align="center">
  Free, privacy-first error tracking and player feedback for NeoForge, Forge and Fabric mods.<br>
  <a href="https://nitea.cc">Website</a> · <a href="https://nitea.cc/d">Dashboard</a> · <a href="https://nitea.cc/legal/privacy-policy">Privacy policy</a>
</p>

---

Nitea is a small library you embed in your mod. It reports errors, uncaught exceptions and crashes caused by your mod to your [Nitea dashboard](https://nitea.cc/d), groups them into issues, and lets players send bug reports and suggestions that you can show on a public roadmap.

- **Opt-in only.** Nothing is sent until the player says yes. Nitea asks once, for every mod using it.
- **A library, not a mod.** On NeoForge and Forge it doesn't show up in the mod list; on Fabric it's a library mod without an entry point. However many mods embed it, the game has one Nitea: one consent screen, one settings file.
- **Only your bugs.** Each mod only receives the errors and crashes its own code caused, never another mod's.
- **Anonymous.** No usernames, player UUIDs or IP addresses. Ever.
- **No dependencies.** Not even Fabric API. Events are sent from a background thread and rate limited, so the game never waits on the network.

## Supported versions

| Minecraft | NeoForge | Forge | Fabric | Java |
| --------- | :------: | :---: | :----: | ---- |
| 26.2      | ✓        | ✓     | ✓      | 25   |
| 26.1      | ✓        | ✓     | ✓      | 25   |
| 1.21.11   | ✓        | ✓     | ✓      | 21   |
| 1.21.1    | ✓        | ✓     | ✓      | 21   |
| 1.20.1    | ✓        |       |        | 17   |

Every loader and Minecraft version gets its own artifact, `nitea-<loader>-<minecraft>`, all built from this repository.

## Add Nitea to your mod

### 1. Add the dependency

Nitea is published in its own Maven repository, `https://libraries.nitea.cc`, as `cc.nitea:nitea-<loader>-<minecraft>:<version>`. Bundle it inside your mod jar; when several mods ship it, the loader keeps one copy, the newest.

**NeoForge** (ModDevGradle):

```groovy
repositories {
    maven { url 'https://libraries.nitea.cc' }
}

dependencies {
    jarJar(implementation("cc.nitea:nitea-neoforge-26.2:0.3.0"))
}
```

For 1.20.1 (ModDevGradle Legacy), use `jarJar(modImplementation(...))`.

**Forge** (ForgeGradle 7 and Forge's Jar-in-Jar plugin):

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '[7.0.17,8)'
    id 'net.minecraftforge.jarjar' version '0.2.3'
}

jarJar.register()

// The jar with Nitea inside is the one to release: build/libs/<mod>-<version>.jar (by default it's the -all.jar)
tasks.named('jar', Jar) {
    archiveClassifier = 'slim'
}
tasks.named('jarJar') {
    archiveClassifier = ''
}

repositories {
    maven { url 'https://libraries.nitea.cc' }
}

dependencies {
    implementation(jarJar("cc.nitea:nitea-forge-26.2:0.3.0"))
}
```

**Fabric** (Loom):

```groovy
repositories {
    maven { url 'https://libraries.nitea.cc' }
}

dependencies {
    // 26.x, net.fabricmc.fabric-loom
    include(implementation("cc.nitea:nitea-fabric-26.2:0.3.0"))
    // 1.21.x, net.fabricmc.fabric-loom-remap
    // include(modImplementation("cc.nitea:nitea-fabric-1.21.1:0.3.0"))
}
```

Don't list Nitea in `neoforge.mods.toml`, `mods.toml` or `fabric.mod.json`: the copy you bundle is always there.

### 2. Add your SDK key

Create a project on [nitea.cc](https://nitea.cc), copy its SDK key (`nt_…`) and put it in `src/main/resources/nitea/<modid>.properties`:

```properties
sdkKey=nt_your_sdk_key
```

Keep it out of git. The example mod generates this file at build time from a git-ignored `.env`. The key can only send events to your project, never read anything.

You can also set it with the system property `-Dnitea.<modid>.sdkKey=…` or the environment variable `NITEA_<MODID>_SDK_KEY`.

### 3. Start Nitea

As soon as your mod starts. On NeoForge, at the very start of your mod's constructor:

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

On Forge, the constructor takes `FMLJavaModLoadingContext context` and the version is
`context.getContainer().getModInfo().getVersion().toString()`. On Fabric, in `onInitialize`:

```java
FabricLoader loader = FabricLoader.getInstance();
NITEA = Nitea.init(NiteaOptions.builder(MODID)
        .owner(MyMod.class)
        .release(loader.getModContainer(MODID).orElseThrow().getMetadata().getVersion().getFriendlyString())
        .gameDir(loader.getGameDir())
        .build());
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

- **One copy.** Every mod bundles Nitea in its jar and the loader keeps a single copy, the newest. On NeoForge and Forge, Nitea is a game library (`FMLModType: GAMELIBRARY`): it runs next to Minecraft but isn't a mod. On Fabric it's a library mod (ID `nitea`) with no entry point. The first mod calling `Nitea.init` starts its screens.
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
| [`core/`](core) | The loader-independent library: the public API (`Nitea`, `NiteaClient`, `NiteaOptions`, `NiteaConsent`, `Level`) and its internals (capturing, attribution, consent, sending). Java 8, no dependencies. |
| `versions/<minecraft>/common/` | The in-game screens for that Minecraft version (consent screen, title screen button), shared by every loader. Plain Minecraft code. |
| `versions/<minecraft>/neoforge/` | The NeoForge artifact: hooks the screens to NeoForge's screen events. |
| `versions/<minecraft>/forge/` | The Forge artifact: hooks the screens to Forge's screen events. |
| `versions/<minecraft>/fabric/` | The Fabric artifact: `fabric.mod.json` and two client mixins that do the same (no Fabric API). |
| [`gradle/nitea-module.gradle`](gradle/nitea-module.gradle) | Shared setup of every artifact: compiles `core/` and `common/` in, names, versions and publishes the jar. |

Each `versions/<minecraft>/<loader>/` directory is its own Gradle build, included from the root, so each can use the loader plugin (ModDevGradle, ForgeGradle 7, Fabric Loom) and Java version its era needs. The core calls the loader side by class name (`cc.nitea.neoforge.NiteaNeoForge`, `cc.nitea.forge.NiteaForge`, `cc.nitea.fabric.NiteaFabric`), so it never depends on a loader.

To support a new Minecraft version, copy the closest `versions/<minecraft>/` directory, update the versions in its `build.gradle` files and fix what the new Minecraft changed in `common/`.

## Building

Requirements: JDK 21 or newer to run Gradle; Gradle downloads the JDKs the builds need (17, 21 and 25) if they're missing.

```sh
./gradlew buildAll               # compile every artifact and test the core
./gradlew publishAll             # write every artifact to build/repo
./gradlew publishToMavenLocal    # install every artifact in ~/.m2 (cc.nitea:nitea-<loader>-<minecraft>), for testing with mavenLocal()
./gradlew :fabric-1.21.1:build   # a single artifact: <loader>-<minecraft>
```

### Releasing

Push a version tag and the [Publish workflow](.github/workflows/publish.yml) does the rest:

```sh
git tag 0.3.1
git push origin 0.3.1
```

It builds and tests every artifact with the tag as the version, adds them to the Maven repository on the `gh-pages` branch (older versions stay), and GitHub Pages serves it at https://libraries.nitea.cc (custom domain, set in the workflow).

JitPack can still build the repository too (`jitpack.yml`, as `com.github.pyrogenous.NSDK:nitea-<loader>-<minecraft>:<tag>`), but the first build of a version may hit its time limit.

### Example mod

The **Nitea NeoForge Example Mod** is a separate repository: a test mod that uses Nitea and triggers errors, crashes and player reports on demand.

## Privacy

A report contains what went wrong (exception, stack trace, message), what the mod recorded (breadcrumbs, tags), the game setup (Minecraft, loader, Java and OS versions, CPU architecture and cores, maximum memory, client or server) and, only while the player allows reporting, a random installation ID. Never usernames, player UUIDs, IP addresses or chat. See the [privacy policy](https://nitea.cc/legal/privacy-policy).
