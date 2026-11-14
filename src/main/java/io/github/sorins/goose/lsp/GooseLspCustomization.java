package io.github.sorins.goose.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport;
import com.intellij.platform.lsp.api.customization.LspCommandsCustomizer;
import com.intellij.platform.lsp.api.customization.LspCommandsSupport;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.run.GooseRunner;
import org.eclipse.lsp4j.Command;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The commands of the server's code lenses that the client runs, as the VS
 * Code extension does: {@code goose.run} (the lens above {@code main}; its
 * argument is the file's URI) and {@code goose.showInstantiations} (the lens
 * above a generic function; its arguments are the name and the lines).
 * Other commands go to the server.
 */
final class GooseLspCustomization extends LspCustomization {
    static final String RUN = "goose.run";
    static final String SHOW_INSTANTIATIONS = "goose.showInstantiations";

    private final GooseLspClientDescriptor descriptor;

    GooseLspCustomization(GooseLspClientDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public @NotNull LspCommandsCustomizer getCommandsCustomizer() {
        return new LspCommandsSupport() {
            @Override
            public void executeCommand(@NotNull LspClient client, @NotNull VirtualFile file, @NotNull Command command) {
                if (!handle(file, command)) super.executeCommand(client, file, command);
            }

        };
    }

    @Override
    public @NotNull LspCodeLensCustomizer getCodeLensCustomizer() {
        return new LspCodeLensSupport() {
            @Override
            public void codeLensClicked(@NotNull LspClient client, @NotNull VirtualFile file, @NotNull Command command,
                                        @NotNull MouseEvent event) {
                if (!handle(file, command)) super.codeLensClicked(client, file, command, event);
            }

        };
    }

    /** Runs the client's own commands; false for the server's. */
    private boolean handle(VirtualFile file, Command command) {
        switch (command.getCommand()) {
            case RUN -> run(file, strings(command.getArguments()));
            case SHOW_INSTANTIATIONS -> showInstantiations(command.getArguments());
            default -> {
                return false;
            }
        }
        return true;
    }

    private void run(VirtualFile contextFile, List<String> args) {
        VirtualFile file = args.isEmpty() ? null : descriptor.findFileByUri(args.get(0));
        VirtualFile target = file != null ? file : contextFile;
        ApplicationManager.getApplication().invokeLater(() ->
                GooseRunner.run(descriptor.getProject(), target, GooseCompiler.Action.RUN, false));
    }

    private void showInstantiations(List<Object> arguments) {
        if (arguments == null || arguments.size() < 2) return;
        String name = string(arguments.get(0));
        List<String> lines = new ArrayList<>();
        Object list = arguments.get(1);
        if (list instanceof JsonArray array) {
            for (JsonElement e : array) lines.add(string(e));
        } else if (list instanceof List<?> l) {
            for (Object o : l) lines.add(string(o));
        }
        if (lines.isEmpty()) return;
        Project project = descriptor.getProject();
        ApplicationManager.getApplication().invokeLater(() -> {
            JBPopup popup = JBPopupFactory.getInstance().createPopupChooserBuilder(lines)
                    .setTitle("Instantiations of " + name)
                    .createPopup();
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) popup.showInBestPositionFor(editor);
            else popup.showCenteredInCurrentWindow(project);
        });
    }

    private static List<String> strings(List<Object> arguments) {
        List<String> result = new ArrayList<>();
        if (arguments != null) for (Object o : arguments) result.add(string(o));
        return result;
    }

    private static String string(Object o) {
        if (o instanceof JsonPrimitive p) return p.getAsString();
        if (o instanceof JsonElement e) return e.toString();
        return String.valueOf(o);
    }
}
