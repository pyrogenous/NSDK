<p align="center">
  <img src=".github/logo.png" alt="Nitea" width="96">
</p>

<h1 align="center">Nitea</h1>

<p align="center">
  Free error tracking and player feedback for NeoForge, Forge and Fabric mods.<br>
  <a href="https://nitea.cc">Website</a> · <a href="https://nitea.cc/d">Dashboard</a> · <a href="https://docs.nitea.cc">Docs</a> · <a href="https://nitea.cc/legal/privacy-policy">Privacy policy</a>
</p>

---

Nitea is a small library you put inside your mod. When your mod throws an error or crashes the game, Nitea sends the stack trace to your dashboard on [nitea.cc](https://nitea.cc), where the same errors are grouped together so you can see what breaks most often. Players can also send you bug reports and suggestions from the game.

A few things worth knowing before you add it:

- Nothing is sent until the player agrees. Nitea asks once, and the answer covers every mod that uses it.
- Reports don't contain usernames, player UUIDs or IP addresses.
- Your mod only gets errors caused by its own code, not other mods' errors.
- It has no dependencies (not even Fabric API) and sends everything from a background thread, so it won't slow the game down.

## Supported versions

| Minecraft | NeoForge | Forge | Fabric | Java |
| --------- | :------: | :---: | :----: | ---- |
| 26.2      | yes      | yes   | yes    | 25   |
| 26.1      | yes      | yes   | yes    | 25   |
| 1.21.11   | yes      | yes   | yes    | 21   |
| 1.21.1    | yes      | yes   | yes    | 21   |
| 1.20.1    | yes      |       |        | 17   |

There's one artifact per loader and Minecraft version, named `nitea-<loader>-<minecraft>`.

## Setup

### 1. Add the dependency

The library is hosted at `https://libraries.nitea.cc`. You bundle it inside your mod's jar. If several mods bundle it, the loader only loads one copy (the newest).

NeoForge (ModDevGradle):

```groovy
repositories {
    maven { url 'https://libraries.nitea.cc' }
}

dependencies {
    jarJar(implementation("cc.nitea:nitea-neoforge-26.2:0.4.0"))
}
```

On 1.20.1 (ModDevGradle Legacy) use `jarJar(modImplementation(...))` instead.

Forge (ForgeGradle 7 with the Jar-in-Jar plugin):

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '[7.0.17,8)'
    id 'net.minecraftforge.jarjar' version '0.2.3'
}

jarJar.register()

// Make the jar that contains Nitea the main one, instead of the "-all" jar
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
    implementation(jarJar("cc.nitea:nitea-forge-26.2:0.4.0"))
}
```

Fabric (Loom):

```groovy
repositories {
    maven { url 'https://libraries.nitea.cc' }
}

dependencies {
    // 26.x (net.fabricmc.fabric-loom)
    include(implementation("cc.nitea:nitea-fabric-26.2:0.4.0"))
    // 1.21.x (net.fabricmc.fabric-loom-remap)
    // include(modImplementation("cc.nitea:nitea-fabric-1.21.1:0.4.0"))
}
```

You don't need to add Nitea to `neoforge.mods.toml`, `mods.toml` or `fabric.mod.json`.

### 2. Add your SDK key

Create a project on [nitea.cc](https://nitea.cc) and copy its SDK key (it starts with `nt_`). Put it in `src/main/resources/nitea/<modid>.properties`:

```properties
sdkKey=nt_your_sdk_key
```

The key ends up inside your jar, so anyone can find it. That's fine: it can only send events to your project, it can't read anything. Still, keep it out of your public git repo so people don't copy it into their own builds by accident. The example mod does this by generating the file at build time from a git-ignored `.env`.

You can also pass the key with `-Dnitea.<modid>.sdkKey=...` or the `NITEA_<MODID>_SDK_KEY` environment variable.

The key only works for your mod: from 0.4, Nitea signs every request for your mod ID and the class you pass to `owner(...)`, and the API refuses it unless both match the project (set the Java package in your project settings). Requests also carry a small proof of work and are rate limited, so the key can't easily be used to flood your project.

### 3. Start Nitea

Call `Nitea.init` as early as possible. On NeoForge, at the top of your mod's constructor:

```java
@Mod(MyMod.MODID)
public final class MyMod {
    public static final String MODID = "mymod";
    public static NiteaClient NITEA;

    public MyMod(IEventBus modEventBus, ModContainer modContainer) {
        NITEA = Nitea.init(NiteaOptions.builder(MODID)
                .owner(MyMod.class)
                .release(modContainer.getModInfo().getVersion().toString())
                .gameDir(FMLPaths.GAMEDIR.get())
                .build());
    }
}
```

`owner` tells Nitea which package is your code. On Forge the constructor gets a `FMLJavaModLoadingContext context`, and the version is `context.getContainer().getModInfo().getVersion().toString()`.

On Fabric, in `onInitialize`:

```java
FabricLoader loader = FabricLoader.getInstance();
NITEA = Nitea.init(NiteaOptions.builder(MODID)
        .owner(MyMod.class)
        .release(loader.getModContainer(MODID).orElseThrow().getMetadata().getVersion().getFriendlyString())
        .gameDir(loader.getGameDir())
        .build());
```

You don't need to build a consent screen, Nitea has its own.

### 4. Send reports

After `init`, uncaught exceptions from your code are reported on their own, and so are Minecraft crash reports your mod caused (those are sent the next time the game starts).

You can also send things yourself:

```java
NITEA.addBreadcrumb("machine", "Generator started");   // shows up with the next event
NITEA.setTag("difficulty", "hard");

