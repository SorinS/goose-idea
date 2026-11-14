package io.github.sorins.goose.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code goose [check|build] [--stdlib dir] [--jit | -o file.c] file.goose [-- args]}. */
public final class GooseRunConfiguration extends LocatableConfigurationBase<GooseRunConfigurationOptions> {
    GooseRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override
    public @NotNull GooseRunConfigurationOptions getOptions() {
        return (GooseRunConfigurationOptions) super.getOptions();
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new GooseRunConfigurationEditor(getProject());
    }

    @Override
    public @Nullable String suggestedName() {
        String file = getOptions().getFile();
        if (file.isBlank()) return null;
        return name(getOptions().getAction(), programFile(), GooseSettings.projectDir(getProject()));
    }

    /**
     * A configuration's name: the file, with its directory in the project
     * when it is not at the top ({@code handshake.goose (examples/tls)}), so
     * that files of the same name in different directories are told apart.
     */
    public static @NotNull String name(@NotNull GooseCompiler.Action action, @NotNull Path file, @Nullable Path projectDir) {
        String name = file.getFileName().toString();
        Path parent = file.getParent();
        if (parent != null && projectDir != null && parent.startsWith(projectDir) && !parent.equals(projectDir)) {
            name += " (" + projectDir.relativize(parent).toString().replace('\\', '/') + ")";
        }
        return action == GooseCompiler.Action.RUN ? name : action.title + " " + name;
    }

    private Path programFile() {
        return GooseCompiler.absolute(getOptions().getFile(), GooseSettings.projectDir(getProject()));
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (getOptions().getFile().isBlank()) throw new RuntimeConfigurationError("Choose the Goose program to run");
        if (!Files.isRegularFile(programFile())) throw new RuntimeConfigurationError("No such file: " + programFile());
        if (GooseSettings.getInstance(getProject()).compiler(getProject()) == null) {
            throw new RuntimeConfigurationError("Goose compiler not found; set it in Settings | Languages & Frameworks | Goose");
        }
    }

    /** The command line this configuration runs. */
    public @NotNull GeneralCommandLine commandLine() throws ExecutionException {
        Project project = getProject();
        GooseSettings settings = GooseSettings.getInstance(project);
        Path compiler = settings.compiler(project);
        if (compiler == null) throw new ExecutionException("Goose compiler not found; set it in Settings | Languages & Frameworks | Goose");
        Path file = programFile();
        GooseRunConfigurationOptions o = getOptions();
        GeneralCommandLine commandLine = new GeneralCommandLine(compiler.toString())
                .withParameters(GooseCompiler.actionArguments(o.getAction(), settings.stdlib(project), file, null,
                        ParametersListUtil.parse(o.getProgramArguments())))
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(StandardCharsets.UTF_8);
        Path projectDir = GooseSettings.projectDir(project);
        Path dir = !o.getWorkingDirectory().isBlank() ? GooseCompiler.absolute(o.getWorkingDirectory(), projectDir)
                : projectDir != null ? projectDir : file.getParent();
        commandLine.withWorkDirectory(dir.toFile());
        return commandLine;
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        CommandLineState state = new CommandLineState(environment) {
            @Override
            protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                KillableColoredProcessHandler handler = new KillableColoredProcessHandler(commandLine());
                ProcessTerminatedListener.attach(handler);
                return handler;
            }
        };
        state.addConsoleFilters(new GooseConsoleFilter(getProject()));
        return state;
    }
}
