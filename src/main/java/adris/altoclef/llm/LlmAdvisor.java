package adris.altoclef.llm;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandDocumentation;
import adris.altoclef.debug.DebugLogger;
import adris.altoclef.memory.ShulkerMemory;
import adris.altoclef.util.helpers.WorldHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Local Ollama advisor for high-level command decisions.
 *
 * This does not replace Belfegor's task system. It gives the bot a bounded,
 * logged way to ask a local thinking/instruct model: "Given my context and
 * command catalogue, what Belfegor command should I run next?"
 */
public class LlmAdvisor {

    private static final LlmAdvisor INSTANCE = new LlmAdvisor();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER = "belfegor";

    private File _dir = new File(FOLDER);
    private File _commandsFile;
    private File _contextFile;
    private File _promptFile;
    private File _responseFile;
    private File _actionLogFile;
    private long _lastAutomaticRequestMs = 0;
    private CompletableFuture<AdvisorDecision> _pending;
    private String _lastAction = "none";
    private String _plannedAction = "normal player-mode fallback";
    private String _goal = "survive, learn, gather, craft, improve tools, manage shulkers, and build home base";
    private AdvisorDecision _lastDecision;

    public record AdvisorDecision(String command, String chat, String goal, String reason, boolean valid) {
    }

    public static LlmAdvisor getInstance() {
        return INSTANCE;
    }

    public synchronized void init(File gameDir) {
        try {
            _dir = new File(gameDir, FOLDER);
            _dir.mkdirs();
            _commandsFile = new File(_dir, "llm_commands.md");
            _contextFile = new File(_dir, "llm_context.json");
            _promptFile = new File(_dir, "llm_prompt.txt");
            _responseFile = new File(_dir, "llm_response.json");
            _actionLogFile = new File(_dir, "llm_actions.log");
            record("INIT", "LLM advisor initialized dir=" + _dir.getAbsolutePath());
        } catch (Exception e) {
            Debug.logWarning("[LLM] Failed to initialize advisor: " + e.getMessage());
        }
    }

    public synchronized void exportCommandCatalogue(AltoClef mod) {
        if (_commandsFile == null || mod == null || mod.getCommandExecutor() == null) return;
        try {
            String prefix = mod.getModSettings() == null ? "@" : mod.getModSettings().getCommandPrefix();
            Files.writeString(_commandsFile.toPath(),
                    CommandDocumentation.exportMarkdown(mod.getCommandExecutor().allCommands(), prefix),
                    StandardCharsets.UTF_8);
            record("COMMANDS", "Exported command catalogue to " + _commandsFile.getPath());
        } catch (Exception e) {
            record("ERROR", "Failed to export commands: " + e.getMessage());
        }
    }

    public synchronized void recordAction(String action, String reaction) {
        _lastAction = action == null || action.isBlank() ? _lastAction : action;
        record("ACTION", _lastAction + " reaction=" + (reaction == null ? "" : reaction));
    }

    public synchronized void setPlannedAction(String plannedAction) {
        if (plannedAction != null && !plannedAction.isBlank()) {
            _plannedAction = plannedAction;
        }
    }

    public synchronized void setGoal(String goal) {
        if (goal != null && !goal.isBlank()) {
            _goal = goal;
        }
    }

    public synchronized Optional<AdvisorDecision> pollDecision() {
        if (_pending == null || !_pending.isDone()) return Optional.empty();
        try {
            _lastDecision = _pending.getNow(null);
            _pending = null;
            if (_lastDecision != null) {
                record("DECISION", "command=" + _lastDecision.command
                        + " chat=" + _lastDecision.chat
                        + " valid=" + _lastDecision.valid
                        + " reason=" + _lastDecision.reason);
                return Optional.of(_lastDecision);
            }
        } catch (Exception e) {
            record("ERROR", "Decision failed: " + e.getMessage());
            _pending = null;
        }
        return Optional.empty();
    }

