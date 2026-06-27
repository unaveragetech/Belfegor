package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.item.Item;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers how to obtain items - step by step crafting/mining sequences.
 * Learns from successful completions and gets better over time.
 * Persists to JSON across sessions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CraftingMemory {

    private static CraftingMemory INSTANCE = new CraftingMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_crafting_memory.json";

    private final Map<String, ItemPlan> _knownPlans = new ConcurrentHashMap<>();
    private final Map<String, ToolStats> _toolStats = new ConcurrentHashMap<>();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Step {
        public String action;
        public String target;
        public int count;
        public double timeSeconds;
        public boolean succeeded;

        public Step() {}
        public Step(String action, String target, int count) {
            this.action = action;
            this.target = target;
            this.count = count;
            this.succeeded = true;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ItemPlan {
        public String itemName;
        public List<Step> steps = new ArrayList<>();
        public int timesSucceeded = 0;
        public int timesFailed = 0;
        public double avgTimeSeconds = 0;
        public long lastAttemptTime = 0;

        public ItemPlan() {}
        public ItemPlan(String itemName) { this.itemName = itemName; }

        public void recordSuccess(double timeSeconds) {
            timesSucceeded++;
            avgTimeSeconds = (avgTimeSeconds * (timesSucceeded - 1) + timeSeconds) / timesSucceeded;
            lastAttemptTime = System.currentTimeMillis();
        }

        public void recordFailure() {
            timesFailed++;
            lastAttemptTime = System.currentTimeMillis();
        }

        public double getSuccessRate() {
            int total = timesSucceeded + timesFailed;
            return total == 0 ? 0.5 : (double) timesSucceeded / total;
        }

        public double getScore() {
            double rate = getSuccessRate();
            double recencyBonus = lastAttemptTime > 0 ? Math.max(0, 1.0 - (System.currentTimeMillis() - lastAttemptTime) / 3600000.0) * 0.2 : 0;
            return rate + recencyBonus;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ToolStats {
        public String toolName;
        public int timesCrafted = 0;
        public int timesUsedToChop = 0;
        public int timesUsedToMine = 0;
        public double avgDurabilityUsed = 0;

        public ToolStats() {}
        public ToolStats(String toolName) { this.toolName = toolName; }
    }

    public static CraftingMemory getInstance() { return INSTANCE; }

    public static void init(File gameDir) {
        File file = new File(gameDir, FILE_NAME);
        if (file.exists()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                INSTANCE = mapper.readValue(file, CraftingMemory.class);
            } catch (Exception e) {
                INSTANCE = new CraftingMemory();
            }
        }
    }

    public void recordStep(String itemName, Step step) {
        ItemPlan plan = _knownPlans.computeIfAbsent(itemName, k -> new ItemPlan(itemName));
        plan.steps.add(step);
        _dirty = true;
    }

    public void recordSuccess(String itemName, double timeSeconds) {
        ItemPlan plan = _knownPlans.computeIfAbsent(itemName, k -> new ItemPlan(itemName));
        plan.recordSuccess(timeSeconds);
        _dirty = true;
    }

    public void recordFailure(String itemName) {
        ItemPlan plan = _knownPlans.computeIfAbsent(itemName, k -> new ItemPlan(itemName));
        plan.recordFailure();
        _dirty = true;
    }

    public Optional<ItemPlan> getPlan(String itemName) {
        return Optional.ofNullable(_knownPlans.get(itemName));
    }

    public List<Step> getBestSteps(String itemName) {
        ItemPlan plan = _knownPlans.get(itemName);
        if (plan == null || plan.steps.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(plan.steps);
    }

    public void recordToolCraft(String toolName) {
        ToolStats stats = _toolStats.computeIfAbsent(toolName, k -> new ToolStats(toolName));
        stats.timesCrafted++;
        _dirty = true;
    }

    public void recordToolUse(String toolName, String useType) {
        ToolStats stats = _toolStats.computeIfAbsent(toolName, k -> new ToolStats(toolName));
        switch (useType) {
            case "chop" -> stats.timesUsedToChop++;
            case "mine" -> stats.timesUsedToMine++;
        }
        _dirty = true;
    }

    public ToolStats getToolStats(String toolName) {
        return _toolStats.get(toolName);
    }

    public boolean shouldMakeAxeFirst() {
        ToolStats axe = _toolStats.get("wooden_axe");
        if (axe == null) return true;
        return axe.timesUsedToChop > 0;
    }

    public boolean shouldMakeStoneTools() {
        ToolStats woodPick = _toolStats.get("wooden_pickaxe");
        if (woodPick == null) return false;
        return woodPick.timesUsedToMine > 5;
    }

    public Set<String> getKnownItems() {
        return _knownPlans.keySet();
    }

    public void save() {
        if (!_dirty) return;
        try {
            java.io.File dir = new java.io.File(FOLDER);
            dir.mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(dir, FILE_NAME), this);
            _dirty = false;
        } catch (Exception e) {}
    }

    public boolean hasPlan(String itemName) {
        ItemPlan plan = _knownPlans.get(itemName);
        return plan != null && plan.timesSucceeded > 0;
    }
}
