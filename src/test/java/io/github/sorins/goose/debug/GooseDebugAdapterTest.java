package io.github.sorins.goose.debug;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Adapter discovery, launch arguments and the debug build's command line, without the platform. */
public class GooseDebugAdapterTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Path touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "");
        return p;
    }

    private static GooseDebugAdapter.Found find(GooseDebugAdapter.Kind kind, String configured, Path compiler, String path,
                                                GooseDebugAdapter.Os os, Set<Path> present) {
        return GooseDebugAdapter.find(kind, configured, null, compiler, path, null, os, present::contains, () -> null);
    }

    @Test
    public void autoPrefersGooseDapBesideTheCompiler() throws IOException {
        Path bin = tmp.newFolder("bin").toPath();
        Path compiler = touch(bin.resolve("goose"));
        Path gooseDap = touch(bin.resolve("goose-dap"));
        Set<Path> present = Set.of(compiler, gooseDap, GooseDebugAdapter.MAC_CLT_LLDB_DAP);
        GooseDebugAdapter.Found found = find(GooseDebugAdapter.Kind.AUTO, "", compiler, "", GooseDebugAdapter.Os.MAC, present);
        assertEquals(new GooseDebugAdapter.Found(GooseDebugAdapter.Kind.GOOSE_DAP, gooseDap), found);
        // lldb-dap asked for by kind wins over a goose-dap that is present.
        found = find(GooseDebugAdapter.Kind.LLDB_DAP, "", compiler, "", GooseDebugAdapter.Os.MAC, present);
        assertEquals(new GooseDebugAdapter.Found(GooseDebugAdapter.Kind.LLDB_DAP, GooseDebugAdapter.MAC_CLT_LLDB_DAP), found);
    }

    @Test
    public void autoFallsBackToTheCommandLineToolsLldbDapOnMac() {
        GooseDebugAdapter.Found found = find(GooseDebugAdapter.Kind.AUTO, "", null, "", GooseDebugAdapter.Os.MAC,
                Set.of(GooseDebugAdapter.MAC_CLT_LLDB_DAP));
        assertEquals(new GooseDebugAdapter.Found(GooseDebugAdapter.Kind.LLDB_DAP, GooseDebugAdapter.MAC_CLT_LLDB_DAP), found);
        assertNull(find(GooseDebugAdapter.Kind.GOOSE_DAP, "", null, "", GooseDebugAdapter.Os.MAC,
                Set.of(GooseDebugAdapter.MAC_CLT_LLDB_DAP)));
    }

    @Test
    public void xcrunIsAskedLastOnMac() {
        Path xcode = Path.of("/Applications/Xcode-beta.app/Contents/Developer/usr/bin/lldb-dap");
        GooseDebugAdapter.Found found = GooseDebugAdapter.find(GooseDebugAdapter.Kind.AUTO, "", null, null, "", null,
                GooseDebugAdapter.Os.MAC, Set.of(xcode)::contains, () -> xcode);
        assertEquals(xcode, found.path());
    }

    @Test
    public void linuxFindsLldbVscodeAndVersionedLldbDapOnPath() throws IOException {
        Path a = tmp.newFolder("a").toPath();
        Path b = tmp.newFolder("b").toPath();
        Path vscode = touch(a.resolve("lldb-vscode"));
        assertEquals(vscode, find(GooseDebugAdapter.Kind.LLDB_DAP, "", null, a + ":" + b, GooseDebugAdapter.Os.LINUX,
                Set.of(vscode)).path());
        Path v18 = touch(a.resolve("lldb-dap-18"));
        Path v19 = touch(b.resolve("lldb-dap-19"));
        assertEquals(v19, find(GooseDebugAdapter.Kind.LLDB_DAP, "", null, a + ":" + b, GooseDebugAdapter.Os.LINUX,
                Set.of(v18, v19)).path());
    }

    @Test
    public void windowsLooksForExeOnPathAndInLlvm() throws IOException {
        Path bin = tmp.newFolder("llvm").toPath();
        Path exe = touch(bin.resolve("lldb-dap.exe"));
        assertEquals(exe, find(GooseDebugAdapter.Kind.AUTO, "", null, bin.toString(), GooseDebugAdapter.Os.WINDOWS,
                Set.of(exe)).path());
        Path llvm = Path.of("C:\\Program Files\\LLVM\\bin\\lldb-dap.exe");
        assertEquals(llvm, find(GooseDebugAdapter.Kind.AUTO, "", null, "", GooseDebugAdapter.Os.WINDOWS, Set.of(llvm)).path());
    }

    @Test
    public void configuredPathIsUsedAndItsNameGivesTheKind() throws IOException {
        Path dap = touch(tmp.getRoot().toPath().resolve("tools/goose-dap"));
        Path lldb = touch(tmp.getRoot().toPath().resolve("tools/lldb-dap"));
        Set<Path> present = Set.of(dap, lldb);
        assertEquals(new GooseDebugAdapter.Found(GooseDebugAdapter.Kind.GOOSE_DAP, dap),
                find(GooseDebugAdapter.Kind.AUTO, dap.toString(), null, "", GooseDebugAdapter.Os.MAC, present));
        assertEquals(new GooseDebugAdapter.Found(GooseDebugAdapter.Kind.LLDB_DAP, lldb),
                find(GooseDebugAdapter.Kind.AUTO, lldb.toString(), null, "", GooseDebugAdapter.Os.MAC, present));
        // A kind chosen explicitly holds whatever the file is called.
        assertEquals(GooseDebugAdapter.Kind.GOOSE_DAP,
                find(GooseDebugAdapter.Kind.GOOSE_DAP, lldb.toString(), null, "", GooseDebugAdapter.Os.MAC, present).kind());
        assertNull(find(GooseDebugAdapter.Kind.AUTO, "/no/such/lldb-dap", null, "", GooseDebugAdapter.Os.MAC, present));
        // A bare name is a command on PATH.
        assertEquals(lldb, find(GooseDebugAdapter.Kind.AUTO, "lldb-dap", null, lldb.getParent().toString(),
                GooseDebugAdapter.Os.MAC, present).path());
    }

    @Test
    public void kindParsesStoredNames() {
        assertEquals(GooseDebugAdapter.Kind.AUTO, GooseDebugAdapter.Kind.parse(""));
        assertEquals(GooseDebugAdapter.Kind.AUTO, GooseDebugAdapter.Kind.parse("nonsense"));
        assertEquals(GooseDebugAdapter.Kind.LLDB_DAP, GooseDebugAdapter.Kind.parse("LLDB_DAP"));
    }

    @Test
    public void lldbDapLaunchImportsTheFormattersAndBreaksOnRuntimeErrors() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("A", "1");
        env.put("B", "x y");
        Map<String, Object> args = GooseDebugAdapter.launchArguments(GooseDebugAdapter.Kind.LLDB_DAP,
                Path.of("/sys/goose/area-1234"), Path.of("/work"), List.of("one", "two words"), env,
                Path.of("/plugins/goose idea/lldb/goose_lldb.py"), true);
        assertEquals("/sys/goose/area-1234", args.get("program"));
        assertEquals(List.of("one", "two words"), args.get("args"));
        assertEquals("/work", args.get("cwd"));
        assertEquals(List.of("A=1", "B=x y"), args.get("env"));
        assertEquals(false, args.get("stopOnEntry"));
        assertEquals(List.of("command script import \"/plugins/goose idea/lldb/goose_lldb.py\""), args.get("initCommands"));
        assertEquals(List.of("breakpoint set --name gs_abort"), args.get("preRunCommands"));

        args = GooseDebugAdapter.launchArguments(GooseDebugAdapter.Kind.LLDB_DAP, Path.of("/p"), null, List.of(), Map.of(),
                null, false);
        assertFalse(args.containsKey("env"));
        assertFalse(args.containsKey("cwd"));
        assertFalse(args.containsKey("initCommands"));
        assertFalse(args.containsKey("preRunCommands"));
    }

    @Test
    public void gooseDapLaunchLeavesFormattersAndRuntimeErrorsToTheProxy() {
        Map<String, Object> args = GooseDebugAdapter.launchArguments(GooseDebugAdapter.Kind.GOOSE_DAP, Path.of("/p"),
                Path.of("/w"), List.of("a"), Map.of(), Path.of("/x/goose_lldb.py"), true);
        assertEquals("/p", args.get("program"));
        assertEquals(List.of("a"), args.get("args"));
        assertFalse(args.containsKey("initCommands"));
        assertFalse(args.containsKey("preRunCommands"));
        assertEquals(true, args.get("stopOnRuntimeError"));
        args = GooseDebugAdapter.launchArguments(GooseDebugAdapter.Kind.GOOSE_DAP, Path.of("/p"),
                Path.of("/w"), List.of(), Map.of(), null, false);
        assertEquals(false, args.get("stopOnRuntimeError"));
    }

    @Test
    public void importCommandQuotesForLldb() {
        assertEquals("command script import \"/a \\\"b\\\"/c.py\"", GooseDebugAdapter.importCommand(Path.of("/a \"b\"/c.py")));
    }

    @Test
    public void debugBuildArguments() {
        Path file = Path.of("/src/area.goose");
        Path out = Path.of("/sys/area-0011");
        assertEquals(List.of("build", "-g", "-o", "/sys/area-0011", "/src/area.goose"),
                GooseDebugBuild.arguments(null, file, out));
        assertEquals(List.of("build", "-g", "--stdlib", "/std", "-o", "/sys/area-0011", "/src/area.goose"),
                GooseDebugBuild.arguments(Path.of("/std"), file, out));
    }

    @Test
    public void debugOutputIsPerSourcePath() {
        Path dir = Path.of("/sys/goose/debug/p");
        Path a = GooseDebugBuild.outputPath(dir, Path.of("/x/area.goose"), false);
        Path b = GooseDebugBuild.outputPath(dir, Path.of("/y/area.goose"), false);
        assertEquals(dir, a.getParent());
        assertTrue(a.getFileName().toString(), a.getFileName().toString().matches("area-[0-9a-f]{8}"));
        assertNotEquals(a, b);
        assertEquals(a, GooseDebugBuild.outputPath(dir, Path.of("/x/area.goose"), false));
        assertTrue(GooseDebugBuild.outputPath(dir, Path.of("/x/area.goose"), true).toString().endsWith(".exe"));
    }

    @Test
    public void debugInfoIsToldFromTheBuildHelp() {
        assertTrue(GooseDebugBuild.supportsDebugInfo("  -g, --debug-info      --debug, and the C built for stepping"));
        assertFalse(GooseDebugBuild.supportsDebugInfo("  --debug   Overflow, range and tag checks"));
        assertFalse(GooseDebugBuild.supportsDebugInfo(null));
    }
}
