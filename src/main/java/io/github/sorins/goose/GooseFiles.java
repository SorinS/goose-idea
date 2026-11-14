package io.github.sorins.goose;

import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Goose files are the {@code .goose} files. The plugin registers no file
 * type of its own: the TextMate bundle claims the extension, so the TextMate
 * plugin's highlighting, brace matching, commenting and snippets apply.
 */
public final class GooseFiles {
    public static final String EXTENSION = "goose";
    public static final String LANGUAGE_ID = "goose";
    public static final Icon ICON = IconLoader.getIcon("/icons/goose.svg", GooseFiles.class);

    private GooseFiles() {}

    public static boolean isGooseFile(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && isGooseFileName(file.getName());
    }

    public static boolean isGooseFileName(@Nullable String name) {
        return name != null && name.endsWith("." + EXTENSION) && name.length() > EXTENSION.length() + 1;
    }
}