try {
    loadConfig();
} catch (IOException e) {
    NITEA.captureException(e);
}

NITEA.captureMessage("Network has no controller", Level.WARNING);

// Feedback from players, for example from a command or a button
NITEA.reportBug(textFromPlayer);
NITEA.reportSuggestion(textFromPlayer, link -> player.sendSystemMessage(Component.literal(link)));
```

If your project has a public page, `reportBug` and `reportSuggestion` return a one-time link where the player can add details and screenshots. On the client Nitea opens it in the browser. On a server, use the callback to send the link to the player.

## Player consent

The consent belongs to Nitea, not to your mod. If a player has five mods that use Nitea, they're asked once.

The first time the title screen opens, Nitea shows its own screen listing the mods that use it and what a report contains. The player picks "Allow reports" or "Don't allow". The choice is saved in `config/nitea/nitea.properties`, and there's a Nitea button on the title screen to change it later.

Until the player answers, events are kept in memory (up to 25 per mod) and nothing is sent. If they allow it, those events go out and a random installation ID is created so you can count how many players are affected. If they refuse, the events are thrown away and the ID is deleted. Crash reports written before the player said yes are never sent.

Dedicated servers have no screen, so nothing is sent until the server owner edits the file:

```properties
# config/nitea/nitea.properties
consent=granted            # or denied
examplemod.enabled=false   # optional, turns off one mod
```

## When several mods use Nitea

Every mod bundles its own copy, but only one runs. On NeoForge and Forge it's loaded as a game library, so it doesn't show up in the mod list. On Fabric it's a library mod with the ID `nitea`.

If a mod shades its own copy anyway, all copies still share the same consent, installation ID and list of mods, because that state is stored in one place for the whole JVM.

To decide which mod an uncaught error or crash belongs to, Nitea looks at the stack trace and skips Java, Minecraft and loader frames. The first frame left decides:

- if it's from a mod that uses Nitea, that mod gets the report;
- if it's from some other mod or library, nobody gets it;
- if it's a Mixin injected into Minecraft code, the mod that owns the Mixin gets it.

So if your mod calls another mod and that one throws, the other mod gets the report. Anything you pass to `captureException` yourself is always sent under your mod.

## Options

| Option | Default | What it does |
| ------ | ------- | ------------ |
| `owner(Class)` | | A class from your mod. Its package is treated as your code. |
| `inAppPackages(String...)` | owner's package | Use this if your code lives in more than one package. |
| `release(String)` | | Your mod's version. |
| `gameDir(Path)` | working directory | The folder that contains `config/` and `crash-reports/`. |
| `tag(String, String)` | | A tag added to every event. |
| `maxBreadcrumbs(int)` | 100 | How many breadcrumbs to attach (100 max). |
| `captureUncaught(boolean)` | true | Report uncaught exceptions from your code. |
| `scanCrashReports(boolean)` | true | Report crash reports your mod caused. |
| `openReportLinks(boolean)` | true | Open the browser for player reports on the client. |
| `environment`, `minecraftVersion`, `loader`, `side` | detected | Override what Nitea detects. |
| `debug(boolean)` | false | Log every request and response. |

## What gets sent

A report has the error itself (exception, stack trace, message), the breadcrumbs and tags your mod added, and some info about the setup: Minecraft, loader, Java and OS versions, CPU architecture and core count, max memory, and whether it's a client or a server. If the player allowed reporting, it also has the random installation ID.

Exception messages are sent as they are. If your mod puts a player name or a file path in an exception message, that ends up in the report too, so avoid that.

Everything is sent over HTTPS. More details in the [privacy policy](https://nitea.cc/legal/privacy-policy).

## Working on Nitea

Read [AGENTS.md](AGENTS.md) before changing anything: it has the compatibility rules (5 versions of guaranteed compatibility, the kill switch for older mods, public signatures kept forever) and the release checklist.

### Layout

- `core/` is the part that doesn't depend on any loader: the public API (`Nitea`, `NiteaClient`, `NiteaOptions`, `NiteaConsent`, `Level`) and everything behind it. Java 8, no dependencies.
- `versions/<minecraft>/common/` has the in-game screens for that Minecraft version, shared by all loaders.
- `versions/<minecraft>/neoforge/`, `forge/` and `fabric/` hook those screens into each loader. Fabric uses two small mixins instead of Fabric API.
- `gradle/nitea-module.gradle` is the build setup shared by every artifact.

Each `versions/<minecraft>/<loader>/` folder is a separate Gradle build included from the root, so each one can use its own loader plugin and Java version.

To add a new Minecraft version, copy the closest `versions/<minecraft>/` folder, bump the versions in its `build.gradle` files and fix whatever changed in `common/`.

### Building

You need JDK 21 or newer to run Gradle. It downloads JDK 17, 21 and 25 if they're missing.

```sh
./gradlew buildAll               # build everything and run the core tests
./gradlew publishAll             # write every artifact to build/repo
./gradlew publishToMavenLocal    # install to ~/.m2 to test with mavenLocal()
./gradlew :fabric-1.21.1:build   # just one artifact: <loader>-<minecraft>
```

### Releasing

Push a version tag:

```sh
git tag 0.4.1
git push origin 0.4.1
```

The [Publish workflow](.github/workflows/publish.yml) builds and tests everything with that version, adds it to the Maven repository on the `gh-pages` branch (old versions are kept), and GitHub Pages serves it at https://libraries.nitea.cc.

JitPack can also build the repo (see `jitpack.yml`), but the first build of a version sometimes times out.

There's also a separate example mod repository, a NeoForge test mod that triggers errors, crashes and player reports on demand.
