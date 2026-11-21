package io.github.sorins.goose.debug;

import io.github.sorins.goose.GooseCompiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding the debug adapter and making its launch arguments. Plain Java,
 * with no platform dependencies, so that it can be tested on its own.
 * <p>
 * Two adapters are supported. {@code goose-dap} is the Goose proxy in front
 * of lldb-dap: it loads the Goose formatters itself and speaks in Goose
 * names. {@code lldb-dap} (formerly {@code lldb-vscode}) is LLVM's adapter,
 * used as it is: the plugin has it load {@code goose_lldb.py} through
 * {@code initCommands}, and frames, locals and expressions are the generated
 * C's.
 */
public final class GooseDebugAdapter {
    private GooseDebugAdapter() {}

    /** The adapter setting: a kind, or Auto (goose-dap when found, else lldb-dap). */
    public enum Kind {
        AUTO("Auto (goose-dap when found, else lldb-dap)"), GOOSE_DAP("goose-dap"), LLDB_DAP("lldb-dap");

        public final String title;

        Kind(String title) { this.title = title; }

        public static Kind parse(String name) {
            try {
                return name == null || name.isBlank() ? AUTO : valueOf(name);
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    /** The operating system the adapter is looked for on. */
    public enum Os {
        MAC, LINUX, WINDOWS;

        public static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.startsWith("windows")) return WINDOWS;
            if (name.startsWith("mac") || name.startsWith("darwin")) return MAC;
            return LINUX;
        }
    }

    /** An adapter found: what it is ({@code GOOSE_DAP} or {@code LLDB_DAP}) and where. */
    public record Found(Kind kind, Path path) {}

    /** Where Apple's Command Line Tools put lldb-dap. */
    public static final Path MAC_CLT_LLDB_DAP = Path.of("/Library/Developer/CommandLineTools/usr/bin/lldb-dap");

    /** The kind of an adapter executable, from its name. */
    public static Kind kindOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("goose-dap") || name.startsWith("goose_dap") ? Kind.GOOSE_DAP : Kind.LLDB_DAP;
    }

    /**
     * The adapter to use, or null when there is none.
     * <ul>
     * <li>A configured path is used as it is (a name without a separator is
     *     a command on PATH). Under Auto its kind comes from its name.</li>
     * <li>Otherwise goose-dap is looked for (for GOOSE_DAP and AUTO) beside
     *     the compiler, on PATH and in the usual install directories, then
     *     lldb-dap (for LLDB_DAP and AUTO).</li>
     * </ul>
     *
     * @param xcrun what {@code xcrun -f lldb-dap} prints on macOS, or null;
     *              asked only when nothing else is found
     */
    public static Found find(Kind kind, String configured, Path projectDir, Path compiler, String pathEnv, Path home,
                             Os os, Predicate<Path> usable, java.util.function.Supplier<Path> xcrun) {
        boolean windows = os == Os.WINDOWS;
        if (configured != null && !configured.isBlank()) {
            String expanded = GooseCompiler.expand(configured.trim(), projectDir);
            Path path;
            if (expanded.contains("/") || expanded.contains("\\")) {
                path = GooseCompiler.absolute(expanded, projectDir);
                if (!usable.test(path)) return null;
            } else {
                path = onPath(List.of(expanded), pathEnv, windows, usable);
                if (path == null) return null;
            }
            return new Found(kind == Kind.AUTO ? kindOf(path) : kind, path);
        }
        if (kind != Kind.LLDB_DAP) {
            Path gooseDap = findGooseDap(compiler, pathEnv, home, os, usable);
            if (gooseDap != null) return new Found(Kind.GOOSE_DAP, gooseDap);
            if (kind == Kind.GOOSE_DAP) return null;
        }
        Path lldbDap = findLldbDap(pathEnv, os, usable, xcrun);
        return lldbDap == null ? null : new Found(Kind.LLDB_DAP, lldbDap);
    }

