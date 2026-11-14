package io.github.sorins.goose.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.GooseFiles;
import io.github.sorins.goose.run.GooseRunner;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Tools | Goose: the VS Code extension's commands. Each acts on the first
 * configured entry file, or else on the current Goose file.
 */
public abstract class GooseAction extends DumbAwareAction {
    private final GooseCompiler.Action action;

    GooseAction(GooseCompiler.Action action) {
        this.action = action;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = project != null
                && (GooseFiles.isGooseFile(file) || GooseSettings.getInstance(project).mainEntryFile(project) != null);
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        GooseRunner.run(project, GooseFiles.isGooseFile(file) ? file : null, action, true);
    }

    public static final class Run extends GooseAction {
        public Run() { super(GooseCompiler.Action.RUN); }
    }

    public static final class Check extends GooseAction {
        public Check() { super(GooseCompiler.Action.CHECK); }
    }

    public static final class Build extends GooseAction {
        public Build() { super(GooseCompiler.Action.BUILD); }
    }

    public static final class GenerateC extends GooseAction {
        public GenerateC() { super(GooseCompiler.Action.GENERATE_C); }
    }
}
