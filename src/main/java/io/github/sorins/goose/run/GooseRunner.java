package io.github.sorins.goose.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.execution.ParametersListUtil;
import io.github.sorins.goose.GooseCompiler;
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
        FileDocumentManager.getInstance().saveAllDocuments();
        GooseConfigurationType type = GooseConfigurationType.getInstance();
        String name = action == GooseCompiler.Action.RUN ? program.getFileName().toString()
                : action.title + " " + program.getFileName();
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
}
