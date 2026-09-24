package cc.nitea.internal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one Nitea engine of the game, whatever the number of mods embedding the library.
 *
 * <p>Usually the mod loader keeps a single copy of the library (Jar-in-Jar picks the newest), but a mod may still
 * shade its own copy. Every copy therefore keeps its shared state in one map stored in the JVM's system
 * properties, holding only JDK types so all copies can read it whatever class loader loaded them:
 * <ul>
 *   <li>the player's consent, shared by every mod: Nitea asks once, on behalf of the library, not of each mod</li>
 *   <li>the anonymous installation ID, which only exists while the player has opted in</li>
 *   <li>every mod using Nitea and its packages, so an uncaught error or crash is reported only by the mod that
 *       caused it</li>
 * </ul>
 */
public final class Engine {
    /** Consent values, stored as plain strings so every copy of the library agrees on them. */
    public static final String UNDECIDED = "undecided";
    public static final String GRANTED = "granted";
    public static final String DENIED = "denied";

    private static final String KEY = "cc.nitea.engine.v1";
    private static final String HEADER = String.join("\n",
            " Nitea: anonymous error reporting for Minecraft mods (https://nitea.cc)",
            " Mods using Nitea can send crash and error reports to their developers, only if you allow it.",
            " Reports never include usernames, player UUIDs or IP addresses.",
            "",
            " consent=granted sends reports, consent=denied never sends anything. Without it, you are asked in game",
            " (on a dedicated server, nothing is sent until you set it). Change it any time from the Nitea button on",
            " the title screen.",
            " To turn off a single mod, add <modid>.enabled=false",
            " installationId is random, only exists while reporting is allowed, and counts how many installations an",
            " issue affects.");

