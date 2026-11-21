package io.github.sorins.goose.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.debug.GooseDebugAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The project's Goose settings, named as the VS Code extension's
 * {@code goose.*} settings. Kept in {@code .idea/goose.xml}.
 */
@Service(Service.Level.PROJECT)
@State(name = "GooseSettings", storages = @Storage("goose.xml"))
public final class GooseSettings implements PersistentStateComponent<GooseSettings.State> {
    public static final class State {
        /** goose.compilerPath: the compiler, or a command on PATH. Empty searches for it. */
        public String compilerPath = "";
        /** goose.stdlibPath: passed as --stdlib. Empty uses the compiler's lookup (GOOSE_STDLIB, stdlib/ near it). */
        public String stdlibPath = "";
        /**
         * goose.entryFile: the program entry files, one per line, relative to
         * the project. The language server checks the modules they import as
         * part of their program; the first is what the Goose actions run.
         */
        public String entryFiles = "";
        /** goose.useLanguageServer: start goose lsp. */
        public boolean useLanguageServer = true;
        /** goose.runArguments: the program's arguments for Run with JIT, as a command line. */
        public String runArguments = "";
        /** The debug adapter: AUTO, GOOSE_DAP or LLDB_DAP ({@link GooseDebugAdapter.Kind}). */
        public String debugAdapter = GooseDebugAdapter.Kind.AUTO.name();
        /** The debug adapter's executable, or a command on PATH. Empty searches for it. */
        public String debugAdapterPath = "";
        /** Stop the debugger where a Goose runtime error (assert, bounds, overflow) aborts. */
        public boolean stopOnRuntimeErrors = true;
    }

    private State state = new State();

    public static GooseSettings getInstance(@NotNull Project project) {
        return project.getService(GooseSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = new State();
        XmlSerializerUtil.copyBean(loaded, state);
    }

    public static @Nullable Path projectDir(@NotNull Project project) {
        String base = project.getBasePath();
        return base == null ? null : Path.of(base);
    }

    /** The compiler these settings find, or null. */
    public @Nullable Path compiler(@NotNull Project project) {
        return GooseCompiler.find(state.compilerPath, projectDir(project));
    }

    /** The configured standard library as an absolute path, or null. */
    public @Nullable Path stdlib(@NotNull Project project) {
        return state.stdlibPath.isBlank() ? null : GooseCompiler.absolute(state.stdlibPath, projectDir(project));
    }

    /** The configured entry files as absolute paths. */
    public @NotNull List<Path> entryFiles(@NotNull Project project) {
        return parseEntryFiles(state.entryFiles, projectDir(project));
    }

    /** The first entry file that exists, or null. */
    public @Nullable Path mainEntryFile(@NotNull Project project) {
        for (Path entry : entryFiles(project)) {
            if (Files.isRegularFile(entry)) return entry;
        }
        return null;
    }

    public @NotNull GooseDebugAdapter.Kind debugAdapterKind() {
        return GooseDebugAdapter.Kind.parse(state.debugAdapter);
    }

    /** The debug adapter these settings find, or null. */
    public @Nullable GooseDebugAdapter.Found debugAdapter(@NotNull Project project) {
        String home = System.getProperty("user.home");
        return GooseDebugAdapter.find(debugAdapterKind(), state.debugAdapterPath, projectDir(project), compiler(project),
                System.getenv("PATH"), home == null ? null : Path.of(home), GooseDebugAdapter.Os.current(),
                GooseDebugAdapter::isExecutableFile, GooseSettings::xcrunLldbDap);
    }

    private static volatile Path xcrunLldbDap;
    private static volatile boolean xcrunAsked;

    /** {@code xcrun -f lldb-dap}, asked once (macOS, when no lldb-dap is found elsewhere). */
    private static @Nullable Path xcrunLldbDap() {
        if (xcrunAsked) return xcrunLldbDap;
        xcrunAsked = true;
        try {
            ProcessOutput output = ExecUtil.execAndGetOutput(new GeneralCommandLine("xcrun", "-f", "lldb-dap"), 5000);
            String path = output.getStdout().trim();
            if (output.getExitCode() == 0 && !path.isEmpty()) xcrunLldbDap = Path.of(path);
        } catch (ExecutionException | RuntimeException ignored) {
            // No xcrun: not macOS, or no developer tools.
        }
        return xcrunLldbDap;
    }

    public @NotNull List<String> runArguments() {
        return ParametersListUtil.parse(state.runArguments);
    }

    static @NotNull List<Path> parseEntryFiles(String text, Path projectDir) {
        List<Path> files = new ArrayList<>();
        if (text == null) return files;
        for (String line : text.split("[\\r\\n;]+")) {
            if (!line.isBlank()) files.add(GooseCompiler.absolute(line, projectDir));
        }
        return files;
    }
}
