package io.github.sorins.goose.lsp;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sorins.goose.GooseFiles;
import org.jetbrains.annotations.NotNull;

/** A closed package module's program leaves the server's entry files (see {@link GoosePackageRoots}). */
public final class GooseEditorListener implements FileEditorManagerListener {
    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (GooseFiles.isGooseFile(file)) GoosePackageRoots.getInstance(source.getProject()).update();
    }
}
