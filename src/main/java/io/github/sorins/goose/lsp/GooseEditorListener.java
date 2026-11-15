package io.github.sorins.goose.lsp;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sorins.goose.GooseFiles;
import org.jetbrains.annotations.NotNull;

/**
 * A package module's program joins the server's entry files when the module
 * opens, and leaves them when it closes (see {@link GoosePackageRoots}).
 * Opening is handled here rather than only in the integration provider's
 * {@code fileOpened}, which runs before the editor manager lists the file
 * as open, so the update made there misses it.
 */
public final class GooseEditorListener implements FileEditorManagerListener {
    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (GooseFiles.isGooseFile(file)) GoosePackageRoots.getInstance(source.getProject()).update();
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (GooseFiles.isGooseFile(file)) GoosePackageRoots.getInstance(source.getProject()).update();
    }
}
