package io.github.sorins.goose.debug;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The executable Debug builds: {@code goose build -g [--stdlib dir] -o out
 * file.goose}. Plain Java, with no platform dependencies, so that it can be
 * tested on its own.
 * <p>
 * A JIT run ({@code goose run}, TinyCC in memory) cannot be debugged, so
 * Debug always builds an executable with {@code -g} ({@code --debug-info}):
 * C debug information, {@code #line} directives that map the C back to the
 * Goose source, and no inlining. Only a compiler with the debugger patches
 * has {@code -g}; {@link #supportsDebugInfo} tells from its help.
 */
public final class GooseDebugBuild {
    private GooseDebugBuild() {}

    /** The flag Debug builds with. */
    public static final String DEBUG_FLAG = "-g";
    /** What {@code goose help build} lists when the compiler has {@link #DEBUG_FLAG}. */
    public static final String DEBUG_INFO_OPTION = "--debug-info";

    /** {@code build -g [--stdlib dir] -o out file}. */
    public static List<String> arguments(Path stdlib, Path file, Path output) {
        List<String> args = new ArrayList<>(List.of("build", DEBUG_FLAG));
        if (stdlib != null) {
            args.add("--stdlib");
            args.add(stdlib.toString());
        }
        args.add("-o");
        args.add(output.toString());
        args.add(file.toString());
        return args;
    }

    /** The arguments that print the build command's options. */
    public static List<String> helpArguments() {
        return List.of("help", "build");
    }

    /** Whether {@code goose help build} printed {@code --debug-info}. */
    public static boolean supportsDebugInfo(String helpOutput) {
        return helpOutput != null && helpOutput.contains(DEBUG_INFO_OPTION);
    }

    /**
     * Where the executable for {@code source} goes: {@code
     * <dir>/<name>-<hash>[.exe]}, where the hash is of the source's path, so
     * that programs of the same name in different directories do not share
     * one. {@code dir} is per project, outside the project's tree (the IDE's
     * system directory), since {@code -o} also writes a {@code .dSYM} or
     * {@code .pdb} beside the executable.
     */
    public static Path outputPath(Path dir, Path source, boolean windows) {
        String name = source.getFileName().toString();
        if (name.endsWith(".goose")) name = name.substring(0, name.length() - ".goose".length());
        String file = name + "-" + shortHash(source.toAbsolutePath().normalize().toString());
        return dir.resolve(windows ? file + ".exe" : file);
    }

    static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** Whether a build left the executable it was asked for. */
    public static boolean built(Path output) {
        return Files.isRegularFile(output);
    }
}
