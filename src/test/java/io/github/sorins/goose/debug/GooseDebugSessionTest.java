package io.github.sorins.goose.debug;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.frame.XStackFrame;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.run.GooseConfigurationType;
import io.github.sorins.goose.run.GooseRunConfiguration;
import io.github.sorins.goose.settings.GooseSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * A whole Debug through the platform, headless: the DAP runner, the
 * descriptor's {@code goose build -g}, lldb-dap with goose_lldb.py, a line
 * breakpoint, and a stop on a Goose runtime error. Skipped unless a compiler
 * with {@code -g} ({@code -PgooseDebugCompiler=...}, else the compiler the
 * settings find) and lldb-dap ({@code -PlldbDap=...}, else the usual places)
 * are present. The goose-dap tests also need {@code -PgooseDap=path}.
 */
public class GooseDebugSessionTest extends BasePlatformTestCase {
    private static final long TIMEOUT_MS = 90_000;
    private Path work;
    private Path compiler;
    private GooseDebugAdapter.Found adapter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        String configured = System.getProperty("goose.debugCompiler", "");
        compiler = GooseCompiler.find(configured, null);
        if (compiler != null && !GooseDebugBuilder.supportsDebugInfo(compiler)) compiler = null;
        GooseSettings.State state = GooseSettings.getInstance(getProject()).getState();
        state.debugAdapter = GooseDebugAdapter.Kind.LLDB_DAP.name();
        state.debugAdapterPath = System.getProperty("goose.lldbDap", "");
        adapter = GooseSettings.getInstance(getProject()).debugAdapter(getProject());
        if (compiler != null) state.compilerPath = compiler.toString();
        // Under build/, where the Gradle test runs, rather than the system temp directory.
        Path base = Path.of(System.getProperty("user.dir"), "build", "debug-test");
        Files.createDirectories(base);
        work = Files.createTempDirectory(base, "session");
        for (String name : new String[] {"area.goose", "assert.goose", "geo.goose", "structs.goose", "broken.goose"}) {
            try (InputStream in = getClass().getResourceAsStream("/debug/" + name)) {
                Files.copy(in, work.resolve(name));
            }
        }
        // As a project's files would be: known to the VFS, so frames in them have positions.
        LocalFileSystem.getInstance().refreshNioFiles(java.util.List.of(work), false, true, null);
        for (String name : new String[] {"area.goose", "assert.goose", "geo.goose", "structs.goose", "broken.goose"}) {
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(work.resolve(name)));
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            for (XDebugSession session : XDebuggerManager.getInstance(getProject()).getDebugSessions()) session.stop();
            // Ended sessions can stay listed (their tab is not closed in a test); wait until each has stopped.
            waitFor("the sessions to end", () -> java.util.Arrays.stream(XDebuggerManager.getInstance(getProject()).getDebugSessions())
                    .allMatch(XDebugSession::isStopped));
            // Stops open their file in an editor, and an exception stop one more
            // that no file editor owns; release those on the test's files.
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(getProject()).closeAllFiles();
            var editors = com.intellij.openapi.editor.EditorFactory.getInstance();
            for (var editor : editors.getAllEditors()) {
                VirtualFile file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
                if (file != null && file.getPath().startsWith(work.toString()) && !editor.isDisposed()) editors.releaseEditor(editor);
            }
            WriteAction.runAndWait(() -> {
                var manager = XDebuggerManager.getInstance(getProject()).getBreakpointManager();
                for (var b : manager.getAllBreakpoints()) {
                    if (b.getType() instanceof GooseLineBreakpointType) manager.removeBreakpoint(b);
                }
            });
            GooseSettings.State state = GooseSettings.getInstance(getProject()).getState();
            state.compilerPath = "";
            state.debugAdapter = GooseDebugAdapter.Kind.AUTO.name();
            state.debugAdapterPath = "";
            deleteTree(work);
        } finally {
            super.tearDown();
        }
    }

    private boolean skipped() {
        if (compiler == null || adapter == null) {
            System.err.println(getClass().getSimpleName() + " skipped: " + (compiler == null
                    ? "no goose compiler with -g (set -PgooseDebugCompiler=path)" : "no lldb-dap (set -PlldbDap=path)"));
            return true;
        }
        return false;
    }

    /** Switches the settings to goose-dap ({@code -PgooseDap=path}); false when it is not given. */
    private boolean useGooseDap() {
        String path = System.getProperty("goose.gooseDap", "");
        if (compiler == null || path.isBlank() || !Files.isExecutable(Path.of(path))) {
            System.err.println(getClass().getSimpleName() + " goose-dap test skipped: "
                    + (compiler == null ? "no goose compiler with -g" : "no goose-dap (set -PgooseDap=path)"));
            return false;
        }
        GooseSettings.State state = GooseSettings.getInstance(getProject()).getState();
        state.debugAdapter = GooseDebugAdapter.Kind.AUTO.name();
        state.debugAdapterPath = path;
        assertEquals(GooseDebugAdapter.Kind.GOOSE_DAP, GooseSettings.getInstance(getProject()).debugAdapter(getProject()).kind());
        return true;
    }

    /** Through goose-dap: Goose names in the call stack and in expressions. */
    public void testGooseDapShowsGooseNames() throws Exception {
        if (!useGooseDap()) return;
        VirtualFile area = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(work.resolve("area.goose"));
        assertNotNull(area);
        WriteAction.runAndWait(() -> XDebuggerManager.getInstance(getProject()).getBreakpointManager().addLineBreakpoint(
                XDebuggerUtil.getInstance().findBreakpointType(GooseLineBreakpointType.class), area.getUrl(), 8, null));
        XDebugSession session = debug(work.resolve("area.goose"));
        waitFor("a stop at the breakpoint", () -> session.isSuspended() && session.getCurrentPosition() != null);
        assertEquals("area.goose", session.getCurrentPosition().getFile().getName());
        assertEquals(8, session.getCurrentPosition().getLine());
        String top = describe(session.getCurrentStackFrame());
        assertTrue(top, top.startsWith("main") && !top.contains("main_g"));
        assertEquals("0", evaluate(session, "total"));
        assertEquals("1", evaluate(session, "total + i + 1"));
        session.stop();
        waitFor("the session to end", session::isStopped);
    }

    /**
     * Through goose-dap, a failed assert stops at the Goose line that failed.
     * The platform requires an exception breakpoint type; this checks that
     * goose-dap's runtime-error filter is still on with it.
     */
    public void testGooseDapStopsOnAGooseRuntimeError() throws Exception {
        if (!useGooseDap()) return;
        XDebugSession session = debug(work.resolve("assert.goose"));
        waitFor("a stop on the runtime error", () -> session.isSuspended() && session.getCurrentPosition() != null);
        assertEquals("geo.goose", session.getCurrentPosition().getFile().getName());
        assertEquals(5 - 1, session.getCurrentPosition().getLine());
        String top = describe(session.getCurrentStackFrame());
        assertTrue(top, top.contains("geo::area"));
        session.stop();
        waitFor("the session to end", session::isStopped);
    }

    public void testStopsAtALineBreakpoint() throws Exception {
        if (skipped()) return;
        VirtualFile area = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(work.resolve("area.goose"));
        assertNotNull(area);
        // Line 9 (index 8): total = total + area(i, 2).
        XLineBreakpoint<?> breakpoint = WriteAction.computeAndWait(() -> XDebuggerManager.getInstance(getProject())
                .getBreakpointManager().addLineBreakpoint(
                        XDebuggerUtil.getInstance().findBreakpointType(GooseLineBreakpointType.class), area.getUrl(), 8, null));
        assertNotNull(breakpoint);

        XDebugSession session = debug(work.resolve("area.goose"));
        waitFor("a stop at the breakpoint", () -> session.isSuspended() && session.getCurrentPosition() != null);
        XSourcePosition position = session.getCurrentPosition();
        assertEquals("area.goose", position.getFile().getName());
        assertEquals(8, position.getLine());
        XStackFrame frame = session.getCurrentStackFrame();
        assertNotNull(frame);
        assertTrue("breakpoint verified by the adapter", breakpoint.isEnabled());
        // Expressions are the generated C's, with its names.
        assertEquals("0", evaluate(session, "i_g"));
        assertEquals("1", evaluate(session, "total_g + 1"));

        session.resume();
        waitFor("the second stop", () -> session.isSuspended() && session.getCurrentPosition() != null);
        assertEquals(8, session.getCurrentPosition().getLine());
        assertEquals("1", evaluate(session, "i_g"));
        session.stop();
        waitFor("the session to end", session::isStopped);
    }

    /** Values read as Goose values: goose_lldb.py, shipped in the plugin, was imported through initCommands. */
    public void testGooseFormattersAreLoaded() throws Exception {
        if (skipped()) return;
        VirtualFile structs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(work.resolve("structs.goose"));
        assertNotNull(structs);
        // Line 24 (index 23): the print after every value is set.
        WriteAction.runAndWait(() -> XDebuggerManager.getInstance(getProject()).getBreakpointManager().addLineBreakpoint(
                XDebuggerUtil.getInstance().findBreakpointType(GooseLineBreakpointType.class), structs.getUrl(), 23, null));
        XDebugSession session = debug(work.resolve("structs.goose"));
        waitFor("a stop at the breakpoint", () -> session.isSuspended() && session.getCurrentPosition() != null);
        assertEquals(23, session.getCurrentPosition().getLine());
        assertEquals("Point { x: 3, y: -4 }", evaluate(session, "p_g"));
        assertEquals("Color.Green", evaluate(session, "c_g"));
        assertEquals("Shape.Circle { r: 1.5 }", evaluate(session, "circle_g"));
        session.stop();
        waitFor("the session to end", session::isStopped);
    }

    /**
     * A compile error ends the session before an adapter starts, with a
     * notification that quotes the compiler. (The compiler's output also
     * gets a Run tool window tab, which a light test project does not have.)
     */
    public void testFailedBuildEndsTheSession() throws Exception {
        if (skipped()) return;
        java.util.List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(com.intellij.notification.Notifications.TOPIC,
                new com.intellij.notification.Notifications() {
                    @Override
                    public void notify(@org.jetbrains.annotations.NotNull com.intellij.notification.Notification n) {
                        notes.add(n.getTitle() + " | " + n.getContent());
                    }
                });
        XDebugSession session = debug(work.resolve("broken.goose"));
        waitFor("the session to end", session::isStopped);
        waitFor("the notification", () -> !notes.isEmpty());
        assertTrue(notes.toString(), notes.get(0).startsWith("goose build -g failed"));
        assertTrue(notes.toString(), notes.get(0).contains("broken.goose:2"));
    }

    public void testStopsOnAGooseRuntimeError() throws Exception {
        if (skipped()) return;
        assertTrue(GooseSettings.getInstance(getProject()).getState().stopOnRuntimeErrors);
        XDebugSession session = debug(work.resolve("assert.goose"));
        waitFor("a stop in the runtime's abort", () -> session.isSuspended() && session.getSuspendContext() != null
                && session.getSuspendContext().getActiveExecutionStack() != null);
        // The stop is in gs_abort; the frames below it are the Goose ones that failed.
        StringBuilder frames = new StringBuilder();
        var stack = session.getSuspendContext().getActiveExecutionStack();
        XStackFrame top = stack.getTopFrame();
        assertNotNull(top);
        frames.append(describe(top));
        assertTrue(frames.toString(), frames.toString().contains("gs_abort"));
        session.stop();
        waitFor("the session to end", session::isStopped);
    }

    /** An expression's value, as the Evaluate dialog shows it, or "error: message". */
    private static String evaluate(XDebugSession session, String expression) {
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        session.getCurrentStackFrame().getEvaluator().evaluate(expression, new com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@org.jetbrains.annotations.NotNull com.intellij.xdebugger.frame.XValue value) {
                value.computePresentation(new com.intellij.xdebugger.frame.XValueNode() {
                    @Override
                    public void setPresentation(javax.swing.Icon icon, String type, @org.jetbrains.annotations.NotNull String text, boolean hasChildren) {
                        result.complete(text);
                    }

                    @Override
                    public void setPresentation(javax.swing.Icon icon, @org.jetbrains.annotations.NotNull com.intellij.xdebugger.frame.presentation.XValuePresentation presentation, boolean hasChildren) {
                        result.complete(com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil.computeValueText(presentation));
                    }

                    @Override
                    public void setFullValueEvaluator(@org.jetbrains.annotations.NotNull com.intellij.xdebugger.frame.XFullValueEvaluator evaluator) {
                    }
                }, com.intellij.xdebugger.frame.XValuePlace.TREE);
            }

            @Override
            public void errorOccurred(@org.jetbrains.annotations.NotNull String message) {
                result.complete("error: " + message);
            }
        }, null);
        waitFor("evaluating " + expression, result::isDone);
        return result.join();
    }

    private static String describe(XStackFrame frame) {
        var component = new com.intellij.ui.SimpleColoredComponent();
        frame.customizePresentation(component);
        return component.getCharSequence(false).toString();
    }

    private XDebugSession debug(Path file) throws Exception {
        RunnerAndConfigurationSettings settings = RunManager.getInstance(getProject())
                .createConfiguration(file.getFileName().toString(), GooseConfigurationType.getInstance().factory());
        GooseRunConfiguration configuration = (GooseRunConfiguration) settings.getConfiguration();
        configuration.getOptions().setAction(GooseCompiler.Action.RUN);
        configuration.getOptions().setFile(file.toString());
        configuration.getOptions().setWorkingDirectory(work.toString());
        ExecutionEnvironment environment = ExecutionEnvironmentBuilder
                .create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).build();
        assertEquals("DebugAdapterRunner", environment.getRunner().getRunnerId());
        java.util.Set<XDebugSession> before = java.util.Set.of(XDebuggerManager.getInstance(getProject()).getDebugSessions());
        environment.getRunner().execute(environment);
        // The new session: one of an earlier test may not have ended yet.
        java.util.function.Supplier<XDebugSession> started = () -> java.util.Arrays.stream(
                XDebuggerManager.getInstance(getProject()).getDebugSessions()).filter(s -> !before.contains(s)).findFirst().orElse(null);
        waitFor("a debug session", () -> started.get() != null);
        return started.get();
    }

    private static void waitFor(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
