package io.github.sorins.goose.debug;

import com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * goose_lldb.py, the LLDB formatters and {@code goose-*} commands for Goose
 * programs. It ships in the plugin's directory as {@code lldb/goose_lldb.py}
 * (beside {@code textmate/}), so LLDB imports it from there; nothing is
 * extracted. Raw lldb-dap loads it through {@code initCommands}; goose-dap
 * loads its own.
 */
public final class GooseLldbScript {
    private GooseLldbScript() {}

    /**
     * The shipped goose_lldb.py, or null when the plugin's directory lacks
     * it. The plugin is the one the platform gave an extension of it (as
     * {@link io.github.sorins.goose.GooseBundleProvider} finds textmate/).
     */
    public static @Nullable Path path(@Nullable PluginDescriptor plugin) {
        if (plugin == null) return null;
        Path script = plugin.getPluginPath().resolve("lldb").resolve("goose_lldb.py");
        return Files.isRegularFile(script) ? script : null;
    }
}
