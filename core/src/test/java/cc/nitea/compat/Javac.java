package cc.nitea.compat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles test mods the way a mod author's build would, against a given Nitea jar. */
public final class Javac {
    private Javac() {}

    public static void compile(Path sources, List<Path> classpath, Path out) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("The tests need a JDK, not a JRE");
        List<String> files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).collect(Collectors.toList());
        }
        Files.createDirectories(out);
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(out.toString());
        args.add("-cp");
        args.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        args.add("-proc:none");
        args.add("-encoding");
        args.add("UTF-8");
        args.addAll(files);
        // Errors go to stderr, shown in the test report
        int code = compiler.run(null, null, null, args.toArray(new String[0]));
        if (code != 0) throw new AssertionError("Compilation of " + sources + " failed, see the test output");
    }
}
