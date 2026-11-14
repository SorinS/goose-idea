package io.github.sorins.goose;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Finding the compiler and making its command lines. Plain Java, with no
 * platform dependencies, so that it can be tested on its own. It mirrors
 * the VS Code extension's src/config.js and src/client.js.
 */
public final class GooseCompiler {
    private GooseCompiler() {}

    /** The actions a run configuration can take, as the VS Code extension's tasks. */
    public enum Action {
        RUN("Run with JIT"), CHECK("Check Program"), BUILD("Build Executable"), GENERATE_C("Generate C");

        public final String title;

        Action(String title) { this.title = title; }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static String executableName(boolean windows) {
        return windows ? "goose.exe" : "goose";
    }

    /** Replaces ${workspaceFolder} (VS Code) and $PROJECT_DIR$ (IntelliJ) with the project directory. */
    public static String expand(String value, Path projectDir) {
        if (value == null) return "";
        String dir = projectDir == null ? "" : projectDir.toString();
        return value.replace("${workspaceFolder}", dir).replace("$PROJECT_DIR$", dir);
    }

    /** A configured path, expanded and made absolute against the project directory. */
    public static Path absolute(String value, Path projectDir) {
        Path path = Path.of(expand(value.trim(), projectDir));
        if (!path.isAbsolute() && projectDir != null) path = projectDir.resolve(path);
        return path.normalize();
    }

    /** The default test for a usable compiler: an executable regular file. */
    public static boolean isExecutableFile(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    /**
     * The compiler to use, or null when there is none.
     * <ol>
     * <li>The configured path, when set: one with a separator is a path
     *     (relative ones resolve against the project), one without is a
     *     command looked up on PATH.</li>
     * <li>{@code goose} and {@code build/{Release,Debug,}/goose} in the
     *     project, as the VS Code extension does (a compiler checkout).</li>
     * <li>{@code goose} on PATH.</li>
     * <li>{@code ~/.local/bin/goose}, {@code /opt/homebrew/bin/goose} and
     *     {@code /usr/local/bin/goose}: an IDE started from the Dock or
     *     Finder does not have the shell's PATH.</li>
     * </ol>
     */
    public static Path find(String configured, Path projectDir, String pathEnv, Path home,
                            boolean windows, Predicate<Path> usable) {
        String name = executableName(windows);
        if (configured != null && !configured.isBlank()) {
            String expanded = expand(configured.trim(), projectDir);
            if (expanded.contains("/") || expanded.contains("\\")) {
                Path path = absolute(expanded, projectDir);
                return usable.test(path) ? path : null;
            }
            return onPath(expanded, pathEnv, windows, usable);
        }
        if (projectDir != null) {
            for (String relative : new String[] {name, "build/Release/" + name, "build/Debug/" + name, "build/" + name}) {
                Path candidate = projectDir.resolve(relative);
                if (usable.test(candidate)) return candidate;
            }
        }
        Path found = onPath(name, pathEnv, windows, usable);
        if (found != null) return found;
        List<Path> fallbacks = new ArrayList<>();
        if (home != null) fallbacks.add(home.resolve(".local").resolve("bin").resolve(name));
        if (!windows) {
            fallbacks.add(Path.of("/opt/homebrew/bin", name));
            fallbacks.add(Path.of("/usr/local/bin", name));
        }
        for (Path candidate : fallbacks) {
            if (usable.test(candidate)) return candidate;
        }
        return null;
    }

    /** {@link #find} with this process's PATH and home directory. */
    public static Path find(String configured, Path projectDir) {
        String home = System.getProperty("user.home");
        return find(configured, projectDir, System.getenv("PATH"), home == null ? null : Path.of(home),
                isWindows(), GooseCompiler::isExecutableFile);
    }

    static Path onPath(String command, String pathEnv, boolean windows, Predicate<Path> usable) {
        if (pathEnv == null || pathEnv.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        if (windows && !command.toLowerCase(Locale.ROOT).endsWith(".exe")) names.add(command + ".exe");
        names.add(command);
        for (String dir : pathEnv.split(windows ? ";" : File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            for (String n : names) {
                Path candidate;
                try {
                    candidate = Path.of(dir, n);
                } catch (RuntimeException e) {
                    continue;
                }
                if (usable.test(candidate)) return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    /** The language server's arguments: {@code lsp [--stdlib dir]}. */
    public static List<String> lspArguments(Path stdlib) {
        List<String> args = new ArrayList<>(List.of("lsp"));
        if (stdlib != null) {
            args.add("--stdlib");
            args.add(stdlib.toString());
        }
        return args;
    }

    /** The C file Generate C writes when none is given: the source with .c for .goose. */
    public static Path defaultCOutput(Path file) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".goose") ? name.substring(0, name.length() - ".goose".length()) : name;
        return file.resolveSibling(base + ".c");
    }

    /**
     * The arguments of a compiler action, as the VS Code extension's
     * {@code argumentsFor}: {@code check} and {@code build} are verbs and
     * come first, a run is {@code --jit}, and Generate C is {@code -o out.c}.
     * The program's arguments follow a {@code --}, for a run only.
     */
    public static List<String> actionArguments(Action action, Path stdlib, Path file, Path cOutput,
                                               List<String> programArguments) {
        List<String> args = new ArrayList<>();
        if (action == Action.CHECK) args.add("check");
        if (action == Action.BUILD) args.add("build");
        if (stdlib != null) {
            args.add("--stdlib");
            args.add(stdlib.toString());
        }
        if (action == Action.RUN) args.add("--jit");
        if (action == Action.GENERATE_C) {
            args.add("-o");
            args.add((cOutput != null ? cOutput : defaultCOutput(file)).toString());
        }
        args.add(file.toString());
        if (action == Action.RUN && programArguments != null && !programArguments.isEmpty()) {
            args.add("--");
            args.addAll(programArguments);
        }
        return args;
    }
}
