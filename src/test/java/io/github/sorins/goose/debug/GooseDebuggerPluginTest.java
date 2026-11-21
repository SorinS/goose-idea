package io.github.sorins.goose.debug;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.platform.dap.DapBreakpointsDescription;
import com.intellij.platform.dap.DapLaunchArgumentsProvider;
import com.intellij.platform.dap.DebugAdapterSupportProvider;
import com.intellij.platform.dap.LaunchRequestArguments;
import com.intellij.platform.dap.DapStartRequest;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.xdebugger.XDebuggerUtil;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.run.GooseConfigurationType;
import io.github.sorins.goose.run.GooseRunConfiguration;
import io.github.sorins.goose.run.GooseRunLineMarkerContributor;
import io.github.sorins.goose.settings.GooseSettings;
import org.jdom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The debugger's extensions in a test IDE: breakpoints, the DAP runner, launch arguments, settings. */
public class GooseDebuggerPluginTest extends BasePlatformTestCase {
    private GooseRunConfiguration configuration(String file, GooseCompiler.Action action) {
        RunnerAndConfigurationSettings settings = RunManager.getInstance(getProject())
                .createConfiguration("test", GooseConfigurationType.getInstance().factory());
        GooseRunConfiguration configuration = (GooseRunConfiguration) settings.getConfiguration();
        configuration.getOptions().setFile(file);
        configuration.getOptions().setAction(action);
        return configuration;
    }

    public void testBreakpointTypesAreRegistered() {
        assertNotNull(XDebuggerUtil.getInstance().findBreakpointType(GooseLineBreakpointType.class));
        var runtimeErrors = XDebuggerUtil.getInstance().findBreakpointType(GooseRuntimeErrorBreakpointType.class);
        assertNotNull(runtimeErrors);
        // The platform's DAP client needs a default exception breakpoint for every stop with reason "exception".
        assertEquals(1, com.intellij.xdebugger.XDebuggerManager.getInstance(getProject()).getBreakpointManager()
                .getDefaultBreakpoints(runtimeErrors).size());
    }

    public void testLineBreakpointsGoOnGooseCodeLines() {
        var goose = myFixture.configureByText("area.goose",
                "// area\n\nfn area(w: int) -> int {\n    return w * 2\n}\n   // indented comment\n").getVirtualFile();
        var text = myFixture.configureByText("notes.txt", "fn area() {\n}\n").getVirtualFile();
        GooseLineBreakpointType type = XDebuggerUtil.getInstance().findBreakpointType(GooseLineBreakpointType.class);
        assertFalse("comment", type.canPutAt(goose, 0, getProject()));
        assertFalse("blank", type.canPutAt(goose, 1, getProject()));
        assertTrue(type.canPutAt(goose, 2, getProject()));
        assertTrue(type.canPutAt(goose, 3, getProject()));
        assertFalse("indented comment", type.canPutAt(goose, 5, getProject()));
        assertFalse("past the end", type.canPutAt(goose, 40, getProject()));
        assertFalse("not a .goose file", type.canPutAt(text, 0, getProject()));
    }

    public void testSupportProviderAndDescriptor() {
        GooseDebugAdapterSupportProvider provider = DebugAdapterSupportProvider.Companion.getEP_NAME().getExtensionList().stream()
                .filter(GooseDebugAdapterSupportProvider.class::isInstance).map(GooseDebugAdapterSupportProvider.class::cast)
                .findFirst().orElseThrow();
        assertSame(GooseDebugAdapterId.INSTANCE, provider.getAdapterId());
        var descriptor = provider.createDebugAdapterDescriptor(getProject());
        assertSame(GooseDebugAdapterId.INSTANCE, descriptor.getId());
        DapBreakpointsDescription breakpoints = descriptor.getBreakpointsDescription();
        assertEquals(GooseLineBreakpointType.class, breakpoints.getSourceBreakpointType());
        assertEquals(GooseRuntimeErrorBreakpointType.class, breakpoints.getExceptionBreakpointType());
    }

