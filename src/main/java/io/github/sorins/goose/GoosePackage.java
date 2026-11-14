package io.github.sorins.goose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A project that is a Goose package (docs/packages.md §4): a directory with
 * an entry file {@code name.goose} declaring {@code namespace name;}. Inside
 * it, {@code import crypto.bignum;} finds {@code crypto/bignum.goose} under
 * the package's root, but only when the module is compiled as part of the
 * package. The language server analyzes an open file no program reaches as
 * a program of its own, rooted at the file's directory, where those imports
 * do not resolve. So for a module of the package, the plugin gives the
 * server a program of its own making ({@link #rootSource}) that declares the
 * package and imports the module.
 */
public final class GoosePackage {
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^\\s*namespace\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static final Pattern MAIN = Pattern.compile("(?m)^\\s*(?:pub\\s+)?fn\\s+main\\s*\\(");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The package's name, as its entry file and namespace say. */
    public final String name;
    /** The package's root directory. */
    public final Path dir;

    private GoosePackage(String name, Path dir) {
        this.name = name;
        this.dir = dir;
    }

    /**
     * The package rooted at {@code dir}, or null if it is not one. A
     * directory can hold several {@code x.goose} files declaring
     * {@code namespace x;} (gslib's {@code mail.goose} beside
     * {@code gslib.goose}); the entry is then the one with a {@code .build}
     * sidecar, else the one named as the directory (without an extension
     * such as {@code .git}), else the only one.
     */
    public static @Nullable GoosePackage at(@Nullable Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String file = f.getFileName().toString();
                if (!GooseFiles.isGooseFileName(file) || !Files.isRegularFile(f)) continue;
                String stem = file.substring(0, file.length() - GooseFiles.EXTENSION.length() - 1);
                if (stem.equals(namespaceOf(f))) names.add(stem);
            }
        } catch (IOException e) {
            return null;
        }
        if (names.isEmpty()) return null;
        names.sort(null);
        for (String n : names) if (Files.isRegularFile(dir.resolve(n + ".build"))) return new GoosePackage(n, dir);
        String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
        int dot = dirName.indexOf('.');
        String base = dot > 0 ? dirName.substring(0, dot) : dirName;
        for (String n : names) if (n.equals(base)) return new GoosePackage(n, dir);
        return names.size() == 1 ? new GoosePackage(names.get(0), dir) : null;
    }

    /**
     * The module {@code file} is in this package ({@code name.crypto.bignum}
     * for {@code crypto/bignum.goose}), or null: a file outside the package,
     * one whose path is not made of names, or a program (a file with
     * {@code fn main}, such as the package's tests and examples, which are
     * roots of their own).
     */
    public @Nullable String moduleOf(@NotNull Path file, @NotNull CharSequence text) {
        Path rel;
        try {
            rel = dir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (rel.getNameCount() == 0 || rel.startsWith("..")) return null;
        String last = rel.getFileName().toString();
        if (!GooseFiles.isGooseFileName(last)) return null;
        if (MAIN.matcher(text).find()) return null;
        StringBuilder module = new StringBuilder(name);
        for (int i = 0; i < rel.getNameCount(); i++) {
            String part = rel.getName(i).toString();
            if (i == rel.getNameCount() - 1) part = part.substring(0, part.length() - GooseFiles.EXTENSION.length() - 1);
            if (!IDENTIFIER.matcher(part).matches()) return null;
            module.append('.').append(part);
        }
        // The entry file is the package itself.
        if (rel.getNameCount() == 1 && module.toString().equals(name + "." + name)) return name;
        return module.toString();
    }

    /** A program that declares this package and imports {@code module} of it. */
    public @NotNull String rootSource(@NotNull String module) {
        String path = dir.toAbsolutePath().normalize().toString().replace('\\', '/').replace("\"", "\\\"");
        StringBuilder s = new StringBuilder()
                .append("// Made by the Goose plugin, so that the language server checks\n")
                .append("// ").append(module).append(" as part of its package.\n")
                .append("import ").append(name).append(" from \"").append(path).append("\";\n");
        if (!module.equals(name)) s.append("import ").append(module).append(";\n");
        return s.toString();
    }

    private static @Nullable String namespaceOf(Path file) {
        try {
            Matcher m = NAMESPACE.matcher(Files.readString(file));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
