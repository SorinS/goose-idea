package io.github.sorins.goose.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import io.github.sorins.goose.debug.GooseDebugAdapter;
import io.github.sorins.goose.lsp.GooseLspIntegrationProvider;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
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
    private ComboBox<GooseDebugAdapter.Kind> debugAdapter;
    private TextFieldWithBrowseButton debugAdapterPath;
    private JBCheckBox stopOnRuntimeErrors;
    private JBLabel foundAdapter;

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
        debugAdapter = new ComboBox<>(GooseDebugAdapter.Kind.values());
        debugAdapter.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends GooseDebugAdapter.Kind> list, GooseDebugAdapter.Kind value,
                                  int index, boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value.title);
            }
        });
        debugAdapterPath = new TextFieldWithBrowseButton();
        debugAdapterPath.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Debug Adapter"));
        stopOnRuntimeErrors = new JBCheckBox("Stop on Goose runtime errors (assert, bounds and overflow checks)");
        foundAdapter = new JBLabel();
        foundAdapter.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        foundAdapter.setFontColor(UIUtil.FontColor.BRIGHTER);

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
                .addSeparator()
                .addLabeledComponent("Debug adapter:", debugAdapter)
                .addLabeledComponent("Adapter path:", debugAdapterPath)
                .addComponentToRightColumn(comment(
                        "Empty searches goose-dap beside the compiler and on PATH, then lldb-dap (the Command Line "
                                + "Tools' on macOS; lldb-dap, lldb-vscode or lldb-dap-NN on PATH on Linux; LLVM's on "
                                + "Windows). Debug builds with goose build -g, which needs a compiler with the debugger patches."))
                .addComponentToRightColumn(foundAdapter)
                .addComponent(stopOnRuntimeErrors)
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
                || !Objects.equals(runArguments.getText().trim(), s.runArguments)
                || selectedKind() != GooseDebugAdapter.Kind.parse(s.debugAdapter)
                || !Objects.equals(debugAdapterPath.getText().trim(), s.debugAdapterPath)
                || stopOnRuntimeErrors.isSelected() != s.stopOnRuntimeErrors;
    }

    private GooseDebugAdapter.Kind selectedKind() {
        return debugAdapter.getSelectedItem() instanceof GooseDebugAdapter.Kind k ? k : GooseDebugAdapter.Kind.AUTO;
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
        s.debugAdapter = selectedKind().name();
        s.debugAdapterPath = debugAdapterPath.getText().trim();
        s.stopOnRuntimeErrors = stopOnRuntimeErrors.isSelected();
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
        debugAdapter.setSelectedItem(GooseDebugAdapter.Kind.parse(s.debugAdapter));
        debugAdapterPath.setText(s.debugAdapterPath);
        stopOnRuntimeErrors.setSelected(s.stopOnRuntimeErrors);
        updateFound();
    }

    private void updateFound() {
        Path compiler = GooseSettings.getInstance(project).compiler(project);
        found.setText(compiler == null ? "No Goose compiler found." : "Using " + compiler);
        GooseDebugAdapter.Found adapter = GooseSettings.getInstance(project).debugAdapter(project);
        foundAdapter.setText(adapter == null ? "No debug adapter found." : "Using " + adapter.kind().title + ": " + adapter.path());
    }

    @Override
    public void disposeUIResources() {
        compilerPath = null;
        stdlibPath = null;
        entryFiles = null;
        useLanguageServer = null;
        runArguments = null;
        found = null;
        debugAdapter = null;
        debugAdapterPath = null;
        stopOnRuntimeErrors = null;
        foundAdapter = null;
    }
}
