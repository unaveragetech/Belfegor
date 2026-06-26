package adris.altoclef.macros;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MacroStorage {
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<MacroChain> macros = new ArrayList<>();
    private static File macroFile;

    public static void init(File configDir) {
        macroFile = new File(configDir, "belfegor_macros.json");
        load();
    }

    public static List<MacroChain> getMacros() { return macros; }

    public static MacroChain getMacro(String name) {
        for (MacroChain m : macros) {
            if (m.getName().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    public static void addMacro(MacroChain macro) {
        macros.add(macro);
        save();
    }

    public static void removeMacro(String name) {
        macros.removeIf(m -> m.getName().equalsIgnoreCase(name));
        save();
    }

    public static void save() {
        try {
            if (macroFile == null) return;
            List<Map<String, Object>> data = new ArrayList<>();
            for (MacroChain macro : macros) {
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("name", macro.getName());
                obj.put("description", macro.getDescription());
                obj.put("loop", macro.isLoop());
                obj.put("inputs", macro.getInputs());
                obj.put("outputs", macro.getOutputs());
                List<Map<String, Object>> steps = new ArrayList<>();
                for (MacroStep step : macro.getSteps()) {
                    Map<String, Object> stepObj = new LinkedHashMap<>();
                    stepObj.put("command", step.getCommand());
                    stepObj.put("description", step.getDescription());
                    stepObj.put("enabled", step.isEnabled());
                    stepObj.put("conditional", step.isConditional());
                    stepObj.put("conditionOutput", step.getConditionOutput());
                    stepObj.put("variables", step.getVariables());
                    steps.add(stepObj);
                }
                obj.put("steps", steps);
                data.add(obj);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(macroFile, data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void load() {
        macros.clear();
        if (macroFile == null || !macroFile.exists()) return;
        try {
            List<Map<String, Object>> data = mapper.readValue(macroFile,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> obj : data) {
                String name = (String) obj.getOrDefault("name", "Unnamed");
                String desc = (String) obj.getOrDefault("description", "");
                MacroChain macro = new MacroChain(name, desc);
                macro.setLoop((Boolean) obj.getOrDefault("loop", false));
                Map<String, String> inputs = (Map<String, String>) obj.get("inputs");
                if (inputs != null) macro.getInputs().putAll(inputs);
                Map<String, String> outputs = (Map<String, String>) obj.get("outputs");
                if (outputs != null) macro.getOutputs().putAll(outputs);
                List<Map<String, Object>> steps = (List<Map<String, Object>>) obj.get("steps");
                if (steps != null) {
                    for (Map<String, Object> stepObj : steps) {
                        MacroStep step = new MacroStep(
                                (String) stepObj.getOrDefault("command", ""),
                                (String) stepObj.getOrDefault("description", "")
                        );
                        step.setEnabled((Boolean) stepObj.getOrDefault("enabled", true));
                        step.setConditional((Boolean) stepObj.getOrDefault("conditional", false));
                        step.setConditionOutput((String) stepObj.get("conditionOutput"));
                        Map<String, String> vars = (Map<String, String>) stepObj.get("variables");
                        if (vars != null) step.getVariables().putAll(vars);
                        macro.addStep(step);
                    }
                }
                macros.add(macro);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