    static Path findGooseDap(Path compiler, String pathEnv, Path home, Os os, Predicate<Path> usable) {
        boolean windows = os == Os.WINDOWS;
        String name = windows ? "goose-dap.exe" : "goose-dap";
        List<Path> candidates = new ArrayList<>();
        if (compiler != null && compiler.getParent() != null) candidates.add(compiler.resolveSibling(name));
        for (Path candidate : candidates) {
            if (usable.test(candidate)) return candidate;
        }
        Path found = onPath(List.of("goose-dap"), pathEnv, windows, usable);
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

    private static final Pattern VERSIONED = Pattern.compile("lldb-dap-(\\d+)");

    /**
     * lldb-dap: on macOS the Command Line Tools' and Xcode's, then PATH,
     * Homebrew's LLVM and {@code xcrun -f lldb-dap}; on Linux PATH
     * ({@code lldb-dap}, {@code lldb-vscode}, the newest {@code lldb-dap-NN})
     * and {@code /usr/lib/llvm-NN/bin}; on Windows PATH and the LLVM install.
     */
    static Path findLldbDap(String pathEnv, Os os, Predicate<Path> usable, java.util.function.Supplier<Path> xcrun) {
        boolean windows = os == Os.WINDOWS;
        List<Path> first = new ArrayList<>();
        if (os == Os.MAC) {
            first.add(MAC_CLT_LLDB_DAP);
            first.add(Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin/lldb-dap"));
        }
        for (Path candidate : first) {
            if (usable.test(candidate)) return candidate;
        }
        Path found = onPath(List.of("lldb-dap", "lldb-vscode"), pathEnv, windows, usable);
        if (found != null) return found;
        if (os == Os.LINUX) {
            found = newestVersioned(pathEnv, usable);
            if (found != null) return found;
            found = newestLlvmDir(Path.of("/usr/lib"), usable);
            if (found != null) return found;
        }
        List<Path> fallbacks = new ArrayList<>();
        if (os == Os.MAC) {
            fallbacks.add(Path.of("/opt/homebrew/opt/llvm/bin/lldb-dap"));
            fallbacks.add(Path.of("/usr/local/opt/llvm/bin/lldb-dap"));
        }
        if (windows) {
            fallbacks.add(Path.of("C:\\Program Files\\LLVM\\bin\\lldb-dap.exe"));
            fallbacks.add(Path.of("C:\\Program Files (x86)\\LLVM\\bin\\lldb-dap.exe"));
        }
        for (Path candidate : fallbacks) {
            if (usable.test(candidate)) return candidate;
        }
        if (os == Os.MAC && xcrun != null) {
            Path path = xcrun.get();
            if (path != null && usable.test(path)) return path;
        }
        return null;
    }

    /** The newest {@code lldb-dap-NN} on PATH (Debian and Ubuntu's LLVM packages). */
    static Path newestVersioned(String pathEnv, Predicate<Path> usable) {
        if (pathEnv == null || pathEnv.isEmpty()) return null;
        Path best = null;
        int bestVersion = -1;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            String[] names = new File(dir).list();
            if (names == null) continue;
            for (String n : names) {
                Matcher m = VERSIONED.matcher(n);
                if (!m.matches()) continue;
                int version = Integer.parseInt(m.group(1));
                Path candidate = Path.of(dir, n);
                if (version > bestVersion && usable.test(candidate)) {
                    best = candidate;
                    bestVersion = version;
                }
            }
        }
        return best;
    }

    /** The newest {@code /usr/lib/llvm-NN/bin/lldb-dap}. */
    static Path newestLlvmDir(Path lib, Predicate<Path> usable) {
        String[] names = lib.toFile().list();
        if (names == null) return null;
        Path best = null;
        int bestVersion = -1;
        for (String n : names) {
            if (!n.matches("llvm-\\d+")) continue;
            int version = Integer.parseInt(n.substring("llvm-".length()));
            Path candidate = lib.resolve(n).resolve("bin").resolve("lldb-dap");
            if (version > bestVersion && usable.test(candidate)) {
                best = candidate;
                bestVersion = version;
            }
        }
        return best;
    }

    static Path onPath(List<String> commands, String pathEnv, boolean windows, Predicate<Path> usable) {
        if (pathEnv == null || pathEnv.isEmpty()) return null;
        for (String dir : pathEnv.split(windows ? ";" : File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            for (String command : commands) {
                List<String> names = new ArrayList<>();
                if (windows && !command.toLowerCase(Locale.ROOT).endsWith(".exe")) names.add(command + ".exe");
                names.add(command);
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
        }
        return null;
    }

    /** The default test for a usable adapter: an executable regular file. */
    public static boolean isExecutableFile(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    /** The goose runtime's abort function, where every Goose runtime error ends. */
    public static final String RUNTIME_ERROR_FUNCTION = "gs_abort";

    /**
     * The arguments of the DAP {@code launch} request.
     * <ul>
     * <li>{@code program}, {@code args}, {@code cwd} and, when there are
     *     variables, {@code env} as {@code NAME=value} strings (the form every
     *     lldb-dap version takes), and {@code stopOnEntry: false};</li>
     * <li>for lldb-dap: {@code initCommands} that import goose_lldb.py, and,
     *     when stopping on runtime errors, {@code preRunCommands} that set a
     *     breakpoint on {@link #RUNTIME_ERROR_FUNCTION} (a failed assert or
     *     bounds check otherwise just exits);</li>
     * <li>for goose-dap, which loads the formatters itself and stops on
     *     runtime errors through its {@code goose_runtime} exception filter,
     *     no {@code initCommands}, and {@code stopOnRuntimeError}, which it
     *     applies when the client sends no exception breakpoints.</li>
     * </ul>
     */
    public static Map<String, Object> launchArguments(Kind kind, Path program, Path cwd, List<String> args,
                                                      Map<String, String> env, Path lldbScript,
                                                      boolean stopOnRuntimeErrors) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("program", program.toString());
        arguments.put("args", new ArrayList<>(args == null ? List.of() : args));
        if (cwd != null) arguments.put("cwd", cwd.toString());
        if (env != null && !env.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            env.forEach((k, v) -> pairs.add(k + "=" + (v == null ? "" : v)));
            arguments.put("env", pairs);
        }
        arguments.put("stopOnEntry", false);
        if (kind == Kind.GOOSE_DAP) {
            arguments.put("stopOnRuntimeError", stopOnRuntimeErrors);
        } else {
            if (lldbScript != null) arguments.put("initCommands", List.of(importCommand(lldbScript)));
            if (stopOnRuntimeErrors) {
                arguments.put("preRunCommands", List.of("breakpoint set --name " + RUNTIME_ERROR_FUNCTION));
            }
        }
        return arguments;
    }

    /** {@code command script import "path"}, quoted for LLDB's command line. */
    public static String importCommand(Path script) {
        String quoted = script.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return "command script import \"" + quoted + "\"";
    }
}
