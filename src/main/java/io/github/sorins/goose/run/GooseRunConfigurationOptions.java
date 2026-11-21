package io.github.sorins.goose.run;

import com.intellij.execution.configurations.LocatableRunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;
import io.github.sorins.goose.GooseCompiler;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a Goose run configuration stores. */
public final class GooseRunConfigurationOptions extends LocatableRunConfigurationOptions {
    private final StoredProperty<String> action = string(GooseCompiler.Action.RUN.name()).provideDelegate(this, "action");
    private final StoredProperty<String> file = string("").provideDelegate(this, "file");
    private final StoredProperty<String> programArguments = string("").provideDelegate(this, "programArguments");
    private final StoredProperty<String> workingDirectory = string("").provideDelegate(this, "workingDirectory");
    private final StoredProperty<Map<String, String>> environment = this.<String, String>map().provideDelegate(this, "environment");

    public GooseCompiler.Action getAction() {
        try {
            return GooseCompiler.Action.valueOf(action.getValue(this));
        } catch (RuntimeException e) {
            return GooseCompiler.Action.RUN;
        }
    }

    public void setAction(GooseCompiler.Action value) { action.setValue(this, value.name()); }

    public String getFile() { return nonNull(file.getValue(this)); }

    public void setFile(String value) { file.setValue(this, value); }

    public String getProgramArguments() { return nonNull(programArguments.getValue(this)); }

    public void setProgramArguments(String value) { programArguments.setValue(this, value); }

    public String getWorkingDirectory() { return nonNull(workingDirectory.getValue(this)); }

    public void setWorkingDirectory(String value) { workingDirectory.setValue(this, value); }

    /** The program's environment variables, added to the IDE's. */
    public Map<String, String> getEnvironment() {
        Map<String, String> value = environment.getValue(this);
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    public void setEnvironment(Map<String, String> value) {
        environment.setValue(this, value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value));
    }

    private static String nonNull(String s) { return s == null ? "" : s; }
}
