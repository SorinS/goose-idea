package io.github.sorins.goose.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import io.github.sorins.goose.GooseFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The Goose run configuration type: run, check, build or generate C for a program. */
public final class GooseConfigurationType extends ConfigurationTypeBase {
    public static final String ID = "GooseRunConfiguration";

    public GooseConfigurationType() {
        super(ID, "Goose", "Run, check or build a Goose program with the goose compiler", GooseFiles.ICON);
        addFactory(new Factory(this));
    }

    public static GooseConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(GooseConfigurationType.class);
    }

    public ConfigurationFactory factory() {
        return getConfigurationFactories()[0];
    }

    static final class Factory extends ConfigurationFactory {
        Factory(GooseConfigurationType type) {
            super(type);
        }

        @Override
        public @NotNull String getId() {
            return ID;
        }

        @Override
        public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new GooseRunConfiguration(project, this, "Goose");
        }

        @Override
        public @Nullable Class<? extends BaseState> getOptionsClass() {
            return GooseRunConfigurationOptions.class;
        }
    }
}
