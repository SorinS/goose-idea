package io.github.sorins.goose.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import io.github.sorins.goose.GooseFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Line breakpoints in {@code .goose} files. The plugin has no Goose file
 * type (the TextMate bundle claims the extension), so files are told by
 * name. A breakpoint goes on any line that is not blank or a {@code //}
 * comment; the adapter moves one on a line without code to the next line
 * that has some, and the IDE shows it where it went.
 */
@SuppressWarnings("rawtypes")
public final class GooseLineBreakpointType extends XLineBreakpointType<XBreakpointProperties> {
    public static final String ID = "goose-line";

    public GooseLineBreakpointType() {
        super(ID, "Goose line breakpoints");
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        if (!GooseFiles.isGooseFile(file)) return false;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        return document == null || canPutAt(document, line);
    }

    /** A line that is neither blank nor only a {@code //} comment. */
    static boolean canPutAt(@NotNull Document document, int line) {
        if (line < 0 || line >= document.getLineCount()) return false;
        String text = document.getText(new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)));
        return canPutAt(text);
    }

    static boolean canPutAt(@NotNull String lineText) {
        String text = lineText.strip();
        return !text.isEmpty() && !text.startsWith("//");
    }

    @Override
    public @Nullable XBreakpointProperties createBreakpointProperties(@NotNull VirtualFile file, int line) {
        return null;
    }

    @Override
    public @Nullable XDebuggerEditorsProvider getEditorsProvider(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint,
                                                                 @NotNull Project project) {
        return new GooseDebuggerEditorsProvider();
    }
}
