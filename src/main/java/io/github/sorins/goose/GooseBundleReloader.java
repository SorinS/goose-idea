package io.github.sorins.goose;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The TextMate plugin reads the bundle providers' bundles when it starts,
 * and not again when a plugin installed without a restart adds one: the
 * Goose grammar would then be missing until the IDE restarts. So when this
 * plugin is loaded, and as a fallback when a Goose file is opened while
 * TextMate does not know {@code .goose}, it has TextMate reload its bundles.
 */
public final class GooseBundleReloader implements DynamicPluginListener {
    public static final String PLUGIN_ID = "io.github.sorins.goose";
    private static final AtomicBoolean RELOADED = new AtomicBoolean();

    @Override
    public void pluginLoaded(@NotNull IdeaPluginDescriptor plugin) {
        if (PLUGIN_ID.equals(plugin.getPluginId().getIdString())) reload();
    }

    /** Reloads TextMate's bundles if it does not know Goose files, once. */
    public static void ensureLoaded() {
        if (RELOADED.get()) return;
        if (TextMateService.getInstance().getLanguageDescriptorByExtension(GooseFiles.EXTENSION) != null) return;
        reload();
    }

    private static void reload() {
        if (!RELOADED.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> TextMateService.getInstance().reloadEnabledBundles());
    }
}
