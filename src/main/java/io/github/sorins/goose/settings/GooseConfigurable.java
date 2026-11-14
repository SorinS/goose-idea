package io.github.sorins.goose.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import io.github.sorins.goose.lsp.GooseLspIntegrationProvider;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.file.Path;
import java.util.Objects;

/** Settings | Languages &amp; Frameworks | Goose. */
public final class GooseConfigurable implements Configurable {
    private final Project project;
    private TextFieldWithBrowseButton compilerPath;
    private TextFieldWithBrowseButton stdlibPath;
    private JBTextArea entryFiles;
    private JBCheckBox useLanguageServer;
    private JBTextField runArguments;
    private JBLabel found;

    public GooseConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return "Goose";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compilerPath = new TextFieldWithBrowseButton();
        compilerPath.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Goose Compiler"));
        stdlibPath = new TextFieldWithBrowseButton();
        stdlibPath.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Goose Standard Library"));
        entryFiles = new JBTextArea(3, 40);
        useLanguageServer = new JBCheckBox("Start the language server (goose lsp)");
        runArguments = new JBTextField();
        found = new JBLabel();
        found.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        found.setFontColor(UIUtil.FontColor.BRIGHTER);

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Compiler:", compilerPath)
                .addComponentToRightColumn(comment(
                        "Empty searches goose and build/{Release,Debug}/goose in the project, then PATH, "
                                + "~/.local/bin, /opt/homebrew/bin and /usr/local/bin. "
                                + "A name without a slash is a command on PATH."))
                .addComponentToRightColumn(found)
                .addLabeledComponent("Standard library:", stdlibPath)
                .addComponentToRightColumn(comment(
                        "Passed as --stdlib. Empty uses the compiler's lookup (GOOSE_STDLIB, or stdlib/ near the executable)."))
                .addLabeledComponent("Entry files:", new JBScrollPane(entryFiles))
                .addComponentToRightColumn(comment(
                        "One per line, relative to the project. Modules they import are checked as part of their "
                                + "program. The first is what Goose | Run, Check and Build use instead of the current file."))
                .addLabeledComponent("Program arguments:", runArguments)
                .addComponentToRightColumn(comment("Passed after -- by Run with JIT."))
                .addComponent(useLanguageServer)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private static JComponent comment(String text) {
        JBLabel label = new JBLabel("<html>" + text + "</html>");
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        label.setAllowAutoWrapping(true);
        return label;
    }

    @Override
    public boolean isModified() {
        GooseSettings.State s = GooseSettings.getInstance(project).getState();
        return !Objects.equals(compilerPath.getText().trim(), s.compilerPath)
                || !Objects.equals(stdlibPath.getText().trim(), s.stdlibPath)
                || !Objects.equals(entryFiles.getText().trim(), s.entryFiles)
                || useLanguageServer.isSelected() != s.useLanguageServer
                || !Objects.equals(runArguments.getText().trim(), s.runArguments);
    }

    @Override
    public void apply() {
        GooseSettings.State s = GooseSettings.getInstance(project).getState();
        boolean serverChanged = !Objects.equals(compilerPath.getText().trim(), s.compilerPath)
                || !Objects.equals(stdlibPath.getText().trim(), s.stdlibPath)
                || !Objects.equals(entryFiles.getText().trim(), s.entryFiles)
                || useLanguageServer.isSelected() != s.useLanguageServer;
        s.compilerPath = compilerPath.getText().trim();
        s.stdlibPath = stdlibPath.getText().trim();
        s.entryFiles = entryFiles.getText().trim();
        s.useLanguageServer = useLanguageServer.isSelected();
        s.runArguments = runArguments.getText().trim();
        updateFound();
        if (serverChanged) GooseLspIntegrationProvider.restart(project);
    }

    @Override
    public void reset() {
        GooseSettings.State s = GooseSettings.getInstance(project).getState();
        compilerPath.setText(s.compilerPath);
        stdlibPath.setText(s.stdlibPath);
        entryFiles.setText(s.entryFiles);
        useLanguageServer.setSelected(s.useLanguageServer);
        runArguments.setText(s.runArguments);
        updateFound();
    }

    private void updateFound() {
        Path compiler = GooseSettings.getInstance(project).compiler(project);
        found.setText(compiler == null ? "No Goose compiler found." : "Using " + compiler);
    }

    @Override
    public void disposeUIResources() {
        compilerPath = null;
        stdlibPath = null;
        entryFiles = null;
        useLanguageServer = null;
        runArguments = null;
        found = null;
    }
}
