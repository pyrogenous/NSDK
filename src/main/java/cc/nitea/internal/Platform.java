package cc.nitea.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects the Minecraft version, mod loader, side and environment through each loader's public API, called
 * reflectively so the library compiles against none of them. Anything not found stays null.
 */
public final class Platform {
    public String minecraftVersion;
    public String loaderName;
    public String loaderVersion;
    public String side;
    public String environment;

    public static Platform detect(ClassLoader loader) {
        Platform platform = new Platform();
        if (!platform.fabric(loader)) {
            if (!platform.modList(loader, "net.neoforged.fml.ModList", "neoforge")
                    && platform.modList(loader, "net.minecraftforge.fml.ModList", "forge")
                    && platform.neoForgeLegacy(loader)) {
                // NeoForge for 1.20.1 still uses Forge's packages and mod ID
                platform.loaderName = "neoforge";
            }
            platform.fmlEnvironment(loader, "net.neoforged.fml.loading.FMLEnvironment");
            if (platform.side == null) platform.fmlEnvironment(loader, "net.minecraftforge.fml.loading.FMLEnvironment");
        }
        return platform;
    }

    // Fabric and Quilt: FabricLoader.getInstance()
    private boolean fabric(ClassLoader loader) {
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader", false, loader);
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            Method getModContainer = fabricLoader.getMethod("getModContainer", String.class);
            minecraftVersion = fabricVersion(getModContainer.invoke(instance, "minecraft"));
            String quilt = fabricVersion(getModContainer.invoke(instance, "quilt_loader"));
            loaderName = quilt != null ? "quilt" : "fabric";
            loaderVersion = quilt != null ? quilt : fabricVersion(getModContainer.invoke(instance, "fabricloader"));
            Object envType = fabricLoader.getMethod("getEnvironmentType").invoke(instance);
            side = envType.toString().toLowerCase(Locale.ROOT);
            boolean dev = (boolean) fabricLoader.getMethod("isDevelopmentEnvironment").invoke(instance);
            environment = dev ? "development" : "production";
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String fabricVersion(Object optionalContainer) throws ReflectiveOperationException {
        Optional<?> container = (Optional<?>) optionalContainer;
        if (container.isEmpty()) return null;
        Object metadata = findMethod(container.get().getClass(), "getMetadata").invoke(container.get());
        Object version = findMethod(metadata.getClass(), "getVersion").invoke(metadata);
        return (String) findMethod(version.getClass(), "getFriendlyString").invoke(version);
    }

    // NeoForge and Forge: ModList.get().getModContainerById(id).getModInfo().getVersion()
    private boolean modList(ClassLoader loader, String className, String name) {
        try {
            Class<?> modList = Class.forName(className, false, loader);
            Object instance = modList.getMethod("get").invoke(null);
            if (instance == null) return false;
            Method byId = modList.getMethod("getModContainerById", String.class);
            loaderName = name;
            minecraftVersion = modVersion(byId.invoke(instance, "minecraft"));
            loaderVersion = modVersion(byId.invoke(instance, name));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String modVersion(Object optionalContainer) throws ReflectiveOperationException {
        Optional<?> container = (Optional<?>) optionalContainer;
        if (container.isEmpty()) return null;
        Object info = findMethod(container.get().getClass(), "getModInfo").invoke(container.get());
        Object version = findMethod(info.getClass(), "getVersion").invoke(info);
        return version != null ? version.toString() : null;
    }

    // NeoForge 1.20.1 ships as net.neoforged:forge, which ForgeVersion reports as its Maven group
    private boolean neoForgeLegacy(ClassLoader loader) {
        try {
            Class<?> version = Class.forName("net.minecraftforge.versions.forge.ForgeVersion", false, loader);
            return "net.neoforged".equals(version.getMethod("getGroup").invoke(null));
        } catch (Throwable e) {
            return false;
        }
    }

    // FMLEnvironment: static fields `dist` / `production` on older versions, static getters on newer ones
    private void fmlEnvironment(ClassLoader loader, String className) {
        try {
            Class<?> env = Class.forName(className, false, loader);
            Object dist = staticValue(env, "getDist", "dist");
            if (dist != null) side = dist.toString().contains("CLIENT") ? "client" : "server";
            Object production = staticValue(env, "isProduction", "production");
            if (production instanceof Boolean prod) environment = prod ? "production" : "development";
        } catch (Throwable ignored) {
            // Not this loader
        }
    }

    private static Object staticValue(Class<?> type, String getter, String field) {
        try {
            return type.getMethod(getter).invoke(null);
        } catch (Throwable ignored) {
            // try the field
        }
        try {
            Field f = type.getField(field);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Methods declared on interfaces must be invoked through a public type, not a hidden implementation class
    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
                try {
                    return c.getMethod(name);
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                try {
                    return i.getMethod(name);
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
        }
        return type.getMethod(name);
    }
}
