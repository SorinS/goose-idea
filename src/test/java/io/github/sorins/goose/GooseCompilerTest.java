package io.github.sorins.goose;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class GooseCompilerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Path touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "");
        return p;
    }

    @Test
    public void configuredPathIsResolvedAgainstTheProject() throws IOException {
        Path project = tmp.newFolder("project").toPath();
        Path compiler = touch(project.resolve("tools/goose"));
        assertEquals(compiler, GooseCompiler.find("tools/goose", project, "", null, false, Files::isRegularFile));
        assertEquals(compiler, GooseCompiler.find("${workspaceFolder}/tools/goose", project, "", null, false, Files::isRegularFile));
        assertEquals(compiler, GooseCompiler.find("$PROJECT_DIR$/tools/goose", project, "", null, false, Files::isRegularFile));
        assertNull(GooseCompiler.find("tools/missing", project, "", null, false, Files::isRegularFile));
    }

    @Test
    public void configuredCommandIsLookedUpOnPath() throws IOException {
        Path bin = tmp.newFolder("bin").toPath();
        Path compiler = touch(bin.resolve("goose-dev"));
        String path = "/nonexistent:" + bin;
        assertEquals(compiler, GooseCompiler.find("goose-dev", null, path, null, false, Files::isRegularFile));
    }

    @Test
    public void projectBuildComesBeforePath() throws IOException {
        Path project = tmp.newFolder("checkout").toPath();
        Path bin = tmp.newFolder("bin").toPath();
        touch(bin.resolve("goose"));
        Path debug = touch(project.resolve("build/Debug/goose"));
        assertEquals(debug, GooseCompiler.find("", project, bin.toString(), null, false, Files::isRegularFile));
        Path release = touch(project.resolve("build/Release/goose"));
        assertEquals(release, GooseCompiler.find("", project, bin.toString(), null, false, Files::isRegularFile));
        Path top = touch(project.resolve("goose"));
        assertEquals(top, GooseCompiler.find("", project, bin.toString(), null, false, Files::isRegularFile));
    }

    @Test
    public void pathComesBeforeLocalBin() throws IOException {
        Path home = tmp.newFolder("home").toPath();
        Path local = touch(home.resolve(".local/bin/goose"));
        Path bin = tmp.newFolder("bin").toPath();
        assertEquals(local, GooseCompiler.find("", null, bin.toString(), home, false, Files::isRegularFile));
        Path onPath = touch(bin.resolve("goose"));
        assertEquals(onPath, GooseCompiler.find("", null, bin.toString(), home, false, Files::isRegularFile));
    }

    @Test
    public void fallsBackToHomebrewAndUsrLocal() {
        Set<Path> exists = Set.of(Path.of("/usr/local/bin/goose"));
        assertEquals(Path.of("/usr/local/bin/goose"), GooseCompiler.find("", null, "", Path.of("/nohome"), false, exists::contains));
        assertNull(GooseCompiler.find("", null, "", Path.of("/nohome"), true, exists::contains));
    }

    @Test
    public void windowsLooksForExe() {
        Set<Path> exists = Set.of(Path.of("C:\\bin", "goose.exe"));
        assertEquals(Path.of("C:\\bin", "goose.exe").toAbsolutePath(),
                GooseCompiler.find("", null, "C:\\bin", null, true, exists::contains));
    }

    @Test
    public void lspArguments() {
        assertEquals(List.of("lsp"), GooseCompiler.lspArguments(null));
        assertEquals(List.of("lsp", "--stdlib", "/s"), GooseCompiler.lspArguments(Path.of("/s")));
    }

    @Test
    public void actionArgumentsMirrorTheVsCodeExtension() {
        Path file = Path.of("/p/main.goose");
        Path stdlib = Path.of("/s");
        assertEquals(List.of("--stdlib", "/s", "--jit", "/p/main.goose", "--", "a", "b c"),
                GooseCompiler.actionArguments(GooseCompiler.Action.RUN, stdlib, file, null, List.of("a", "b c")));
        assertEquals(List.of("--jit", "/p/main.goose"),
                GooseCompiler.actionArguments(GooseCompiler.Action.RUN, null, file, null, List.of()));
        assertEquals(List.of("check", "--stdlib", "/s", "/p/main.goose"),
                GooseCompiler.actionArguments(GooseCompiler.Action.CHECK, stdlib, file, null, List.of("ignored")));
        assertEquals(List.of("build", "/p/main.goose"),
                GooseCompiler.actionArguments(GooseCompiler.Action.BUILD, null, file, null, List.of()));
        assertEquals(List.of("-o", "/p/main.c", "/p/main.goose"),
                GooseCompiler.actionArguments(GooseCompiler.Action.GENERATE_C, null, file, null, List.of()));
    }

    @Test
    public void gooseFileNames() {
        assertTrue(GooseFiles.isGooseFileName("main.goose"));
        assertFalse(GooseFiles.isGooseFileName(".goose"));
        assertFalse(GooseFiles.isGooseFileName("main.goose.bak"));
        assertFalse(GooseFiles.isGooseFileName("goose"));
    }
}
