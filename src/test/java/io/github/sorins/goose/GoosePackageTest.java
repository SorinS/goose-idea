package io.github.sorins.goose;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class GoosePackageTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** gslib's layout: lib.goose with a sidecar, other.goose beside it, modules below. */
    private Path library() throws IOException {
        Path dir = tmp.newFolder("lib.git").toPath();
        write(dir.resolve("lib.goose"), "// lib: the entry\nnamespace lib;\n");
        write(dir.resolve("lib.build"), "c_sources = [];\n");
        write(dir.resolve("other.goose"), "namespace other;\nfn twice(x: i64) -> i64 { x * 2 }\n");
        write(dir.resolve("crypto/hash.goose"), "namespace lib;\nfn hash(x: i64) -> i64 { x + 1 }\n");
        write(dir.resolve("crypto/user.goose"),
                "namespace lib;\nimport crypto.hash;\nimport other;\nfn use_hash() -> i64 { hash(1) + other::twice(2) }\n");
        write(dir.resolve("crypto/test/t.goose"),
                "import lib from \"../..\";\nimport lib.crypto.hash;\nfn main() { assert(lib::hash(1) == 2); }\n");
        return dir;
    }

    @Test
    public void entryIsTheFileWithASidecar() throws IOException {
        GoosePackage pkg = GoosePackage.at(library());
        assertNotNull(pkg);
        assertEquals("lib", pkg.name);
    }

    @Test
    public void entryIsNamedAsTheDirectoryWithoutASidecar() throws IOException {
        Path dir = library();
        Files.delete(dir.resolve("lib.build"));
        GoosePackage pkg = GoosePackage.at(dir);
        assertNotNull(pkg);
        assertEquals("lib", pkg.name);
    }

    @Test
    public void notAPackage() throws IOException {
        Path dir = tmp.newFolder("plain").toPath();
        write(dir.resolve("main.goose"), "fn main() {}\n");
        assertNull(GoosePackage.at(dir));
    }

    @Test
    public void modulesAreNamedByTheirPath() throws IOException {
        Path dir = library();
        GoosePackage pkg = GoosePackage.at(dir);
        assertNotNull(pkg);
        assertEquals("lib.crypto.hash", pkg.moduleOf(dir.resolve("crypto/hash.goose"), "namespace lib;"));
        assertEquals("lib.other", pkg.moduleOf(dir.resolve("other.goose"), "namespace other;"));
        assertEquals("lib", pkg.moduleOf(dir.resolve("lib.goose"), "namespace lib;"));
        // Programs are roots of their own; files outside are not the package's.
        assertNull(pkg.moduleOf(dir.resolve("crypto/test/t.goose"), "fn main() {}\n"));
        assertNull(pkg.moduleOf(dir.resolve("crypto/my-file.goose"), ""));
        assertNull(pkg.moduleOf(dir.resolveSibling("elsewhere/x.goose"), ""));
    }

    @Test
    public void rootSourceDeclaresThePackageAndImportsTheModule() throws IOException {
        Path dir = library();
        GoosePackage pkg = GoosePackage.at(dir);
        assertNotNull(pkg);
        String source = pkg.rootSource("lib.crypto.user");
        assertTrue(source, source.contains("import lib from \"" + dir.toAbsolutePath().normalize().toString().replace('\\', '/') + "\";\n"));
        assertTrue(source, source.contains("import lib.crypto.user;\n"));
        assertFalse(pkg.rootSource("lib").contains("import lib;"));
    }

    /** The compiler checks a module through the root, where it does not on its own. */
    @Test
    public void compilerChecksTheModuleThroughTheRoot() throws Exception {
        Path compiler = GooseCompiler.find(System.getProperty("goose.compiler", ""), null);
        Assume.assumeNotNull(compiler);
        Path dir = library();
        GoosePackage pkg = GoosePackage.at(dir);
        assertNotNull(pkg);
        Path module = dir.resolve("crypto/user.goose");
        assertNotEquals("the module alone", 0, check(compiler, module));
        Path root = tmp.newFolder("roots").toPath().resolve("lib.crypto.user.goose");
        Files.writeString(root, pkg.rootSource(pkg.moduleOf(module, Files.readString(module))));
        assertEquals("through the root", 0, check(compiler, root));
    }

    private static int check(Path compiler, Path file) throws Exception {
        Process p = new ProcessBuilder(compiler.toString(), "check", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("goose check timed out", p.waitFor(60, TimeUnit.SECONDS));
        if (p.exitValue() != 0) System.out.println(file.getFileName() + ": " + out);
        return p.exitValue();
    }
}
