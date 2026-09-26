package cc.nitea.compat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The public API of Nitea as one line per class, constructor, method and field: what a mod's compiled code can link
 * against. A mod built against a release calls exactly these signatures, so every one of them must still exist, with
 * the same parameter and return types, in every later release. Only {@code cc.nitea} itself is API;
 * {@code cc.nitea.internal} and the loader packages are not.
 */
public final class ApiSurface {
    private static final String PACKAGE = "cc.nitea.";

    private ApiSurface() {}

    /** The API of the classes in a jar (an old release) or a classes directory (this build). */
    public static TreeSet<String> of(ClassLoader loader, List<String> classNames) throws ClassNotFoundException {
        TreeSet<String> lines = new TreeSet<>();
        for (String name : classNames) {
            Class<?> type = Class.forName(name, false, loader);
            if (!isApi(type)) continue;
            lines.add("class " + name + " " + kind(type));
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (visible(c.getModifiers()) && !c.isSynthetic()) lines.add("ctor " + name + "(" + params(c.getParameterTypes()) + ")");
            }
            for (Method m : type.getDeclaredMethods()) {
                if (!visible(m.getModifiers()) || m.isSynthetic() || m.isBridge()) continue;
                lines.add("method " + name + " " + (Modifier.isStatic(m.getModifiers()) ? "static " : "")
                        + m.getReturnType().getTypeName() + " " + m.getName() + "(" + params(m.getParameterTypes()) + ")");
            }
            for (Field f : type.getDeclaredFields()) {
                if (!visible(f.getModifiers()) || f.isSynthetic()) continue;
                lines.add("field " + name + " " + (Modifier.isStatic(f.getModifiers()) ? "static " : "") + f.getType().getTypeName() + " " + f.getName());
            }
        }
        return lines;
    }

    /** Names of every class directly in {@code cc.nitea} (nested ones included) inside a jar. */
    public static List<String> classesInJar(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (entry.matches("cc/nitea/[^/]+\\.class")) names.add(entry.replace('/', '.').replaceAll("\\.class$", ""));
            }
        }
        return names;
    }

    /** Names of every class directly in {@code cc.nitea} compiled into the directory holding {@code anchor}. */
    public static List<String> classesOfBuild(Class<?> anchor) throws Exception {
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
        Path dir = new File(location.toURI()).toPath().resolve("cc").resolve("nitea");
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".class"))
                    .map(n -> PACKAGE + n.substring(0, n.length() - ".class".length()))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static boolean isApi(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || type.isAnonymousClass()) return false;
        // A public class nested in a public class is API too (NiteaOptions.Builder, NiteaConsent.State)
        for (Class<?> outer = type.getDeclaringClass(); outer != null; outer = outer.getDeclaringClass()) {
            if (!Modifier.isPublic(outer.getModifiers())) return false;
        }
        return true;
    }

    private static String kind(Class<?> type) {
        if (type.isEnum()) return "enum";
        if (type.isInterface()) return "interface";
        return "class";
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String params(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getTypeName).collect(Collectors.joining(","));
    }
}