    // Classes that belong to the game, the loader, the JDK or common libraries: never the cause of an error
    private static final String[] PLATFORM_PACKAGES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "com.mojang.", "net.neoforged.",
        "net.minecraftforge.", "cpw.mods.", "net.fabricmc.", "org.quiltmc.", "org.spongepowered.", "com.llamalad7.",
        "org.objectweb.", "io.netty.", "com.google.", "org.apache.", "org.slf4j.", "it.unimi.", "org.lwjgl.", "org.joml.",
        "kotlin.", "org.jetbrains.", "com.electronwill.", "oshi.", "org.jline.", "paulscode.", "scala.", "gnu.trove.",
        "com.typesafe.", "com.ibm.icu.", "LZMA.", "cc.nitea.internal.",
    };
    private static final Set<String> PLATFORM_MODULES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft", "neoforge", "forge", "fml_loader", "fml_earlydisplay", "loader", "mixin", "mixinextras", "nitea")));
    // StackTraceElement.getModuleName() only exists from Java 9
    private static final Method MODULE_NAME = moduleNameMethod();
    // Methods Mixin merges into game classes: handler$abc000$modid$method (and redirect$, modify$, wrap...)
    private static final Pattern MIXIN_METHOD = Pattern.compile(
            "^(?:handler|redirect|modify|localvar|constant|args|wrapOperation|wrapWithCondition|modifyExpressionValue|modifyReturnValue|modifyArg|modifyArgs|modifyVariable)\\$[a-z0-9]+\\$([a-z][a-z0-9_-]{1,63})\\$");

    private Engine() {}

    // ------------------------------------------------------------------------------------------------------------
    // Shared state
    // ------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shared() {
        // Properties.computeIfAbsent is atomic, so two copies starting at once still end up with one map
        return (Map<String, Object>) System.getProperties().computeIfAbsent(KEY, k -> new ConcurrentHashMap<String, Object>());
    }

    @SuppressWarnings("unchecked")
    private static <T> T entry(String name, java.util.function.Supplier<T> create) {
        return (T) shared().computeIfAbsent(name, k -> create.get());
    }

    private static AtomicReference<String> consentRef() {
        return entry("consent", AtomicReference::new);
    }

    private static AtomicReference<String> installationRef() {
        return entry("installationId", AtomicReference::new);
    }

    private static AtomicReference<String> gameDirRef() {
        return entry("gameDir", AtomicReference::new);
    }

    private static Object lock() {
        return entry("lock", Object::new);
    }

    private static List<Runnable> listeners() {
        return entry("listeners", CopyOnWriteArrayList::new);
    }

    // modId -> packages of its own code
    private static Map<String, List<String>> modPackages() {
        return entry("modPackages", ConcurrentHashMap::new);
    }

    // modId -> Java module of its classes, when the loader puts mods in named modules (NeoForge does)
    private static Map<String, String> modModules() {
        return entry("modModules", ConcurrentHashMap::new);
    }

    /** Only for tests: forgets everything, as if the game had just started. */
    public static void reset() {
        System.getProperties().remove(KEY);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Game directory and settings file
    // ------------------------------------------------------------------------------------------------------------

    /** Sets the game directory (holding {@code config/nitea/}). The first call wins; later ones are ignored. */
    public static void useGameDir(Path gameDir) {
        if (gameDir != null) gameDirRef().compareAndSet(null, gameDir.toAbsolutePath().toString());
    }

    public static Path gameDir() {
        String dir = gameDirRef().get();
        return dir != null ? Paths.get(dir) : Paths.get("").toAbsolutePath();
    }

    public static Path settingsFile() {
        return gameDir().resolve("config").resolve("nitea").resolve("nitea.properties");
    }

    /** False when {@code <modId>.enabled=false} is in the settings file. */
    public static boolean modEnabled(String modId) {
        synchronized (lock()) {
            return !"false".equalsIgnoreCase(read(settingsFile()).getProperty(modId + ".enabled", "true").trim());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------------------------------------------------

    /** The player's choice: {@link #GRANTED}, {@link #DENIED} or {@link #UNDECIDED} (never asked). */
    public static String consent() {
        String value = consentRef().get();
        if (value != null) return value;
        synchronized (lock()) {
            value = consentRef().get();
            if (value == null) {
                value = loadConsent();
                consentRef().set(value);
            }
            return value;
        }
    }

    /** Records the player's choice, saves it and tells every mod. */
    public static void setConsent(boolean granted) {
        String value = granted ? GRANTED : DENIED;
        synchronized (lock()) {
            Path file = settingsFile();
            Properties props = read(file);
            props.setProperty("consent", value);
            props.setProperty("consentAt", Long.toString(System.currentTimeMillis()));
            if (granted) {
                // A fresh ID after every opt-in, so installations can't be linked across an opt-out
                String id = props.getProperty("installationId", "");
                if (!id.matches("[0-9a-f-]{36}")) {
                    id = UUID.randomUUID().toString();
                    props.setProperty("installationId", id);
                }
                installationRef().set(id);
            } else {
                props.remove("installationId");
                installationRef().set(null);
            }
            write(file, props);
            consentRef().set(value);
        }
        for (Runnable listener : listeners()) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // One mod's listener must not stop the others
            }
        }
    }

    /** When the player last changed their choice (epoch millis), 0 if never. */
    public static long consentChangedAt() {
        synchronized (lock()) {
            try {
                return Long.parseLong(read(settingsFile()).getProperty("consentAt", "0").trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** Anonymous random ID of this installation, or null unless the player opted in. */
    public static String installationId() {
        return GRANTED.equals(consent()) ? installationRef().get() : null;
    }

    /** Runs {@code listener} (on the thread that changed it) every time the player changes their choice. */
    public static void onConsentChange(Runnable listener) {
        listeners().add(listener);
    }

    /** Set by the in-game screens once they are loaded, so mods know the player will be asked. */
    public static void markPromptAvailable() {
        entry("promptAvailable", AtomicBoolean::new).set(true);
    }

    public static boolean promptAvailable() {
        return entry("promptAvailable", AtomicBoolean::new).get();
    }

    private static String loadConsent() {
        Path file = settingsFile();
        Properties props = read(file);
        String value = props.getProperty("consent", "").trim().toLowerCase(Locale.ROOT);
        boolean changed = false;
        // Files from before consent existed: an explicit enabled=false was a clear opt-out
        String legacy = props.getProperty("enabled");
        if (legacy != null) {
            if (value.isEmpty() && "false".equalsIgnoreCase(legacy.trim())) value = DENIED;
            props.remove("enabled");
            changed = true;
        }
        if (!value.equals(GRANTED) && !value.equals(DENIED)) value = UNDECIDED;
        if (value.equals(GRANTED)) {
            String id = props.getProperty("installationId", "");
            if (!id.matches("[0-9a-f-]{36}")) {
                id = UUID.randomUUID().toString();
                props.setProperty("installationId", id);
                changed = true;
            }
            installationRef().set(id);
        } else if (props.remove("installationId") != null) {
            // Never keep an ID for someone who hasn't opted in
            changed = true;
        }
        if (value.equals(UNDECIDED) && !Files.exists(file)) changed = true;
        if (changed) write(file, props);
        return value;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Mods and attribution
    // ------------------------------------------------------------------------------------------------------------

    /** Registers a mod using Nitea: its packages and, when named, the Java module of its classes. */
    public static void registerMod(String modId, List<String> packages, String module) {
        modPackages().put(modId, Collections.unmodifiableList(new ArrayList<>(packages)));
        if (module != null && !module.isEmpty()) modModules().put(modId, module);
    }

    /** IDs of every mod using Nitea in this game, sorted. */
    public static Set<String> mods() {
        return Collections.unmodifiableSet(new TreeSet<>(modPackages().keySet()));
    }

    /**
     * The mod that caused an exception, or null when it wasn't a mod using Nitea (the game, another mod, or no
     * code of any mod at all). Starting from the root cause, the first frame that is neither the JDK, the game nor
     * the loader is the culprit; errors a mod's code merely passes through are not its own.
     */
    public static String culprit(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable t = throwable; t != null && chain.size() < 10 && seen.add(t); t = t.getCause()) chain.add(t);
        Collections.reverse(chain);
        for (Throwable t : chain) {
            List<String[]> frames = new ArrayList<>();
            for (StackTraceElement e : t.getStackTrace()) frames.add(new String[] {e.getClassName(), e.getMethodName(), moduleName(e)});
            String owner = owner(frames);
            if (owner != null) return owner.isEmpty() ? null : owner;
        }
        return null;
    }

    // "\tat TRANSFORMER/mymod@1.0/com.example.Foo.bar(Foo.java:12)"
    private static final Pattern TEXT_FRAME = Pattern.compile("^\\s+at\\s+(?:(\\S*)/)?([\\w$.]+)\\.([\\w$<>]+)\\(");

    /** Same as {@link #culprit(Throwable)} for a stack trace printed as text (a Minecraft crash report). */
    public static String culprit(String trace) {
        // Split into the thrown exception and its causes, then look at them root cause first
        List<List<String[]>> chain = new ArrayList<>();
        List<String[]> current = null;
        for (String line : trace.split("\\R")) {
            Matcher m = TEXT_FRAME.matcher(line);
            if (m.find()) {
                if (current == null) chain.add(current = new ArrayList<>());
                String module = m.group(1);
                if (module != null) {
                    module = module.substring(module.lastIndexOf('/') + 1);
                    int at = module.indexOf('@');
                    if (at >= 0) module = module.substring(0, at);
                }
                current.add(new String[] {m.group(2), m.group(3), module});
            } else if (!line.trim().startsWith("...")) {
                current = null;
            }
        }
        Collections.reverse(chain);
        for (List<String[]> frames : chain) {
            String owner = owner(frames);
            if (owner != null) return owner.isEmpty() ? null : owner;
        }
        return null;
    }

    // A mod ID, "" when another mod or library caused it, null when only platform code is involved
    private static String owner(List<String[]> frames) {
        for (String[] frame : frames) {
            String className = frame[0];
            String method = frame[1];
            String module = frame[2];

            String byPackage = ownerByPackage(className);
            if (byPackage != null) return byPackage;
            if (module != null) {
                for (Map.Entry<String, String> mod : modModules().entrySet()) {
                    if (mod.getValue().equals(module)) return mod.getKey();
                }
            }

            // Code a mod injected into the game with Mixin
            Matcher mixin = MIXIN_METHOD.matcher(method);
            if (mixin.find()) {
                String modId = mixin.group(1);
                return modPackages().containsKey(modId) ? modId : "";
            }

            if (isPlatform(className, module)) continue;
            return "";
        }
        return null;
    }

    // The longest matching package, so a mod in com.example.addon isn't mistaken for one in com.example
    private static String ownerByPackage(String className) {
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, List<String>> mod : modPackages().entrySet()) {
            for (String pkg : mod.getValue()) {
                if ((className.equals(pkg) || className.startsWith(pkg + ".")) && pkg.length() > bestLength) {
                    best = mod.getKey();
                    bestLength = pkg.length();
                }
            }
        }
        return best;
    }

    private static boolean isPlatform(String className, String module) {
        if (module != null && (PLATFORM_MODULES.contains(module) || module.startsWith("java.") || module.startsWith("jdk."))) return true;
        // The library's public classes (cc.nitea.Nitea...), not mods that happen to live under cc.nitea
        if (className.startsWith("cc.nitea.") && className.indexOf('.', "cc.nitea.".length()) < 0) return true;
        // Obfuscated game classes (Forge before 1.17 at runtime) have no package
        if (className.indexOf('.') < 0) return true;
        for (String pkg : PLATFORM_PACKAGES) {
            if (className.startsWith(pkg)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Method moduleNameMethod() {
        try {
            return StackTraceElement.class.getMethod("getModuleName");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** The named Java module of a class (NeoForge puts mods in named modules), or null. Always null on Java 8. */
    public static String moduleName(Class<?> type) {
        try {
            Object module = Class.class.getMethod("getModule").invoke(type);
            if (!(Boolean) module.getClass().getMethod("isNamed").invoke(module)) return null;
            return (String) module.getClass().getMethod("getName").invoke(module);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String moduleName(StackTraceElement element) {
        if (MODULE_NAME == null) return null;
        try {
            return (String) MODULE_NAME.invoke(element);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static Properties read(Path file) {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException ignored) {
                // Unreadable: treated as defaults
            }
        }
        return props;
    }

    private static void write(Path file, Properties props) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(writer, HEADER);
            }
        } catch (IOException e) {
            System.err.println("[Nitea] Could not write " + file + ": " + e);
        }
    }
}
