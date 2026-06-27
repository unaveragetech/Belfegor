package adris.belfegor.tasksystem;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents one route to obtain an item.
 * Each path has a difficulty score, success/failure stats, and a list of required materials.
 * The registry picks the path with the lowest score (easiest) for any given item.
 *
 * Score = baseDifficulty * (1 - successRateBonus) * (1 - recencyBonus) * (1 - inventoryBonus)
 * Lower score = easier path = preferred.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CraftingPath {

    private String name;
    private String description;
    private double baseDifficulty;
    private int timesSucceeded = 0;
    private int timesFailed = 0;
    private long lastUsedTime = 0;
    private List<String> materials = new ArrayList<>();
    private List<String> steps = new ArrayList<>();

    public CraftingPath() {}

    public CraftingPath(String name, String description, double baseDifficulty) {
        this.name = name;
        this.description = description;
        this.baseDifficulty = baseDifficulty;
    }

    public CraftingPath withMaterial(String material) {
        materials.add(material);
        return this;
    }

    public CraftingPath withStep(String step) {
        steps.add(step);
        return this;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public double getBaseDifficulty() { return baseDifficulty; }
    public int getTimesSucceeded() { return timesSucceeded; }
    public int getTimesFailed() { return timesFailed; }
    public long getLastUsedTime() { return lastUsedTime; }
    public List<String> getMaterials() { return materials; }
    public List<String> getSteps() { return steps; }

    public void recordSuccess() {
        timesSucceeded++;
        lastUsedTime = System.currentTimeMillis();
    }

    public void recordFailure() {
        timesFailed++;
        lastUsedTime = System.currentTimeMillis();
    }

    /**
     * Success rate: 0.5 default when no data (assume 50/50 until proven otherwise).
     * With data: successes / total attempts.
     */
    public double getSuccessRate() {
        int total = timesSucceeded + timesFailed;
        if (total == 0) return 0.5;
        return (double) timesSucceeded / total;
    }

    /**
     * Score calculation: lower = easier = preferred.
     *
     * Factors:
     * 1. baseDifficulty: the intrinsic difficulty (0-10)
     * 2. successRate: higher success = lower score (paths that work get preferred)
     * 3. recency: recently used paths get a small bonus (lower score)
     * 4. attempts: paths with more attempts are trusted more
     */
    public double getScore() {
        double successRate = getSuccessRate();
        double totalAttempts = timesSucceeded + timesFailed;

        // Success bonus: paths that succeed more often get lower scores
        // Max bonus: 0.4 (a path with 100% success gets 40% off its difficulty)
        double successBonus = successRate * 0.4;

        // Experience bonus: paths with more data points are more trusted
        // Max bonus: 0.1 (after 20+ attempts, the path is well-understood)
        double experienceBonus = Math.min(totalAttempts / 20.0, 1.0) * 0.1;

        // Recency bonus: recently used paths get a small preference
        // Decays over 1 hour, max 0.05
        double recencyBonus = 0;
        if (lastUsedTime > 0) {
            double hoursSinceUse = (System.currentTimeMillis() - lastUsedTime) / 3600000.0;
            recencyBonus = Math.max(0, 1.0 - hoursSinceUse) * 0.05;
        }

        // Final score: lower = easier
        return baseDifficulty * (1.0 - successBonus - experienceBonus) * (1.0 - recencyBonus);
    }

    /**
     * Check if this path requires materials that are already in inventory.
     * Paths with fewer external requirements are easier.
     */
    public double getInventoryBonus(java.util.Set<String> inventoryItems) {
        if (materials.isEmpty()) return 0;
        int availableCount = 0;
        for (String mat : materials) {
            if (inventoryItems.contains(mat)) availableCount++;
        }
        return (double) availableCount / materials.size();
    }

    @Override
    public String toString() {
        return name + " (difficulty=" + baseDifficulty + ", score=" + String.format("%.2f", getScore())
                + ", success=" + timesSucceeded + "/" + (timesSucceeded + timesFailed) + ")";
    }
}
