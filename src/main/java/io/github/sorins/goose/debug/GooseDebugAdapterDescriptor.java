package io.github.sorins.goose.debug;

import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.platform.dap.DapBreakpointsDescription;
import com.intellij.platform.dap.DebugAdapterDescriptor;
import com.intellij.platform.dap.connection.CommandLineDebugAdapterHandle;
import com.intellij.platform.dap.connection.DebugAdapterHandle;
import io.github.sorins.goose.run.GooseRunConfiguration;
import io.github.sorins.goose.settings.GooseSettings;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.Job;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Starts a Goose debug session: builds the program with {@code goose build
 * -g}, then starts the adapter (goose-dap or lldb-dap) over stdio. The
 * platform then sends {@code initialize} and the {@code launch} arguments
 * from {@link GooseDapLaunchArgumentsProvider}, and the adapter runs the
 * executable.
 * <p>
 * The build is here rather than in a before-launch task or the run state:
 * the platform calls {@link #launchDebugAdapter} on an IO thread, before its
 * request timeouts start, and only for Debug, so every Goose configuration
 * (saved, temporary or new) is built fresh for Debug and never for Run. A
 * failed build shows the compiler's output in the Run tool window and ends
 * the session before an adapter starts.
 */
public final class GooseDebugAdapterDescriptor extends DebugAdapterDescriptor<GooseDebugAdapterId> {
    private final Project project;

    public GooseDebugAdapterDescriptor(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull GooseDebugAdapterId getId() {
        return GooseDebugAdapterId.INSTANCE;
    }

    @Override
    public @NotNull DapBreakpointsDescription getBreakpointsDescription() {
        return new DapBreakpointsDescription(GooseLineBreakpointType.class, GooseRuntimeErrorBreakpointType.class);
    }

    /**
     * A Kotlin {@code suspend} function; this override never suspends, it
     * returns the handle (or throws) on the calling IO thread.
     */
    @Override
    public @Nullable Object launchDebugAdapter(@NotNull ExecutionEnvironment environment, @Nullable ExecutionResult executionResult,
                                               @NotNull String sessionId,
                                               @NotNull Continuation<? super DebugAdapterHandle> continuation) throws ExecutionException {
        if (!(environment.getRunProfile() instanceof GooseRunConfiguration configuration)) {
            throw new CantRunException("Not a Goose run configuration: " + environment.getRunProfile().getName());
        }
        GooseDebugAdapter.Found adapter = GooseSettings.getInstance(project).debugAdapter(project);
        if (adapter == null) {
            throw GooseDebugBuilder.fail(project, "No debug adapter found",
                    "Debugging Goose needs lldb-dap (on macOS it comes with the Command Line Tools: xcode-select --install; "
                            + "on Linux with the lldb or llvm packages) or goose-dap. Set its path in "
                            + "Settings | Languages &amp; Frameworks | Goose.", true);
        }
        Job job = continuation.getContext().get(Job.Key);
        GooseDebugBuilder.build(project, configuration, () -> job != null && !job.isActive());
        return new CommandLineDebugAdapterHandle(adapterCommandLine(adapter, configuration));
    }

    /** The adapter's command line: the executable alone, speaking DAP on stdin and stdout. */
    static @NotNull GeneralCommandLine adapterCommandLine(@NotNull GooseDebugAdapter.Found adapter,
                                                          @NotNull GooseRunConfiguration configuration) {
        Path dir = configuration.workingDirectory();
        return new GeneralCommandLine(adapter.path().toString())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(dir.toFile());
    }
}
