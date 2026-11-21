package io.github.sorins.goose.debug;

import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.openapi.extensions.PluginAware;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.platform.dap.DapLaunchArgumentsProvider;
import com.intellij.platform.dap.DapStartRequest;
import com.intellij.platform.dap.LaunchRequestArguments;
import io.github.sorins.goose.run.GooseRunConfiguration;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import java.util.Map;

/**
 * Hands Goose run configurations to the platform's DAP runner, for the
 * Debug executor only: the runner also takes Run when a provider applies,
 * as a {@code noDebug} launch, and Run must stay the plugin's own (the JIT).
 */
public final class GooseDapLaunchArgumentsProvider implements DapLaunchArgumentsProvider, PluginAware {
    /** The plugin this provider comes from, given by the platform when it creates the extension. */
    private PluginDescriptor plugin;

    @Override
    public void setPluginDescriptor(@NotNull PluginDescriptor descriptor) {
        plugin = descriptor;
    }

    /** goose_lldb.py in this plugin's directory, or null. */
    public @Nullable Path lldbScript() {
        return GooseLldbScript.path(plugin);
    }

    @Override
    public boolean isApplicable(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)
                && profile instanceof GooseRunConfiguration configuration && configuration.isDebuggable();
    }

    @Override
    public @NotNull LaunchRequestArguments getLaunchArguments(@NotNull Project project, @NotNull RunProfile profile) {
        GooseRunConfiguration configuration = (GooseRunConfiguration) profile;
        return new LaunchRequestArguments(GooseDebugAdapterId.INSTANCE, DapStartRequest.Launch,
                arguments(project, configuration, lldbScript()));
    }

    /** The {@code launch} arguments for the adapter the settings find (lldb-dap's when none is found). */
    public static @NotNull Map<String, Object> arguments(@NotNull Project project, @NotNull GooseRunConfiguration configuration,
                                                         @Nullable Path lldbScript) {
        GooseSettings settings = GooseSettings.getInstance(project);
        GooseDebugAdapter.Found adapter = settings.debugAdapter(project);
        GooseDebugAdapter.Kind kind = adapter == null ? GooseDebugAdapter.Kind.LLDB_DAP : adapter.kind();
        return GooseDebugAdapter.launchArguments(kind, configuration.debugExecutable(), configuration.workingDirectory(),
                configuration.programArguments(), configuration.environment(), lldbScript,
                settings.getState().stopOnRuntimeErrors);
    }
}
