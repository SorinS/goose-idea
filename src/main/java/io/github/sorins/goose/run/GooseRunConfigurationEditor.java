package io.github.sorins.goose.run;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import io.github.sorins.goose.GooseCompiler;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JList;

final class GooseRunConfigurationEditor extends SettingsEditor<GooseRunConfiguration> {
    private final ComboBox<GooseCompiler.Action> action = new ComboBox<>(GooseCompiler.Action.values());
    private final TextFieldWithBrowseButton file = new TextFieldWithBrowseButton();
    private final JBTextField programArguments = new JBTextField();
    private final TextFieldWithBrowseButton workingDirectory = new TextFieldWithBrowseButton();
    private final JComponent panel;

    GooseRunConfigurationEditor(Project project) {
        action.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends GooseCompiler.Action> list, GooseCompiler.Action value,
                                  int index, boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value.title);
            }
        });
        file.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("goose").withTitle("Goose Program"));
        workingDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"));
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Action:", action)
                .addLabeledComponent("Program file:", file)
                .addLabeledComponent("Program arguments:", programArguments)
                .addTooltip("Passed after -- when running with the JIT.")
                .addLabeledComponent("Working directory:", workingDirectory)
                .addTooltip("Empty uses the project directory.")
                .getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull GooseRunConfiguration configuration) {
        GooseRunConfigurationOptions o = configuration.getOptions();
        action.setSelectedItem(o.getAction());
        file.setText(o.getFile());
        programArguments.setText(o.getProgramArguments());
        workingDirectory.setText(o.getWorkingDirectory());
    }

    @Override
    protected void applyEditorTo(@NotNull GooseRunConfiguration configuration) {
        GooseRunConfigurationOptions o = configuration.getOptions();
        Object selected = action.getSelectedItem();
        o.setAction(selected instanceof GooseCompiler.Action a ? a : GooseCompiler.Action.RUN);
        o.setFile(file.getText().trim());
        o.setProgramArguments(programArguments.getText().trim());
        o.setWorkingDirectory(workingDirectory.getText().trim());
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}
