package io.github.sorins.goose.lsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import io.github.sorins.goose.GooseFiles;
import io.github.sorins.goose.GoosePackage;
import io.github.sorins.goose.settings.GooseSettings;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The entry files the server is given: the configured ones, and, when the
 * project is a Goose package, a program of the plugin's making for each
 * open module of the package (see {@link GoosePackage}), so that the
 * module's imports resolve as they do when the package is compiled. The
 * programs live in the IDE's system directory, one per module, and follow
 * the open editors: the server is told of the change through
 * {@code workspace/didChangeConfiguration}.
 */
@Service(Service.Level.PROJECT)
public final class GoosePackageRoots implements Disposable {
    private static final Logger LOG = Logger.getInstance(GoosePackageRoots.class);

    private final Project project;

    public GoosePackageRoots(@NotNull Project project) {
        this.project = project;
    }

    public static GoosePackageRoots getInstance(@NotNull Project project) {
        return project.getService(GoosePackageRoots.class);
    }

    /** The configured entry files, then a program for each open module of the package. */
    public @NotNull List<Path> entryFiles() {
        GooseSettings settings = GooseSettings.getInstance(project);
        Set<Path> entries = new LinkedHashSet<>(settings.entryFiles(project));
        GoosePackage pkg = GoosePackage.at(GooseSettings.projectDir(project));
        if (pkg == null) return new ArrayList<>(entries);
        for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (!GooseFiles.isGooseFile(file) || file.getFileSystem().getNioPath(file) == null) continue;
            Path path = file.toNioPath();
            if (entries.contains(path)) continue;
            String module = pkg.moduleOf(path, text(file));
            if (module == null) continue;
            Path root = writeRoot(pkg, module);
            if (root != null) entries.add(root);
        }
        return new ArrayList<>(entries);
    }

    /** The module of the project's package that {@code file} is, or null (a program, or no package). */
    public @Nullable String moduleOf(@Nullable VirtualFile file) {
        if (!GooseFiles.isGooseFile(file) || file.getFileSystem().getNioPath(file) == null) return null;
        GoosePackage pkg = GoosePackage.at(GooseSettings.projectDir(project));
        return pkg == null ? null : pkg.moduleOf(file.toNioPath(), text(file));
    }

    /** The plugin's program that checks {@code file}'s module as part of the package, or null. */
    public @Nullable Path rootFor(@Nullable VirtualFile file) {
        String module = moduleOf(file);
        GoosePackage pkg = GoosePackage.at(GooseSettings.projectDir(project));
        return module == null || pkg == null ? null : writeRoot(pkg, module);
    }

    /**
     * Tells the running server the entry files for the editors now open. A
     * server still initializing may have taken its options before the
     * latest editor opened, so it is told once it runs (polled: the
     * platform's listener for server states is internal API).
     */
    public void update() {
        update(0);
    }

    private void update(int attempt) {
        if (project.isDisposed()) return;
        for (LspClient client : LspClientManager.getInstance(project).getClients(GooseLspIntegrationProvider.class)) {
            if (client.getState() == LspServerState.Running) {
                sendConfiguration(client);
            } else if (client.getState() == LspServerState.Initializing && attempt < 50) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        () -> ApplicationManager.getApplication().invokeLater(() -> update(attempt + 1), project.getDisposed()),
                        200, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void sendConfiguration(LspClient client) {
        GooseSettings settings = GooseSettings.getInstance(project);
        Map<String, Object> options = GooseLspClientDescriptor.initializationOptions(entryFiles(), settings.stdlib(project));
        client.sendNotification(server -> {
            server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(Map.of("goose", options)));
            return kotlin.Unit.INSTANCE;
        });
    }

    private Path writeRoot(GoosePackage pkg, String module) {
        Path dir = Path.of(PathManager.getSystemPath(), "goose", "roots", project.getLocationHash());
        Path root = dir.resolve(module + ".goose");
        String source = pkg.rootSource(module);
        try {
            if (!Files.isRegularFile(root) || !Files.readString(root).equals(source)) {
                Files.createDirectories(dir);
                Files.writeString(root, source, StandardCharsets.UTF_8);
            }
            return root;
        } catch (IOException e) {
            LOG.warn("Cannot write " + root, e);
            return null;
        }
    }

    private static CharSequence text(VirtualFile file) {
        var document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document != null) return document.getImmutableCharSequence();
        try {
            return new String(file.contentsToByteArray(), file.getCharset());
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public void dispose() {}
}