    public synchronized boolean requestAutomaticPlayerDecision(AltoClef mod, String phase, String fallback) {
        if (mod == null || mod.getModSettings() == null || !mod.getModSettings().isLlmAdvisorInPlayerMode()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (_pending != null && !_pending.isDone()) return false;
        if (now - _lastAutomaticRequestMs < mod.getModSettings().getLlmAdvisorCooldownSeconds() * 1000L) {
            return false;
        }
        _lastAutomaticRequestMs = now;
        setPlannedAction(fallback);
        return requestDecision(mod, "player_mode", "phase=" + phase + " fallback=" + fallback, true);
    }

    public synchronized boolean requestChatDecision(AltoClef mod, String prompt) {
        if (mod == null || mod.getModSettings() == null || !mod.getModSettings().canLlmAdvisorChat()) {
            return false;
        }
        if (_pending != null && !_pending.isDone()) return false;
        return requestDecision(mod, "chat", prompt, false);
    }

    private synchronized boolean requestDecision(AltoClef mod, String mode, String userPrompt, boolean commandRequired) {
        try {
            if (!isConfigured(mod)) {
                record("SKIP", "LLM advisor disabled or missing Ollama model");
                return false;
            }
            exportCommandCatalogue(mod);
            writeContext(mod, mode, userPrompt, commandRequired);
            writePrompt(mode, userPrompt, commandRequired);
            record("REQUEST", "mode=" + mode + " prompt=" + userPrompt);
            _pending = CompletableFuture.supplyAsync(() -> runAdvisorProcess(mod, commandRequired));
            return true;
        } catch (Exception e) {
            record("ERROR", "requestDecision failed: " + e.getMessage());
            return false;
        }
    }

    private boolean isConfigured(AltoClef mod) {
        return mod.getModSettings().isLlmAdvisorEnabled()
                && !mod.getModSettings().getLlmOllamaModel().isBlank();
    }

    private AdvisorDecision runAdvisorProcess(AltoClef mod, boolean commandRequired) {
        try {
            List<String> command = List.of(
                    mod.getModSettings().getLlmOllamaExecutable(),
                    "run",
                    mod.getModSettings().getLlmOllamaModel()
            );
            Process process = new ProcessBuilder(command)
                    .directory(_dir)
                    .redirectErrorStream(true)
                    .start();
            String prompt = Files.readString(_promptFile.toPath(), StandardCharsets.UTF_8);
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(prompt);
                writer.flush();
            }
            boolean finished = process.waitFor(mod.getModSettings().getLlmAdvisorTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new AdvisorDecision("", "", _goal, "Ollama timed out", false);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            record("PROCESS", "exit=" + process.exitValue() + " output=" + output.replace('\n', ' ').trim());
            String json = extractJsonObject(stripAnsi(output));
            Files.writeString(_responseFile.toPath(), json, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = GSON.fromJson(json, Map.class);
            String commandText = cleanString(parsed.get("command"));
            String chat = cleanString(parsed.get("chat"));
            String goal = cleanString(parsed.get("goal"));
            String reason = cleanString(parsed.get("reason"));
            boolean valid = !commandRequired || isAllowedCommand(mod, commandText);
            if (commandRequired && !valid) {
                reason = "rejected invalid command: " + commandText + "; " + reason;
                commandText = "";
            }
            return new AdvisorDecision(commandText, chat, goal, reason, valid);
        } catch (Exception e) {
            return new AdvisorDecision("", "", _goal, "advisor process error: " + e.getMessage(), false);
        }
    }

    private String extractJsonObject(String output) {
        if (output == null || output.isBlank()) {
            return "{\"command\":\"\",\"chat\":\"\",\"goal\":\"\",\"reason\":\"empty Ollama output\"}";
        }
        int commandKey = output.lastIndexOf("\"command\"");
        if (commandKey < 0) {
            commandKey = output.lastIndexOf("'command'");
        }
        int start = commandKey < 0 ? output.lastIndexOf('{') : output.lastIndexOf('{', commandKey);
        int end = commandKey < 0 ? output.lastIndexOf('}') : output.indexOf('}', commandKey);
        if (start < 0 || end <= start) {
            return "{\"command\":\"\",\"chat\":\"\",\"goal\":\"\",\"reason\":\"model did not return JSON\"}";
        }
        return output.substring(start, end + 1);
    }

    private String stripAnsi(String value) {
        return value == null ? "" : value.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
    }

    private boolean isAllowedCommand(AltoClef mod, String commandText) {
        if (commandText == null || commandText.isBlank()) return false;
        String prefix = mod.getModSettings().getCommandPrefix();
        String line = commandText.trim();
        if (!line.startsWith(prefix)) return false;
        String withoutPrefix = line.substring(prefix.length()).trim();
        if (withoutPrefix.isBlank()) return false;
        String name = withoutPrefix.split("\\s+", 2)[0];
        Command command = mod.getCommandExecutor().get(name);
        if (command == null) return false;
        Set<String> denied = Set.of("stop", "reload_settings", "craftaudit", "test", "ai", "player");
        return !denied.contains(name.toLowerCase(Locale.ROOT));
    }

    private void writeContext(AltoClef mod, String mode, String prompt, boolean commandRequired) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("time", Instant.now().toString());
        context.put("mode", mode);
        context.put("goal", _goal);
        context.put("last_action", _lastAction);
        context.put("planned_action", _plannedAction);
        context.put("user_prompt", prompt);
        context.put("command_required", commandRequired);
        context.put("player", buildPlayerContext(mod));
        context.put("inventory", buildInventoryContext(mod));
        context.put("shulkers", buildShulkerContext());
        context.put("commands_file", _commandsFile == null ? "" : _commandsFile.getAbsolutePath());
        Files.writeString(_contextFile.toPath(), GSON.toJson(context), StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildPlayerContext(AltoClef mod) {
        Map<String, Object> player = new LinkedHashMap<>();
        if (mod.getPlayer() == null) return player;
        player.put("x", mod.getPlayer().getBlockX());
        player.put("y", mod.getPlayer().getBlockY());
        player.put("z", mod.getPlayer().getBlockZ());
        player.put("dimension", WorldHelper.getCurrentDimension().name());
        player.put("health", mod.getPlayer().getHealth());
        player.put("hunger", mod.getPlayer().getHungerManager().getFoodLevel());
        player.put("on_ground", mod.getPlayer().isOnGround());
        player.put("touching_water", mod.getPlayer().isTouchingWater());
        player.put("home_base", String.valueOf(mod.getModSettings().getHomeBasePosition()));
        return player;
    }

    private Map<String, Integer> buildInventoryContext(AltoClef mod) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        if (mod.getPlayer() == null) return inventory;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            inventory.merge(id, stack.getCount(), Integer::sum);
        }
        return inventory;
    }

    private List<Map<String, Object>> buildShulkerContext() {
        List<Map<String, Object>> shulkers = new ArrayList<>();
        for (ShulkerMemory.ShulkerEntry entry : ShulkerMemory.getInstance().getAllShulkers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("location", entry.location);
            map.put("inventory_slot", entry.inventorySlot);
            map.put("pos", entry.x + "," + entry.y + "," + entry.z);
            map.put("item", entry.shulkerItem);
            map.put("contents", entry.contents.stream()
                    .collect(Collectors.toMap(i -> i.itemName, i -> i.count, Integer::sum, LinkedHashMap::new)));
            shulkers.add(map);
        }
        return shulkers;
    }

    private void writePrompt(String mode, String userPrompt, boolean commandRequired) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Belfegor's local Minecraft planning advisor running through Ollama.\n");
        prompt.append("You must reason from the JSON context and command catalogue files.\n");
        prompt.append("Return ONLY compact JSON with keys: command, chat, goal, reason.\n");
        if (commandRequired) {
            prompt.append("The command must be one legal Belfegor command from llm_commands.md and must start with @.\n");
        } else {
            prompt.append("If no command is needed, command may be empty and chat should answer the user.\n");
        }
        prompt.append("Never invent unsupported commands. Prefer safe survival, inventory recovery, using known shulkers, and simple useful goals.\n\n");
        prompt.append("Context file: ").append(_contextFile.getAbsolutePath()).append("\n");
        prompt.append("Command catalogue: ").append(_commandsFile.getAbsolutePath()).append("\n");
        prompt.append("Mode: ").append(mode).append("\n");
        prompt.append("Prompt: ").append(userPrompt).append("\n");
        prompt.append("Recent action log: ").append(_actionLogFile.getAbsolutePath()).append("\n");
        prompt.append("\nJSON response only:\n");
        Files.writeString(_promptFile.toPath(), prompt.toString(), StandardCharsets.UTF_8);
    }

    private String cleanString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private synchronized void record(String category, String message) {
        try {
            if (_actionLogFile != null) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(_actionLogFile, true))) {
                    writer.write(Instant.now() + " [" + category + "] " + message);
                    writer.newLine();
                }
            }
            DebugLogger.getInstance().log("LLM-" + category, message);
        } catch (Exception ignored) {
        }
    }
}
