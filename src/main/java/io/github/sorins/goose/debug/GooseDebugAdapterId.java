package io.github.sorins.goose.debug;

import com.intellij.platform.dap.DebugAdapterId;

/**
 * The Goose debug adapter's id. The platform finds the support provider for
 * a launch by comparing ids with {@code equals}, which {@link DebugAdapterId}
 * does not override, so there is one instance, {@link #INSTANCE}, that the
 * launch-arguments provider and the support provider both give.
 */
public final class GooseDebugAdapterId extends DebugAdapterId {
    public static final GooseDebugAdapterId INSTANCE = new GooseDebugAdapterId();

    private GooseDebugAdapterId() {
        super("goose", "Goose (LLDB)");
    }
}