    /**
     * The platform's DAP runner takes Debug for a program run, and nothing
     * else: Run stays the plugin's JIT run, and Check, Build and Generate C
     * have no Debug.
     */
    public void testDapRunnerTakesDebugOnly() {
        GooseRunConfiguration run = configuration("/x/area.goose", GooseCompiler.Action.RUN);
        ProgramRunner<?> debug = ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, run);
        assertNotNull(debug);
        assertEquals("DebugAdapterRunner", debug.getRunnerId());
        ProgramRunner<?> plain = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, run);
        assertNotNull(plain);
        assertFalse("DebugAdapterRunner".equals(plain.getRunnerId()));
        for (GooseCompiler.Action action : List.of(GooseCompiler.Action.CHECK, GooseCompiler.Action.BUILD, GooseCompiler.Action.GENERATE_C)) {
            assertNull(action.name(), ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration("/x/area.goose", action)));
        }
    }

    public void testLaunchArgumentsFromTheConfiguration() {
        GooseRunConfiguration run = configuration("/x/area.goose", GooseCompiler.Action.RUN);
        run.getOptions().setProgramArguments("one \"two words\"");
        run.getOptions().setWorkingDirectory("/work");
        run.getOptions().setEnvironment(Map.of("GOOSE_X", "1"));
        GooseDapLaunchArgumentsProvider provider = DapLaunchArgumentsProvider.Companion.getEP_NAME().getExtensionList().stream()
                .filter(GooseDapLaunchArgumentsProvider.class::isInstance).map(GooseDapLaunchArgumentsProvider.class::cast)
                .findFirst().orElseThrow();
        assertTrue(provider.isApplicable(DefaultDebugExecutor.EXECUTOR_ID, run));
        assertFalse(provider.isApplicable(DefaultRunExecutor.EXECUTOR_ID, run));
        GooseSettings.getInstance(getProject()).getState().debugAdapter = GooseDebugAdapter.Kind.LLDB_DAP.name();
        try {
            LaunchRequestArguments launch = provider.getLaunchArguments(getProject(), run);
            assertSame(GooseDebugAdapterId.INSTANCE, launch.getAdapterId());
            assertEquals(DapStartRequest.Launch, launch.getRequest());
            Map<String, Object> args = launch.getArguments();
            assertEquals(run.debugExecutable().toString(), args.get("program"));
            assertEquals(List.of("one", "two words"), args.get("args"));
            assertEquals("/work", args.get("cwd"));
            assertEquals(List.of("GOOSE_X=1"), args.get("env"));
            assertEquals(List.of(GooseDebugAdapter.importCommand(provider.lldbScript())), args.get("initCommands"));
            assertEquals(List.of("breakpoint set --name gs_abort"), args.get("preRunCommands"));
        } finally {
            GooseSettings.getInstance(getProject()).getState().debugAdapter = GooseDebugAdapter.Kind.AUTO.name();
        }
    }

    public void testGooseLldbScriptShipsWithThePlugin() throws Exception {
        Path script = DapLaunchArgumentsProvider.Companion.getEP_NAME().getExtensionList().stream()
                .filter(GooseDapLaunchArgumentsProvider.class::isInstance).map(GooseDapLaunchArgumentsProvider.class::cast)
                .findFirst().orElseThrow().lldbScript();
        assertNotNull("lldb/goose_lldb.py is not in the plugin's directory", script);
        assertTrue(Files.readString(script).contains("__lldb_init_module"));
    }

    public void testDebugBuildCommandLine() throws Exception {
        Path compiler = Files.createTempFile("goose", "");
        compiler.toFile().setExecutable(true);
        GooseSettings.State state = GooseSettings.getInstance(getProject()).getState();
        String saved = state.compilerPath;
        state.compilerPath = compiler.toString();
        try {
            GooseRunConfiguration run = configuration("/x/area.goose", GooseCompiler.Action.RUN);
            GeneralCommandLine commandLine = run.debugBuildCommandLine();
            assertEquals(compiler.toString(), commandLine.getExePath());
            assertEquals(List.of("build", "-g", "-o", run.debugExecutable().toString(), "/x/area.goose"),
                    commandLine.getParametersList().getList());
            assertTrue(run.debugExecutable().toString(), run.debugExecutable().startsWith(Path.of(
                    com.intellij.openapi.application.PathManager.getSystemPath(), "goose", "debug")));
        } finally {
            state.compilerPath = saved;
            Files.deleteIfExists(compiler);
        }
    }

    /** A compiler without -g gets a clear error, before anything is built. */
    public void testCompilerWithoutDebugInfoIsRefused() throws Exception {
        if (GooseCompiler.isWindows()) return;
        Path compiler = Files.createTempFile("goose-old", "");
        Files.writeString(compiler, "#!/bin/sh\necho 'usage: goose build file.goose [options]'\necho '  --debug   Overflow, range and tag checks'\n");
        compiler.toFile().setExecutable(true);
        assertFalse(GooseDebugBuilder.supportsDebugInfo(compiler));
        GooseSettings.State state = GooseSettings.getInstance(getProject()).getState();
        String saved = state.compilerPath;
        state.compilerPath = compiler.toString();
        List<String> titles = new java.util.ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(com.intellij.notification.Notifications.TOPIC,
                new com.intellij.notification.Notifications() {
                    @Override
                    public void notify(@org.jetbrains.annotations.NotNull com.intellij.notification.Notification notification) {
                        titles.add(notification.getTitle());
                    }
                });
        try {
            GooseRunConfiguration run = configuration("/x/area.goose", GooseCompiler.Action.RUN);
            try {
                GooseDebugBuilder.build(getProject(), run, () -> false);
                fail("built with a compiler that has no -g");
            } catch (com.intellij.execution.CantRunException.CustomProcessedCantRunException expected) {
                // Reported by the plugin's notification, not again by the platform.
            }
            assertEquals(List.of("This Goose compiler cannot build for debugging"), titles);
        } finally {
            state.compilerPath = saved;
            Files.deleteIfExists(compiler);
        }
    }

    public void testSettingsRoundTrip() {
        GooseSettings.State state = new GooseSettings.State();
        state.debugAdapter = GooseDebugAdapter.Kind.GOOSE_DAP.name();
        state.debugAdapterPath = "/opt/goose/bin/goose-dap";
        state.stopOnRuntimeErrors = false;
        Element element = XmlSerializer.serialize(state);
        GooseSettings.State loaded = XmlSerializer.deserialize(element, GooseSettings.State.class);
        GooseSettings settings = new GooseSettings();
        settings.loadState(loaded);
        assertEquals(GooseDebugAdapter.Kind.GOOSE_DAP, settings.debugAdapterKind());
        assertEquals("/opt/goose/bin/goose-dap", settings.getState().debugAdapterPath);
        assertFalse(settings.getState().stopOnRuntimeErrors);
        // Defaults: Auto, searched, stop on runtime errors.
        GooseSettings.State defaults = new GooseSettings.State();
        assertEquals(GooseDebugAdapter.Kind.AUTO, GooseDebugAdapter.Kind.parse(defaults.debugAdapter));
        assertEquals("", defaults.debugAdapterPath);
        assertTrue(defaults.stopOnRuntimeErrors);
    }

    public void testRunConfigurationEnvironmentIsStored() {
        GooseRunConfiguration run = configuration("/x/area.goose", GooseCompiler.Action.RUN);
        run.getOptions().setEnvironment(Map.of("A", "1"));
        Element element = new Element("configuration");
        run.writeExternal(element);
        GooseRunConfiguration copy = configuration("", GooseCompiler.Action.CHECK);
        copy.readExternal(element);
        assertEquals(Map.of("A", "1"), copy.environment());
        assertEquals(GooseCompiler.Action.RUN, copy.getOptions().getAction());
    }

    public void testGutterMarksProgramsOnly() {
        var program = myFixture.configureByText("main.goose", "import x\n\nfn main() {\n}\n");
        GooseRunLineMarkerContributor contributor = new GooseRunLineMarkerContributor();
        var leaf = program.findElementAt(0);
        // Light test files live in a temp:// file system, which has nothing to run; the pattern is what is checked.
        assertNotNull(leaf);
        assertTrue(GooseRunLineMarkerContributor.hasMain("import x\n\nfn main() {\n}\n"));
        assertTrue(GooseRunLineMarkerContributor.hasMain("pub fn x() {}\n  fn main( ) {}"));
        assertFalse(GooseRunLineMarkerContributor.hasMain("fn mainly() {}\n// fn main()\n"));
        assertNull(contributor.getInfo(program));
    }
}
