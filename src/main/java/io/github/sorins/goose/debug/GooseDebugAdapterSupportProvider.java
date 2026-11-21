package io.github.sorins.goose.debug;

import com.intellij.openapi.project.Project;
import com.intellij.platform.dap.DebugAdapterDescriptor;
import com.intellij.platform.dap.DebugAdapterSupportProvider;
import org.jetbrains.annotations.NotNull;

/** Registers the Goose debug adapter with the platform's DAP client. */
public final class GooseDebugAdapterSupportProvider implements DebugAdapterSupportProvider<GooseDebugAdapterId> {
    @Override
    public @NotNull GooseDebugAdapterId getAdapterId() {
        return GooseDebugAdapterId.INSTANCE;
    }

    @Override
    public @NotNull DebugAdapterDescriptor<GooseDebugAdapterId> createDebugAdapterDescriptor(@NotNull Project project) {
        return new GooseDebugAdapterDescriptor(project);
    }
}
