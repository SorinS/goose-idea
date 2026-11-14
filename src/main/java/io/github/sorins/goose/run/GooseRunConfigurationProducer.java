package io.github.sorins.goose.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import io.github.sorins.goose.GooseCompiler;
import io.github.sorins.goose.GooseFiles;
import io.github.sorins.goose.lsp.GoosePackageRoots;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.annotations.NotNull;

/** "Run 'file.goose'" from a Goose file's editor or its node in the Project view. */
public final class GooseRunConfigurationProducer extends LazyRunConfigurationProducer<GooseRunConfiguration> {
    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return GooseConfigurationType.getInstance().factory();
    }

    static VirtualFile gooseFile(ConfigurationContext context) {
        if (context.getLocation() == null) return null;
        PsiElement element = context.getLocation().getPsiElement();
        VirtualFile file = null;
        if (element instanceof PsiFileSystemItem item) file = item.getVirtualFile();
        else {
            PsiFile psiFile = element.getContainingFile();
            if (psiFile != null) file = psiFile.getVirtualFile();
        }
        return GooseFiles.isGooseFile(file) && file.isInLocalFileSystem() ? file : null;
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull GooseRunConfiguration configuration,
                                                    @NotNull ConfigurationContext context,
                                                    @NotNull Ref<PsiElement> sourceElement) {
        VirtualFile file = gooseFile(context);
        // A module of the project's package has no main to run.
        if (file == null || GoosePackageRoots.getInstance(context.getProject()).moduleOf(file) != null) return false;
        GooseRunConfigurationOptions o = configuration.getOptions();
        o.setAction(GooseCompiler.Action.RUN);
        o.setFile(file.getPath());
        o.setProgramArguments(GooseSettings.getInstance(context.getProject()).getState().runArguments);
        configuration.setGeneratedName();
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull GooseRunConfiguration configuration, @NotNull ConfigurationContext context) {
        VirtualFile file = gooseFile(context);
        GooseRunConfigurationOptions o = configuration.getOptions();
        return file != null && o.getAction() == GooseCompiler.Action.RUN && file.getPath().equals(o.getFile());
    }
}
