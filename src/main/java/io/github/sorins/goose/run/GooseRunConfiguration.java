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
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.debug.GooseDebugBuild;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code goose [check|build] [--stdlib dir] [--jit | -o file.c] file.goose [-- args]}.
 * Debug (a Run with JIT configuration under the Debug executor) builds with
 * {@code goose build -g} and runs the executable under the debug adapter;
 * see {@link io.github.sorins.goose.debug.GooseDebugAdapterDescriptor}.
 */
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

    public @NotNull Path programFile() {
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
        commandLine.withEnvironment(o.getEnvironment());
        commandLine.withWorkDirectory(workingDirectory().toFile());
        return commandLine;
    }

    /** The program's working directory: the configured one, else the project's, else the file's. */
    public @NotNull Path workingDirectory() {
        Path projectDir = GooseSettings.projectDir(getProject());
        GooseRunConfigurationOptions o = getOptions();
        return !o.getWorkingDirectory().isBlank() ? GooseCompiler.absolute(o.getWorkingDirectory(), projectDir)
                : projectDir != null ? projectDir : programFile().getParent();
    }

    public @NotNull List<String> programArguments() {
        return ParametersListUtil.parse(getOptions().getProgramArguments());
    }

    public @NotNull Map<String, String> environment() {
        return getOptions().getEnvironment();
    }

    /** Whether Debug applies: a program run (not Check, Build or Generate C). */
    public boolean isDebuggable() {
        return getOptions().getAction() == GooseCompiler.Action.RUN;
    }

    /** The executable Debug builds and runs, in the IDE's system directory. */
    public @NotNull Path debugExecutable() {
        Path dir = Path.of(PathManager.getSystemPath(), "goose", "debug", getProject().getLocationHash());
        return GooseDebugBuild.outputPath(dir, programFile(), GooseCompiler.isWindows());
    }

    /** {@code goose build -g [--stdlib dir] -o debugExecutable() file}, run in the working directory. */
    public @NotNull GeneralCommandLine debugBuildCommandLine() throws ExecutionException {
        Project project = getProject();
        GooseSettings settings = GooseSettings.getInstance(project);
        Path compiler = settings.compiler(project);
        if (compiler == null) throw new ExecutionException("Goose compiler not found; set it in Settings | Languages & Frameworks | Goose");
        return new GeneralCommandLine(compiler.toString())
                .withParameters(GooseDebugBuild.arguments(settings.stdlib(project), programFile(), debugExecutable()))
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(workingDirectory().toFile());
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        if (DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId())) {
            // The platform's DAP runner executes this state before it starts
            // the adapter. There is nothing to run here: the build happens in
            // the adapter descriptor (off the UI thread), and the adapter
            // launches the program.
            return (exec, runner) -> null;
        }
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
