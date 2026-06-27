package adris.belfegor.chains;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.resources.ToolSetTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.tasksystem.TaskRunner;

/**
 * Background chain that enforces tool requirements at all times.
 * Runs alongside other tasks (low priority) — only activates when the bot lacks
 * a required tool for its current activity.
 *
 * Uses ToolSetTask to batch-craft ALL tools in a tier at once:
 * - If missing wooden pickaxe → craft full WOOD set (pickaxe + axe + shovel + sword)
 * - If missing stone pickaxe → craft full STONE set
 * - If missing iron pickaxe → craft full IRON set
 *
 * Priority: 25 (below user tasks at 50, below food at 35, above nothing).
 * Only activates when tool is missing.
 */
public class ToolRequirementChain extends SingleTaskChain {

    // Re-check interval: don't re-evaluate every single tick
    private final adris.belfegor.util.time.TimerGame _checkTimer = new adris.belfegor.util.time.TimerGame(2);

    private boolean _wasEnforcing = false;

    public ToolRequirementChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(Belfegor mod) {
        if (!Belfegor.inGame()) return Float.NEGATIVE_INFINITY;

        // Only check periodically
        if (!_checkTimer.elapsed()) {
            // If we were already enforcing a tool requirement, keep going
            if (_wasEnforcing && isCurrentlyRunning(mod)) {
                return 25;
            }
            return Float.NEGATIVE_INFINITY;
        }
        _checkTimer.reset();

        // Determine what tool tier is needed based on current activity
        ToolSetTask.Tier neededTier = determineNeededTier(mod);

        if (neededTier == null) {
            _wasEnforcing = false;
            return Float.NEGATIVE_INFINITY;
        }

        // Check if we have the required tools for this tier
        String tierPrefix = switch (neededTier) {
            case WOOD -> "wooden";
            case STONE -> "stone";
            case IRON -> "iron";
            case DIAMOND -> "diamond";
        };
        if (hasToolTier(mod, tierPrefix)) {
            _wasEnforcing = false;
            return Float.NEGATIVE_INFINITY;
        }

        // We need tools! Take over to batch-craft the full tier.
        _wasEnforcing = true;

        Task toolTask = new ToolSetTask(neededTier);
        setTask(toolTask);
        return 25;
    }

    /**
     * Determine the lowest tool tier the bot is missing.
     * Returns null if all tool tiers are complete.
     */
    private ToolSetTask.Tier determineNeededTier(Belfegor mod) {
        // Check WOOD tier: need wooden pickaxe + axe + shovel
        if (!hasToolTier(mod, "wooden")) {
            return ToolSetTask.Tier.WOOD;
        }
        // Check STONE tier: need stone pickaxe + axe + shovel
        if (!hasToolTier(mod, "stone")) {
            return ToolSetTask.Tier.STONE;
        }
        // Check IRON tier: need iron pickaxe + axe + shovel
        if (!hasToolTier(mod, "iron")) {
            return ToolSetTask.Tier.IRON;
        }
        // Don't proactively craft diamond — only when specifically needed
        return null;
    }

    /**
     * Check if the bot has a full tool tier (pickaxe + axe + shovel).
     */
    private boolean hasToolTier(Belfegor mod, String prefix) {
        return mod.getItemStorage().hasItem(getItem(prefix + "_pickaxe"))
                && mod.getItemStorage().hasItem(getItem(prefix + "_axe"))
                && mod.getItemStorage().hasItem(getItem(prefix + "_shovel"));
    }

    private net.minecraft.item.Item getItem(String name) {
        return net.minecraft.registry.Registries.ITEM.get(
                net.minecraft.util.Identifier.of("minecraft", name));
    }

    @Override
    protected void onTaskFinish(Belfegor mod) {
        // Tool set was crafted, resume normal operation
        _wasEnforcing = false;
    }

    @Override
    public String getName() {
        return "Tool Requirement Enforcer";
    }

    @Override
    public boolean isActive() {
        // Always active — monitors tool state
        return true;
    }

    @Override
    protected void onStop(Belfegor mod) {
        super.onStop(mod);
        _wasEnforcing = false;
    }
}
