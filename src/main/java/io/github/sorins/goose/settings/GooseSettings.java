package io.github.sorins.goose.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.xmlb.XmlSerializerUtil;
import io.github.sorins.goose.GooseCompiler;
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
