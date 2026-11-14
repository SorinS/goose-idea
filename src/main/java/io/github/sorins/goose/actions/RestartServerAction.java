package io.github.sorins.goose.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import io.github.sorins.goose.lsp.GooseLspIntegrationProvider;
import org.jetbrains.annotations.NotNull;

/** Tools | Goose | Restart Language Server. */
public final class RestartServerAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() != null) GooseLspIntegrationProvider.restart(e.getProject());
    }
}
