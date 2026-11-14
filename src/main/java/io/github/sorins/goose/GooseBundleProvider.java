package io.github.sorins.goose;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginAware;
import com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Gives the TextMate plugin the Goose bundle: a VS Code-style extension
 * directory (package.json, the grammar, language-configuration.json and the
 * snippets), shipped in the plugin's directory as {@code textmate/goose}.
 */
public final class GooseBundleProvider implements TextMateBundleProvider, PluginAware {
    private static final Logger LOG = Logger.getInstance(GooseBundleProvider.class);

    /** The plugin this provider comes from, given by the platform when it creates the extension. */
    private PluginDescriptor plugin;

    @Override
    public void setPluginDescriptor(@NotNull PluginDescriptor descriptor) {
        plugin = descriptor;
    }

    @Override
    public @NotNull List<PluginBundle> getBundles() {
        if (plugin == null) return List.of();
        Path bundle = plugin.getPluginPath().resolve("textmate").resolve("goose");
        if (!Files.isRegularFile(bundle.resolve("package.json"))) {
            LOG.warn("Goose TextMate bundle not found at " + bundle);
            return List.of();
        }
        return List.of(new PluginBundle("Goose", bundle));
    }
}
