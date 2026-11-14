package io.github.sorins.goose.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.GooseFiles;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code goose lsp [--stdlib dir]}, started in the project directory, with
 * the initialization options of the VS Code extension's client: the entry
 * files, the standard library, and the client-side commands the server's
 * code lenses may name.
 */
public final class GooseLspClientDescriptor extends ProjectWideLspClientDescriptor {
    /** The commands this client runs itself (see {@link GooseLspCustomization}). */
    public static final List<String> CLIENT_COMMANDS = List.of(GooseLspCustomization.RUN, GooseLspCustomization.SHOW_INSTANTIATIONS);

    private final GooseLspCustomization customization = new GooseLspCustomization(this);

    public GooseLspClientDescriptor(@NotNull Project project) {
        super(project, "Goose");
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return GooseFiles.isGooseFile(file);
    }

    @Override
    public @NotNull String getLanguageId(@NotNull VirtualFile file) {
        return GooseFiles.LANGUAGE_ID;
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() throws ExecutionException {
        Project project = getProject();
        GooseSettings settings = GooseSettings.getInstance(project);
        Path compiler = settings.compiler(project);
        if (compiler == null) throw new ExecutionException("Goose compiler not found; set it in Settings | Languages & Frameworks | Goose");
        GeneralCommandLine commandLine = new GeneralCommandLine(compiler.toString())
                .withParameters(GooseCompiler.lspArguments(settings.stdlib(project)))
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(java.nio.charset.StandardCharsets.UTF_8);
        Path dir = GooseSettings.projectDir(project);
        if (dir != null) commandLine.withWorkDirectory(dir.toFile());
        return commandLine;
    }

    @Override
    public @NotNull Object createInitializationOptions() {
        Project project = getProject();
        GooseSettings settings = GooseSettings.getInstance(project);
        return initializationOptions(settings.entryFiles(project), settings.stdlib(project));
    }

    /** The options as the VS Code extension sends them: {@code {entryFiles, stdlib, commands}}. */
    public static @NotNull Map<String, Object> initializationOptions(List<Path> entryFiles, Path stdlib) {
        List<String> entries = new ArrayList<>();
        for (Path entry : entryFiles) entries.add(entry.toString());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("entryFiles", entries);
        options.put("stdlib", stdlib == null ? "" : stdlib.toString());
        options.put("commands", CLIENT_COMMANDS);
        return options;
    }

    @Override
    public @NotNull LspCustomization getLspCustomization() {
        return customization;
    }
}
