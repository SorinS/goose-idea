package io.github.sorins.goose.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.github.sorins.goose.GooseFiles;
import io.github.sorins.goose.lsp.GoosePackageRoots;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Run and Debug in the gutter of a Goose program. The TextMate plugin parses
 * a file as a single token, so the icon is on the file's first line rather
 * than beside {@code fn main}; it shows on files that have one and are not a
 * module of the project's package.
 */
public final class GooseRunLineMarkerContributor extends RunLineMarkerContributor {
    static final Pattern MAIN = Pattern.compile("(?m)^\\s*fn\\s+main\\s*\\(");

    /** Whether the text declares {@code fn main(} at the start of a line. */
    public static boolean hasMain(@NotNull CharSequence text) {
        return MAIN.matcher(text).find();
    }

    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        if (element.getFirstChild() != null || element.getTextRange().getStartOffset() != 0) return null;
        PsiFile file = element.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (!GooseFiles.isGooseFile(virtualFile) || !virtualFile.isInLocalFileSystem()) return null;
        if (!hasMain(file.getViewProvider().getContents())) return null;
        if (GoosePackageRoots.getInstance(file.getProject()).moduleOf(virtualFile) != null) return null;
        return new Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0),
                e -> "Run or debug " + virtualFile.getName());
    }
}
