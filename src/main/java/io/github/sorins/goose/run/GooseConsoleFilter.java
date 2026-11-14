package io.github.sorins.goose.run;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links the compiler's {@code file:line: error: message} lines to the source,
 * as the VS Code extension's problem matcher reads them.
 */
public final class GooseConsoleFilter implements Filter {
    public static final Pattern LINE = Pattern.compile("^(.+?):(\\d+): (error|warning): (.*)$");
    private final Project project;

    public GooseConsoleFilter(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        String text = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
        Matcher m = LINE.matcher(text);
        if (!m.matches()) return null;
        Path path;
        try {
            path = GooseCompiler.absolute(m.group(1), GooseSettings.projectDir(project));
        } catch (RuntimeException e) {
            return null;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (file == null) return null;
        int lineNumber = Math.max(0, Integer.parseInt(m.group(2)) - 1);
        int start = entireLength - line.length();
        return new Result(start, start + m.end(2), new OpenFileHyperlinkInfo(project, file, lineNumber));
    }
}
