package io.github.sorins.goose;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** The goose icon for {@code .goose} files, which are TextMate files to the platform. */
public final class GooseIconProvider implements FileIconProvider {
    @Override
    public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
        return GooseFiles.isGooseFile(file) ? GooseFiles.ICON : null;
    }
}
