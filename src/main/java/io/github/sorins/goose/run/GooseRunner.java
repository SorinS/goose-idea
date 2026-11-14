package io.github.sorins.goose.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.lsp.GoosePackageRoots;
import io.github.sorins.goose.settings.GooseConfigurable;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/** Runs a compiler action on a program through a temporary run configuration. */
public final class GooseRunner {
    private GooseRunner() {}

    /**
     * The program an action on {@code file} acts on: the first configured
     * entry file when {@code useEntryFile}, as goose.entryFile does in VS Code,
     * else the file.
     */
    public static @Nullable Path program(@NotNull Project project, @Nullable VirtualFile file, boolean useEntryFile) {
        if (useEntryFile) {
            Path entry = GooseSettings.getInstance(project).mainEntryFile(project);
            if (entry != null) return entry;
        }
        return file == null || !file.isInLocalFileSystem() ? null : file.toNioPath();
    }

    public static void run(@NotNull Project project, @Nullable VirtualFile file, @NotNull GooseCompiler.Action action, boolean useEntryFile) {
        Path program = program(project, file, useEntryFile);
        if (program == null) return;
        String name = GooseRunConfiguration.name(action, program, GooseSettings.projectDir(project));
        // A module of the project's package is no program: its imports
        // resolve only within the package. Check checks it through the
        // plugin's program for it; the others have nothing to run.
        if (file != null && file.isInLocalFileSystem() && program.equals(file.toNioPath())) {
            GoosePackageRoots roots = GoosePackageRoots.getInstance(project);
            String module = roots.moduleOf(file);
            if (module != null) {
                if (action != GooseCompiler.Action.CHECK) {
                    notModule(project, file.getName(), module, action);
                    return;
                }
                Path root = roots.rootFor(file);
                if (root == null) return;
                program = root;
            }
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        GooseConfigurationType type = GooseConfigurationType.getInstance();
        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(name, type.factory());
        GooseRunConfiguration configuration = (GooseRunConfiguration) settings.getConfiguration();
        GooseRunConfigurationOptions o = configuration.getOptions();
        o.setAction(action);
        o.setFile(program.toString());
        o.setProgramArguments(ParametersListUtil.join(GooseSettings.getInstance(project).runArguments()));
        settings.setTemporary(true);
        runManager.setTemporaryConfiguration(settings);
        ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
    }

    private static void notModule(Project project, String fileName, String module, GooseCompiler.Action action) {
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("Goose")
                .createNotification(action.title + ": " + fileName + " is not a program",
                        fileName + " is the module " + module + " of the package, with no fn main. "
                                + "Check Program checks it as part of the package; to run code, run one of the "
                                + "package's tests or examples, or set an entry file.",
                        NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimpleExpiring("Set Entry File...",
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GooseConfigurable.class)));
        notification.notify(project);
    }
}
