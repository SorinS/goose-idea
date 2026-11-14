package io.github.sorins.goose.lsp;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem;
import io.github.sorins.goose.GooseBundleReloader;
import io.github.sorins.goose.GooseFiles;
import io.github.sorins.goose.settings.GooseConfigurable;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the compiler's language server, {@code goose lsp}, for the project
 * when a Goose file is opened. One server serves the project, as one serves
 * a VS Code window.
 */
public final class GooseLspIntegrationProvider implements LspIntegrationProvider {
    private static final Key<Boolean> WARNED = Key.create("goose.lsp.noCompilerWarned");

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspClientStarter starter) {
        if (!GooseFiles.isGooseFile(file)) return;
        GooseBundleReloader.ensureLoaded();
        GooseSettings settings = GooseSettings.getInstance(project);
        if (!settings.getState().useLanguageServer) return;
        if (settings.compiler(project) == null) {
            warnNoCompiler(project);
            return;
        }
        starter.ensureClientStarted(new GooseLspClientDescriptor(project));
        GoosePackageRoots.getInstance(project).update();
    }

    @Override
    public @NotNull LspClientWidgetItem createWidgetItem(@NotNull LspClient client, VirtualFile currentFile) {
        return new LspClientWidgetItem(client, currentFile, GooseFiles.ICON, GooseConfigurable.class);
    }

    /** Stops the server and starts it again with the current settings, for the open Goose files. */
    public static void restart(@NotNull Project project) {
        project.putUserData(WARNED, null);
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(GooseLspIntegrationProvider.class);
    }

    private static void warnNoCompiler(Project project) {
        if (Boolean.TRUE.equals(project.getUserData(WARNED))) return;
        project.putUserData(WARNED, true);
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("Goose")
                .createNotification("Goose compiler not found",
                        "The language server needs the goose compiler. Set its path in Settings | Languages &amp; Frameworks | Goose, "
                                + "or put goose on PATH or in ~/.local/bin.",
                        NotificationType.WARNING);
        notification.addAction(NotificationAction.createSimpleExpiring("Configure...",
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GooseConfigurable.class)));
        notification.notify(project);
    }
}
