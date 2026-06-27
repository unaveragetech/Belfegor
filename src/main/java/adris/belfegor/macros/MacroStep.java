package adris.belfegor.macros;

import java.util.LinkedHashMap;
import java.util.Map;

public class MacroStep {
    private String command;
    private String description;
    private boolean enabled;
    private boolean conditional;
    private String conditionOutput;
    private final Map<String, String> variables;

    public MacroStep(String command, String description) {
        this.command = command;
        this.description = description;
        this.enabled = true;
        this.conditional = false;
        this.conditionOutput = null;
        this.variables = new LinkedHashMap<>();
    }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isConditional() { return conditional; }
    public void setConditional(boolean conditional) { this.conditional = conditional; }
    public String getConditionOutput() { return conditionOutput; }
    public void setConditionOutput(String conditionOutput) { this.conditionOutput = conditionOutput; }
    public Map<String, String> getVariables() { return variables; }

    public void setVariable(String key, String value) {
        variables.put(key, value);
    }

    public MacroStep duplicate() {
        MacroStep copy = new MacroStep(command, description);
        copy.enabled = this.enabled;
        copy.conditional = this.conditional;
        copy.conditionOutput = this.conditionOutput;
        copy.variables.putAll(this.variables);
        return copy;
    }

    public String toString() {
        String prefix = enabled ? "" : "[DISABLED] ";
        String cond = conditional ? "? " : "";
        return prefix + cond + (description != null && !description.isEmpty() ? description + ": " : "") + command;
    }
}
