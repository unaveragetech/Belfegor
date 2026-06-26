package adris.altoclef.macros;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MacroChain {
    private String name;
    private String description;
    private final List<MacroStep> steps;
    private final Map<String, String> inputs;
    private final Map<String, String> outputs;
    private boolean loop;

    public MacroChain(String name, String description) {
        this.name = name;
        this.description = description;
        this.steps = new ArrayList<>();
        this.inputs = new LinkedHashMap<>();
        this.outputs = new LinkedHashMap<>();
        this.loop = false;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<MacroStep> getSteps() { return steps; }
    public Map<String, String> getInputs() { return inputs; }
    public Map<String, String> getOutputs() { return outputs; }
    public boolean isLoop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }

    public void addStep(MacroStep step) {
        steps.add(step);
    }

    public void removeStep(int index) {
        if (index >= 0 && index < steps.size()) {
            steps.remove(index);
        }
    }

    public void moveStep(int from, int to) {
        if (from >= 0 && from < steps.size() && to >= 0 && to < steps.size()) {
            MacroStep step = steps.remove(from);
            steps.add(to, step);
        }
    }

    public void addInput(String name, String defaultValue) {
        inputs.put(name, defaultValue);
    }

    public void addOutput(String name, String description) {
        outputs.put(name, description);
    }

    public String resolveCommand(MacroStep step, Map<String, String> providedInputs) {
        String cmd = step.getCommand();
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            String value = providedInputs.getOrDefault(entry.getKey(), entry.getValue());
            cmd = cmd.replace("{" + entry.getKey() + "}", value);
        }
        for (Map.Entry<String, String> entry : step.getVariables().entrySet()) {
            cmd = cmd.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return cmd;
    }

    public MacroChain duplicate(String newName) {
        MacroChain copy = new MacroChain(newName, description);
        copy.loop = this.loop;
        for (MacroStep step : steps) {
            copy.addStep(step.duplicate());
        }
        copy.inputs.putAll(this.inputs);
        copy.outputs.putAll(this.outputs);
        return copy;
    }
}
