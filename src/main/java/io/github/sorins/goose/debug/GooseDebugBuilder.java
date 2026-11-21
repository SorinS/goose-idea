package io.github.sorins.goose.debug;

import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.CapturingProcessAdapter;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.util.ExecUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import io.github.sorins.goose.run.GooseConsoleFilter;
import io.github.sorins.goose.run.GooseRunConfiguration;
import io.github.sorins.goose.settings.GooseConfigurable;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Runs Debug's build ({@code goose build -g}) and reports what goes wrong. */
public final class GooseDebugBuilder {
    private GooseDebugBuilder() {}

    /** Whether a compiler has -g, by its path, size and modification time. */
    private static final Map<String, Boolean> DEBUG_INFO = new ConcurrentHashMap<>();

    /** Whether {@code compiler help build} lists {@code --debug-info}. Runs the compiler once per binary. */
    public static boolean supportsDebugInfo(@NotNull Path compiler) {
        String key;
        try {
            key = compiler + "|" + Files.size(compiler) + "|" + Files.getLastModifiedTime(compiler).toMillis();
        } catch (IOException e) {
            key = compiler.toString();
        }
        return DEBUG_INFO.computeIfAbsent(key, k -> {
            try {
                ProcessOutput output = ExecUtil.execAndGetOutput(
                        new GeneralCommandLine(compiler.toString()).withParameters(GooseDebugBuild.helpArguments()), 15_000);
                return GooseDebugBuild.supportsDebugInfo(output.getStdout() + output.getStderr());
            } catch (ExecutionException e) {
                return false;
            }
        });
    }

    /**
     * Builds the configuration's program for debugging, blocking until the
     * compiler exits. Throws when it cannot or the build fails, after telling
     * the user why.
     */
    public static void build(@NotNull Project project, @NotNull GooseRunConfiguration configuration,
                             @NotNull BooleanSupplier cancelled) throws ExecutionException {
        Path compiler = GooseSettings.getInstance(project).compiler(project);
        if (compiler == null) {
            throw fail(project, "Goose compiler not found", "Set it in Settings | Languages &amp; Frameworks | Goose.", true);
        }
        if (!supportsDebugInfo(compiler)) {
            throw fail(project, "This Goose compiler cannot build for debugging",
                    compiler + " has no " + GooseDebugBuild.DEBUG_FLAG + " (goose build " + GooseDebugBuild.DEBUG_INFO_OPTION
                            + "). Debugging needs a compiler with the debugger patches (for instance goose.git's "
                            + "build-debug/goose-debug); set it in Settings | Languages &amp; Frameworks | Goose.", true);
        }
        Path output = configuration.debugExecutable();
        try {
            Files.createDirectories(output.getParent());
        } catch (IOException e) {
            throw new ExecutionException("Cannot create " + output.getParent() + ": " + e.getMessage(), e);
        }
        GeneralCommandLine commandLine = configuration.debugBuildCommandLine();
        OSProcessHandler handler = new OSProcessHandler(commandLine);
        CapturingProcessAdapter capture = new CapturingProcessAdapter();
        handler.addProcessListener(capture);
        handler.startNotify();
        while (!handler.waitFor(200)) {
            if (cancelled.getAsBoolean()) {
                handler.destroyProcess();
                throw new CancellationException("Goose debug build cancelled");
            }
        }
        ProcessOutput result = capture.getOutput();
        if (result.getExitCode() != 0 || !GooseDebugBuild.built(output)) {
            showOutput(project, "Build " + configuration.programFile().getFileName(), commandLine, result);
            String errors = StringUtil.join(result.getStderrLines().stream().limit(8).toList(), "\n");
            if (errors.isBlank()) errors = StringUtil.join(result.getStdoutLines().stream().limit(8).toList(), "\n");
            throw fail(project, "goose build -g failed (exit code " + result.getExitCode() + ")",
                    StringUtil.escapeXmlEntities(errors) + "<br>The compiler's output is in the Run tool window.", false);
        }
    }

    /**
     * Notifies the user and returns the exception to throw: one the platform
     * does not report again (it would say only "Failed to launch the debug
     * adapter").
     */
    static @NotNull ExecutionException fail(@NotNull Project project, @NotNull String title, @NotNull String content,
                                            boolean settingsAction) {
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("Goose")
                .createNotification(title, content, NotificationType.ERROR);
        if (settingsAction) {
            notification.addAction(NotificationAction.createSimpleExpiring("Goose Settings...",
                    () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GooseConfigurable.class)));
        }
        notification.notify(project);
        return new CantRunException.CustomProcessedCantRunException();
    }

    /** The compiler's output in a Run tool window tab, with errors linked to the source. */
    private static void showOutput(@NotNull Project project, @NotNull String title, @NotNull GeneralCommandLine commandLine,
                                   @NotNull ProcessOutput output) {
        // A test project has no Run tool window to hold (and dispose) the console.
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            console.addMessageFilter(new GooseConsoleFilter(project));
            console.print(commandLine.getCommandLineString() + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
            console.print(output.getStdout(), ConsoleViewContentType.NORMAL_OUTPUT);
            console.print(output.getStderr(), ConsoleViewContentType.ERROR_OUTPUT);
            console.print("\nProcess finished with exit code " + output.getExitCode() + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
            RunContentDescriptor descriptor = new RunContentDescriptor(console, null, console.getComponent(), title);
            RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
        }, project.getDisposed());
    }
}
