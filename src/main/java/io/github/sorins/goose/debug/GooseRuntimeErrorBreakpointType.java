package io.github.sorins.goose.debug;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Goose runtime errors": the exception breakpoint type the platform's DAP
 * client requires beside the line type. It has one breakpoint, the default
 * one, and no way to add more. The platform needs it for every stop with
 * {@code reason: exception} (goose-dap's stop on a runtime error, or a crash
 * under lldb-dap): it reports the stop as a hit of this breakpoint, and
 * without one it fails to show the stop at all. It turns the breakpoint on
 * at such a stop.
 * <p>
 * Whether a runtime error stops is the {@code stopOnRuntimeErrors} setting:
 * the platform sends no {@code setExceptionBreakpoints} for this breakpoint,
 * so goose-dap applies its {@code goose_runtime} filter from the launch's
 * {@code stopOnRuntimeError}, and with raw lldb-dap the plugin sets a
 * breakpoint on the runtime's abort function.
 */
@SuppressWarnings("rawtypes")
public final class GooseRuntimeErrorBreakpointType extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {
    public static final String ID = "goose-runtime-error";

    public GooseRuntimeErrorBreakpointType() {
        super(ID, "Goose runtime errors");
    }

    @Override
    public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
        return "Goose runtime errors and crashes";
    }

    @Override
    public @Nullable XBreakpoint<XBreakpointProperties> createDefaultBreakpoint(@NotNull XBreakpointCreator<XBreakpointProperties> creator) {
        // Enabled from the start: the platform enables it at the first exception
        // stop, but reads its state at that same stop, and a disabled one
        // resumes the program instead of showing the stop.
        XBreakpoint<XBreakpointProperties> breakpoint = creator.createBreakpoint(null);
        breakpoint.setEnabled(true);
        return breakpoint;
    }

    @Override
    public boolean isAddBreakpointButtonVisible() {
        return false;
    }
}
