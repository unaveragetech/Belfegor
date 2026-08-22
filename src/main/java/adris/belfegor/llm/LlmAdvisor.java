package adris.belfegor.llm;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandDocumentation;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.ErrandMemory;
import adris.belfegor.memory.GamePlanMemory;
import adris.belfegor.memory.ShulkerMemory;
import adris.belfegor.memory.SpatialAwareness;
import adris.belfegor.util.helpers.WorldHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Local llama.cpp advisor for high-level command decisions.
 *
 * This does not replace Belfegor's task system. It gives the bot a bounded,
 * logged way to ask a local thinking/instruct model: "Given my context and
 * command catalogue, what Belfegor command should I run next?"
 */
public class LlmAdvisor {

    private static final LlmAdvisor INSTANCE = new LlmAdvisor();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER = "belfegor";
    private static final long REPEATED_ACTION_LOG_COOLDOWN_MS = 5_000L;
    private static final long FAILED_REQUEST_BACKOFF_MS = 15_000L;
    private static final long TASK_COMPLETION_REQUEST_MIN_INTERVAL_MS = 10_000L;
    private static final int MAX_RECENT_DECISIONS = 6;
    private static final int MAX_EXCHANGES = 25;

    private File _dir = new File(FOLDER);
    private File _commandsFile;
    private File _commandsJsonFile;
    private File _contextFile;
    private File _promptFile;
    private File _responseFile;
    private File _actionLogFile;
    private long _lastAutomaticRequestMs = 0;
    private long _lastFailedRequestMs = 0;
    private CompletableFuture<AdvisorDecision> _pending;
    private String _lastAction = "none";
    private String _lastRecordedActionLine = "";
    private long _lastRecordedActionLineMs = 0;
    private String _plannedAction = "normal player-mode fallback";
    private String _goal = "survive, learn, gather, craft, improve tools, manage shulkers, and build home base";
    private String _taskStatus = "unknown";
    private String _lastExecutedCommand = "none";
    private final List<String> _recentDecisions = new ArrayList<>();
    private final List<AiExchange> _exchanges = new ArrayList<>();
    private final ExecutorService _executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "belfegor-llm-advisor");
        thread.setDaemon(true);
        return thread;
    });
    private String _lastRequestMode = "chat";
    private String _lastRequestPrompt = "";
    private String _status = "idle";
    private String _lastError = "";
    private AdvisorDecision _lastDecision;

    public record AdvisorDecision(String command, String chat, String goal, String reason, boolean valid) {
    }

    public record AiExchange(String mode, String prompt, String chat, String command,
                             String reason, boolean valid, long timestamp) {
    }

    public static LlmAdvisor getInstance() {
        return INSTANCE;
    }

    public synchronized void init(File gameDir) {
        try {
            _dir = new File(gameDir, FOLDER);
            _dir.mkdirs();
            _commandsFile = new File(_dir, "llm_commands.md");
            _commandsJsonFile = new File(_dir, "llm_commands.json");
            _contextFile = new File(_dir, "llm_context.json");
            _promptFile = new File(_dir, "llm_prompt.txt");
            _responseFile = new File(_dir, "llm_response.json");
            _actionLogFile = new File(_dir, "llm_actions.log");
            record("INIT", "LLM advisor initialized dir=" + _dir.getAbsolutePath());
        } catch (Exception e) {
            Debug.logWarning("[LLM] Failed to initialize advisor: " + e.getMessage());
        }
    }

    public synchronized void exportCommandCatalogue(Belfegor mod) {
        if (mod == null || mod.getCommandExecutor() == null) return;
        try {
            String prefix = mod.getModSettings() == null ? "@" : mod.getModSettings().getCommandPrefix();
            if (_commandsFile != null) {
                Files.writeString(_commandsFile.toPath(),
                        CommandDocumentation.exportMarkdown(mod.getCommandExecutor().allCommands(), prefix),
                        StandardCharsets.UTF_8);
            }
            if (_commandsJsonFile != null) {
                Files.writeString(_commandsJsonFile.toPath(),
                        CommandDocumentation.exportJson(mod.getCommandExecutor().allCommands(), prefix),
                        StandardCharsets.UTF_8);
            }
            record("COMMANDS", "Exported command catalogue to "
                    + (_commandsJsonFile == null ? "" : _commandsJsonFile.getPath()));
        } catch (Exception e) {
            record("ERROR", "Failed to export commands: " + e.getMessage());
        }
    }

    public synchronized void recordAction(String action, String reaction) {
        _lastAction = action == null || action.isBlank() ? _lastAction : action;
        String line = _lastAction + " reaction=" + (reaction == null ? "" : reaction);
        long now = System.currentTimeMillis();
        if (line.equals(_lastRecordedActionLine)
                && now - _lastRecordedActionLineMs < REPEATED_ACTION_LOG_COOLDOWN_MS) {
            return;
        }
        _lastRecordedActionLine = line;
        _lastRecordedActionLineMs = now;
        record("ACTION", line);
    }

    public synchronized void setPlannedAction(String plannedAction) {
        if (plannedAction != null && !plannedAction.isBlank()) {
            _plannedAction = plannedAction;
        }
    }

    /** Live status of whatever the bot is currently doing (used by @player). */
    public synchronized void setTaskStatus(String status) {
        if (status != null && !status.isBlank()) {
            _taskStatus = status;
        }
    }

    /**
     * Called when a bot task finishes. Lets the advisor be asked again shortly
     * after completion so the AI can chain the next goal command, but never
     * more often than the minimum interval.
     */
    public synchronized void onTaskCompleted() {
        long now = System.currentTimeMillis();
        if (_lastAutomaticRequestMs != 0
                && now - _lastAutomaticRequestMs >= TASK_COMPLETION_REQUEST_MIN_INTERVAL_MS) {
            _lastAutomaticRequestMs = 0;
        }
    }

    public synchronized void recordCommandExecuted(String command) {
        if (command != null && !command.isBlank()) {
            _lastExecutedCommand = command;
        }
    }

    public synchronized void setGoal(String goal) {
        if (goal != null && !goal.isBlank()) {
            _goal = goal;
        }
    }

    public synchronized Optional<AdvisorDecision> pollDecision() {
        if (_pending == null || !_pending.isDone()) return Optional.empty();
        return pollCompleted();
    }

    /**
     * Consumes a completed decision only when the pending request was a chat
     * request (from @ai). Keeps @player from stealing the @ai answer.
     */
    public synchronized Optional<AdvisorDecision> pollChatDecision() {
        if (_pending == null || !"chat".equals(_lastRequestMode)) return Optional.empty();
        return pollCompleted();
    }

    /**
     * Consumes a completed decision only when the pending request was an
     * automatic @player-mode request.
     */
    public synchronized Optional<AdvisorDecision> pollPlayerDecision() {
        if (_pending == null || !"player_mode".equals(_lastRequestMode)) return Optional.empty();
        return pollCompleted();
    }

    private Optional<AdvisorDecision> pollCompleted() {
        try {
            _lastDecision = _pending.getNow(null);
            _pending = null;
            if (_lastDecision != null) {
                rememberDecision(_lastDecision);
                recordExchange(_lastRequestMode, _lastRequestPrompt, _lastDecision);
                _status = "idle";
                record("DECISION", "command=" + _lastDecision.command
                        + " chat=" + _lastDecision.chat
                        + " valid=" + _lastDecision.valid
                        + " reason=" + _lastDecision.reason);
                return Optional.of(_lastDecision);
            }
        } catch (Exception e) {
            record("ERROR", "Decision failed: " + e.getMessage());
            _status = "idle";
            _lastError = e.getMessage() == null ? "unknown" : e.getMessage();
            _pending = null;
        }
        return Optional.empty();
    }

    /** Advisor worker status: idle, running, done, or error. */
    public synchronized String getStatus() {
        return _status;
    }

    public synchronized String getLastError() {
        return _lastError;
    }

    public synchronized void recordExchange(String mode, String prompt,
                                            String chat, String command,
                                            String reason, boolean valid) {
        _exchanges.add(new AiExchange(
                mode == null ? "chat" : mode,
                prompt == null ? "" : prompt,
                chat == null ? "" : chat,
                command == null ? "" : command,
                reason == null ? "" : reason,
                valid,
                System.currentTimeMillis()));
        while (_exchanges.size() > MAX_EXCHANGES) {
            _exchanges.remove(0);
        }
    }

    public synchronized void recordExchange(String mode, String prompt, AdvisorDecision decision) {
        if (decision == null) return;
        recordExchange(mode, prompt, decision.chat(), decision.command(),
                decision.reason(), decision.valid());
    }

    public synchronized List<AiExchange> getExchanges() {
        return new ArrayList<>(_exchanges);
    }

    /** True when the most recent pending/queued request was a chat request. */
    public synchronized boolean hasChatRequest() {
        return _pending != null && "chat".equals(_lastRequestMode);
    }

    private void rememberDecision(AdvisorDecision decision) {
        if (decision == null) return;
        _recentDecisions.add("command=" + decision.command
                + " chat=" + decision.chat
                + " reason=" + decision.reason
                + " valid=" + decision.valid);
        while (_recentDecisions.size() > MAX_RECENT_DECISIONS) {
            _recentDecisions.remove(0);
        }
    }

    public synchronized boolean requestAutomaticPlayerDecision(Belfegor mod, String phase, String fallback) {
        if (mod == null || mod.getModSettings() == null || !mod.getModSettings().isLlmAdvisorInPlayerMode()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (_pending != null && !_pending.isDone()) return false;
        if (_lastAutomaticRequestMs != 0
                && now - _lastAutomaticRequestMs < mod.getModSettings().getLlmAdvisorCooldownSeconds() * 1000L) {
            return false;
        }
        // Failed requests (missing model/executable, timeout) retry on a short
        // backoff instead of eating the full cooldown, but never hammer llama.
        if (_lastFailedRequestMs != 0 && now - _lastFailedRequestMs < FAILED_REQUEST_BACKOFF_MS) {
            return false;
        }
        setPlannedAction(fallback);
        boolean requested = requestDecision(mod, "player_mode",
                "phase=" + phase + " taskStatus=" + _taskStatus + " fallback=" + fallback, true);
        if (requested) {
            _lastAutomaticRequestMs = now;
            _lastFailedRequestMs = 0;
        } else {
            _lastFailedRequestMs = now;
        }
        return requested;
    }

    public synchronized boolean requestChatDecision(Belfegor mod, String prompt) {
        if (mod == null || mod.getModSettings() == null || !mod.getModSettings().canLlmAdvisorChat()) {
            return false;
        }
        if (_pending != null && !_pending.isDone()) return false;
        return requestDecision(mod, "chat", prompt, false);
    }

    public synchronized String availabilityReport(Belfegor mod) {
        if (mod == null || mod.getModSettings() == null) {
            return "settings unavailable";
        }
        File executable = resolveLlamaExecutable(mod);
        File model = resolveGameFile(mod.getModSettings().getLlmLlamaModelPath());
        return "enabled=" + mod.getModSettings().isLlmAdvisorEnabled()
                + " executable=" + executable.getAbsolutePath()
                + " executableExists=" + executable.exists()
                + " model=" + model.getAbsolutePath()
                + " modelExists=" + model.exists();
    }

    private synchronized boolean requestDecision(Belfegor mod, String mode, String userPrompt, boolean commandRequired) {
        try {
            if (!isConfigured(mod)) {
                record("SKIP", "LLM advisor disabled or missing model path; " + availabilityReport(mod));
                return false;
            }
            _lastRequestMode = mode == null ? "chat" : mode;
            _lastRequestPrompt = userPrompt == null ? "" : userPrompt;
            exportCommandCatalogue(mod);
            writeContext(mod, mode, userPrompt, commandRequired);
            writePrompt(mode, userPrompt, commandRequired);
            record("REQUEST", "mode=" + mode + " prompt=" + userPrompt);
            _status = "running";
            _lastError = "";
            _pending = CompletableFuture.supplyAsync(
                    () -> runAdvisorProcess(mod, commandRequired), _executor);
            return true;
        } catch (Exception e) {
            record("ERROR", "requestDecision failed: " + e.getMessage());
            _status = "idle";
            _lastError = e.getMessage() == null ? "unknown" : e.getMessage();
            return false;
        }
    }

    private boolean isConfigured(Belfegor mod) {
        return mod.getModSettings().isLlmAdvisorEnabled()
                && !mod.getModSettings().getLlmLlamaModelPath().isBlank();
    }

    private AdvisorDecision runAdvisorProcess(Belfegor mod, boolean commandRequired) {
        try {
            File executable = resolveLlamaExecutable(mod);
            File model = resolveGameFile(mod.getModSettings().getLlmLlamaModelPath());
            if (!executable.exists()) {
                record("SKIP", "llama.cpp executable not found; " + availabilityReport(mod));
                return new AdvisorDecision("", "", _goal,
                        "llama.cpp executable not found: " + executable.getAbsolutePath(), false);
            }
            if (!model.exists()) {
                record("SKIP", "llama.cpp model not found; " + availabilityReport(mod));
                return new AdvisorDecision("", "", _goal,
                        "llama.cpp model not found: " + model.getAbsolutePath(), false);
            }
            List<String> command = List.of(
                    executable.getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "-c", String.valueOf(mod.getModSettings().getLlmContextSize()),
                    "-n", String.valueOf(mod.getModSettings().getLlmMaxTokens()),
                    "-t", String.valueOf(mod.getModSettings().getLlmMaxThreads()),
                    "-b", String.valueOf(mod.getModSettings().getLlmBatchSize()),
                    "--temp", "0.2",
                    // Single turn: generate once and exit. Without this flag the
                    // bundled llama.cpp build stays in its REPL waiting on stdin,
                    // so the advisor always timed out and never returned output.
                    "-st",
                    // Qwen3-style models default to verbose chain-of-thought that
                    // eats the token budget before any JSON is produced.
                    "--reasoning", "off",
                    "-f", _promptFile.getAbsolutePath()
            );
            Process process = new ProcessBuilder(command)
                    .directory(_dir)
                    .redirectErrorStream(true)
                    .start();
            // Close stdin so the single-turn run cannot block waiting for the
            // next conversation input even if the flag set changes.
            process.getOutputStream().close();
            boolean finished = process.waitFor(mod.getModSettings().getLlmAdvisorTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                _lastError = "llama.cpp timed out after "
                        + mod.getModSettings().getLlmAdvisorTimeoutSeconds() + "s";
                return new AdvisorDecision("", "", _goal, "llama.cpp timed out", false);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            record("PROCESS", "exit=" + process.exitValue() + " output=" + output.replace('\n', ' ').trim());
            String json = extractJsonObject(output);
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
            _lastError = e.getMessage() == null ? "unknown" : e.getMessage();
            return new AdvisorDecision("", "", _goal, "advisor process error: " + e.getMessage(), false);
        }
    }

    /**
     * Robustly turns whatever the model returned into a JSON object. The model
     * often wraps JSON in prose, markdown fences, or reasoning tags, so we:
     *   1) strip ANSI and code fences,
     *   2) extract the first balanced JSON object and validate it,
     *   3) fall back to locating the "command" key region,
     *   4) as a last resort rebuild JSON from loose key:value lines.
     */
    private String extractJsonObject(String output) {
        String cleaned = stripAnsi(output);
        if (cleaned == null || cleaned.isBlank()) {
            return "{\"command\":\"\",\"chat\":\"\",\"goal\":\"\",\"reason\":\"empty llama.cpp output\"}";
        }
        cleaned = cleaned.replaceAll("(?s)```[a-zA-Z]*", "").replace("```", "");

        // The CLI echoes the prompt (including any JSON examples) before the
        // generation, so iterate every balanced JSON object and keep the LAST
        // one that parses: that is the model's actual answer.
        String best = null;
        int searchFrom = 0;
        while (searchFrom < cleaned.length()) {
            int start = cleaned.indexOf('{', searchFrom);
            if (start < 0) break;
            String candidate = balancedJsonFrom(cleaned, start);
            if (candidate != null) {
                try {
                    GSON.fromJson(candidate, Map.class);
                    best = candidate;
                } catch (Exception ignored) {
                }
            }
            searchFrom = start + 1;
        }
        if (best != null) {
            return best;
        }

        int commandKey = cleaned.lastIndexOf("\"command\"");
        if (commandKey < 0) {
            commandKey = cleaned.lastIndexOf("'command'");
        }
        int start = commandKey < 0 ? cleaned.indexOf('{') : cleaned.lastIndexOf('{', commandKey);
        int end = commandKey < 0 ? cleaned.lastIndexOf('}') : cleaned.indexOf('}', commandKey);
        if (start >= 0 && end > start) {
            String candidate = cleaned.substring(start, end + 1);
            try {
                GSON.fromJson(candidate, Map.class);
                return candidate;
            } catch (Exception ignored) {
            }
        }
        return repairJsonFromLines(cleaned);
    }

    private String balancedJsonFrom(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /** Builds a valid JSON object from loose "key: value" lines. */
    private String repairJsonFromLines(String text) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (String key : List.of("command", "chat", "goal", "reason")) {
            String value = extractValue(text, key);
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(key).append("\":\"")
                    .append(escapeJson(value)).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    private String extractValue(String text, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)[\"']?" + key + "[\"']?\\s*[:=]\\s*[\"']?([^\"'\\n,}]+)[\"']?");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ");
    }

    private String stripAnsi(String value) {
        return value == null ? "" : value.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
    }

    private boolean isAllowedCommand(Belfegor mod, String commandText) {
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

    private void writeContext(Belfegor mod, String mode, String prompt, boolean commandRequired) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("time", Instant.now().toString());
        context.put("mode", mode);
        context.put("goal", _goal);
        context.put("last_action", _lastAction);
        context.put("planned_action", _plannedAction);
        context.put("task_status", _taskStatus);
        context.put("last_executed_command", _lastExecutedCommand);
        context.put("recent_decisions", new ArrayList<>(_recentDecisions));
        context.put("user_prompt", prompt);
        context.put("command_required", commandRequired);
        context.put("player", buildPlayerContext(mod));
        context.put("inventory", buildInventoryContext(mod));
        context.put("stored_at_base", buildStoredContext(mod));
        context.put("errands", buildErrandContext(mod));
        context.put("game_plan", buildGamePlanContext());
        context.put("shulkers", buildShulkerContext());
        context.put("base_memory", buildBaseContext());
        context.put("spatial_awareness", buildSpatialContext());
        context.put("commands_file", _commandsFile == null ? "" : _commandsFile.getAbsolutePath());
        Files.writeString(_contextFile.toPath(), GSON.toJson(context), StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildPlayerContext(Belfegor mod) {
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

    private Map<String, Integer> buildInventoryContext(Belfegor mod) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        if (mod.getPlayer() == null) return inventory;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            inventory.merge(id, stack.getCount(), Integer::sum);
        }
        return inventory;
    }

    /** Known supplies stored in the home storage network (ledger, not live). */
    private Map<String, Integer> buildStoredContext(Belfegor mod) {
        Map<String, Integer> stored = new LinkedHashMap<>();
        if (mod == null || mod.getModSettings() == null) return stored;
        BlockPos home = mod.getModSettings().getHomeBasePosition();
        if (home == null) return stored;
        BaseStorageMemory.StorageNetwork network = BaseStorageMemory.getInstance()
                .networkFor(home, WorldHelper.getCurrentDimension().name());
        if (network != null && network.knownCounts != null) {
            stored.putAll(network.knownCounts);
        }
        return stored;
    }

    /** Outstanding stash errands: supplies gathered earlier and stored at a chest. */
    private List<Map<String, Object>> buildErrandContext(Belfegor mod) {
        List<Map<String, Object>> errands = new ArrayList<>();
        for (ErrandMemory.Errand errand : ErrandMemory.getInstance().getAll()) {
            if (errand == null || errand.remaining <= 0) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("item", errand.item);
            map.put("remaining", errand.remaining);
            map.put("status", errand.status);
            map.put("source", errand.source);
            map.put("chest", errand.chestX + "," + errand.chestY + "," + errand.chestZ);
            errands.add(map);
        }
        return errands;
    }

    /** Persistent long-term game plan stages and their status. */
    private List<Map<String, Object>> buildGamePlanContext() {
        GamePlanMemory memory = GamePlanMemory.getInstance();
        memory.ensureStages();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (GamePlanMemory.GameStage stage : memory.getStages()) {
            if (stage == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", stage.id);
            map.put("name", stage.name);
            map.put("status", stage.status);
            map.put("description", stage.description);
            map.put("note", stage.note);
            stages.add(map);
        }
        return stages;
    }

    private List<Map<String, Object>> buildShulkerContext() {
        List<Map<String, Object>> shulkers = new ArrayList<>();
        for (ShulkerMemory.ShulkerEntry entry : ShulkerMemory.getInstance().getAllShulkers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("location", entry.location);
            map.put("inventory_slot", entry.inventorySlot);
            map.put("pos", entry.x + "," + entry.y + "," + entry.z);
            map.put("item", entry.shulkerItem);
            map.put("source_key", entry.sourceKey);
            map.put("fingerprint", entry.fingerprint);
            map.put("slot_count", entry.slotCount);
            map.put("free_slots", entry.freeSlots);
            map.put("total_items", entry.totalItems);
            map.put("last_verified_source", entry.lastVerifiedSource);
            map.put("contents", entry.contents.stream()
                    .collect(Collectors.toMap(i -> i.itemName, i -> i.count, Integer::sum, LinkedHashMap::new)));
            List<Map<String, Object>> slots = new ArrayList<>();
            for (ShulkerMemory.ShulkerSlotItem slot : entry.slots) {
                Map<String, Object> slotMap = new LinkedHashMap<>();
                slotMap.put("slot", slot.slot);
                slotMap.put("item", slot.itemName);
                slotMap.put("item_key", slot.itemKey);
                slotMap.put("count", slot.count);
                slots.add(slotMap);
            }
            map.put("slots", slots);
            shulkers.add(map);
        }
        return shulkers;
    }

    private List<Map<String, Object>> buildBaseContext() {
        List<Map<String, Object>> bases = new ArrayList<>();
        for (BaseMemory.BaseRecord base : BaseMemory.getInstance().getAllBases()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", base.id);
            map.put("dimension", base.dimension);
            map.put("center", base.x + "," + base.y + "," + base.z);
            map.put("radius", base.radius);
            map.put("wall_height", base.wallHeight);
            map.put("exterior_clearance", base.exteriorClearance);
            map.put("status", base.status);
            List<Map<String, Object>> modules = new ArrayList<>();
            for (BaseMemory.BaseModule module : base.modules) {
                Map<String, Object> moduleMap = new LinkedHashMap<>();
                moduleMap.put("name", module.name);
                moduleMap.put("type", module.type);
                moduleMap.put("anchor", module.x + "," + module.y + "," + module.z);
                moduleMap.put("center", module.centerX + "," + module.centerY + "," + module.centerZ);
                moduleMap.put("size", module.width + "x" + module.depth + "x" + module.height);
                moduleMap.put("progress", module.progressDone + "/" + module.progressTotal);
                moduleMap.put("status", module.status);
                moduleMap.put("note", module.note);
                modules.add(moduleMap);
            }
            map.put("modules", modules);
            List<Map<String, Object>> inspections = new ArrayList<>();
            for (BaseMemory.BaseInspection inspection : base.inspections) {
                Map<String, Object> inspectionMap = new LinkedHashMap<>();
                inspectionMap.put("module", inspection.module);
                inspectionMap.put("type", inspection.type);
                inspectionMap.put("checked", inspection.checked);
                inspectionMap.put("blocked", inspection.blocked);
                inspectionMap.put("missing", inspection.missing);
                inspectionMap.put("complete", inspection.complete);
                inspectionMap.put("status", inspection.status);
                inspectionMap.put("note", inspection.note);
                inspections.add(inspectionMap);
            }
            map.put("inspections", inspections);
            bases.add(map);
        }
        return bases;
    }

    private Map<String, Object> buildSpatialContext() {
        SpatialAwareness.SpatialSnapshot snapshot = SpatialAwareness.getInstance().lastSnapshot;
        Map<String, Object> map = new LinkedHashMap<>();
        if (snapshot == null) return map;
        map.put("summary", snapshot.summary);
        map.put("dimension", snapshot.dimension);
        map.put("center", snapshot.x + "," + snapshot.y + "," + snapshot.z);
        map.put("radius", snapshot.radius);
        map.put("air_blocks", snapshot.airBlocks);
        map.put("solid_blocks", snapshot.solidBlocks);
        map.put("water_blocks", snapshot.waterBlocks);
        map.put("lava_blocks", snapshot.lavaBlocks);
        map.put("open_headroom_columns", snapshot.openHeadroomColumns);
        map.put("flat_floor_columns", snapshot.flatFloorColumns);
        map.put("hostile_entities", snapshot.hostileEntities);
        map.put("passive_entities", snapshot.passiveEntities);
        map.put("dropped_items", snapshot.droppedItems);
        map.put("standing_in_liquid", snapshot.standingInLiquid);
        map.put("near_lava", snapshot.nearLava);
        map.put("has_emergency_headroom", snapshot.hasEmergencyHeadroom);
        map.put("notable_blocks", snapshot.notableBlocks);
        return map;
    }

    private void writePrompt(String mode, String userPrompt, boolean commandRequired) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the planning advisor for Belfegor, an autonomous Minecraft bot. "
                + "You do NOT play the game yourself; you choose the next Belfegor command.\n");
        prompt.append("You are consulted ONE command at a time in a loop: after each command finishes or fails, "
                + "you are asked again. Therefore return exactly ONE next command per response, and chain long "
                + "goals across responses (e.g. @get gold_ingot -> @get apple -> @get golden_apple -> "
                + "@shulker store [golden_apple 2]).\n");
        prompt.append("Read the command catalogue JSON to see every legal command, its arguments, and examples. "
                + "Never invent commands. Never return a command that is not in that catalogue.\n");
        prompt.append("Use task_status, last_action, last_executed_command, inventory, stored_at_base, errands, "
                + "game_plan, and recent_decisions to decide the next step. If the bot is busy "
                + "(task_status is not idle), prefer a matching continuation or an empty command instead of "
                + "interrupting.\n");
        prompt.append("Prefer commands that advance the active game_plan stage "
                + "(wood_tools -> stone_tools -> iron_tools -> diamond_tools -> food_supply -> base_camp -> "
                + "base_expansion -> nether_resources -> stronghold -> end_dragon).\n");
        prompt.append("You MUST answer with exactly ONE JSON object and nothing else: no markdown fences, "
                + "no code blocks, no explanations before or after, no thinking.\n");
        if (commandRequired) {
            prompt.append("Schema (command is required and must start with @):\n");
        } else {
            prompt.append("Schema (command may be empty when just chatting):\n");
        }
        prompt.append("{\"command\": \"<one Belfegor command starting with @, or empty>\", "
                + "\"chat\": \"<short explanation to the player>\", "
                + "\"goal\": \"<short current goal>\", "
                + "\"reason\": \"<why this command now, citing context>\"}\n");
        prompt.append("Example:\n");
        prompt.append("{\"command\": \"@get golden_apple 1\", "
                + "\"chat\": \"Crafting golden apples for regeneration food.\", "
                + "\"goal\": \"prepare food and gear\", "
                + "\"reason\": \"Inventory has 8 gold ingots and 4 apples; golden apple is craftable now.\"}\n");
        prompt.append("Rules:\n- command must start with @ and match a command in the catalogue.\n"
                + "- If no command is needed right now, set command to \"\" and explain in chat.\n"
                + "- chat, goal, and reason should be concise, concrete, and grounded in the context.\n\n");
        prompt.append("Context file: ").append(_contextFile.getAbsolutePath()).append("\n");
        prompt.append("Command catalogue (JSON): ")
                .append(_commandsJsonFile == null ? "" : _commandsJsonFile.getAbsolutePath()).append("\n");
        prompt.append("Mode: ").append(mode).append("\n");
        prompt.append("Prompt: ").append(userPrompt).append("\n");
        prompt.append("Recent action log: ").append(_actionLogFile.getAbsolutePath()).append("\n");
        prompt.append("\nJSON response only:\n");
        Files.writeString(_promptFile.toPath(), prompt.toString(), StandardCharsets.UTF_8);
    }

    private String cleanString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private File resolveLlamaExecutable(Belfegor mod) {
        String configured = mod.getModSettings().getLlmLlamaCppExecutable();
        if (configured != null && !configured.isBlank()) {
            return resolveGameFile(configured);
        }
        String executableName = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win") ? "llama-cli.exe" : "llama-cli";
        return new File(_dir, "llama.cpp/" + executableName);
    }

    private File resolveGameFile(String configuredPath) {
        File file = new File(configuredPath);
        if (file.isAbsolute()) return file;
        File gameDir = _dir == null || _dir.getParentFile() == null ? new File(".") : _dir.getParentFile();
        return new File(gameDir, configuredPath);
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
