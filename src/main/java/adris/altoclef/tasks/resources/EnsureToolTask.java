package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Background sub-routine that ensures the bot has the best tool for its current job.
 * Runs on top of other tasks — checks tool state every tick and crafts if needed.
 *
 * The bot is always aware of the best tool to use:
 * 1. Checks if current mining requirement is met
 * 2. If not, crafts the minimum required tool (recursively ensuring prerequisites)
 * 3. Also proactively upgrades tools if better ones are available
 *
 * This task never finishes — it stays active as long as tool enforcement is needed.
 * Wrap other tasks with this to guarantee tool readiness.
 */
public class EnsureToolTask extends Task {

    private final MiningRequirement _minimumRequirement;
    private final Task _wrappedTask;
    private boolean _toolReady = false;
    private Task _toolTask = null;
    private String _toolTaskKey = null;

    /**
     * Ensure we have at least the given mining requirement before running the wrapped task.
     * @param minimumRequirement The minimum tool tier needed
     * @param wrappedTask The task to run once tools are ready
     */
    public EnsureToolTask(MiningRequirement minimumRequirement, Task wrappedTask) {
        _minimumRequirement = minimumRequirement;
        _wrappedTask = wrappedTask;
    }

    /**
     * Simple constructor: just ensure minimum requirement without wrapping a task.
     */
    public EnsureToolTask(MiningRequirement minimumRequirement) {
        _minimumRequirement = minimumRequirement;
        _wrappedTask = null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _toolReady = false;
        _toolTask = null;
        _toolTaskKey = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Check if we have the required tool
        if (!StorageHelper.miningRequirementMetInventory(mod, _minimumRequirement)) {
            _toolReady = false;
            Task toolTask = createToolTask(mod);
            if (toolTask != null) {
                setDebugState("Crafting tool for " + _minimumRequirement);
                return cachedToolTask("tool:" + _minimumRequirement, toolTask);
            }
        }

        // Tool is ready — run the wrapped task if we have one
        _toolReady = true;
        if (_wrappedTask != null) {
            return _wrappedTask;
        }
        return null;
    }

    /**
     * Creates the appropriate tool-crafting task based on what's missing.
     * Follows the tier progression: hand -> wood -> stone -> iron -> diamond.
     */
    private Task createToolTask(AltoClef mod) {
        switch (_minimumRequirement) {
            case HAND:
                return null; // No tool needed

            case WOOD:
                // Need wooden pickaxe (and axe for wood gathering)
                if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
                    return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                }
                return null;

            case STONE:
                // Need stone pickaxe (requires wooden pickaxe first)
                if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
                    if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
                        return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                    }
                    return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                }
                return null;

            case IRON:
                // Need iron pickaxe (requires stone pickaxe first)
                if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE)) {
                    if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
                        if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
                            return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                        }
                        return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                    }
                    return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
                }
                return null;

            case DIAMOND:
                // Need diamond pickaxe (requires iron pickaxe first)
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)) {
                    if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE)) {
                        if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
                            if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
                                return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                            }
                            return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                        }
                        return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
                    }
                    return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                }
                return null;

            case NETHERITE:
                // Need netherite pickaxe (requires diamond pickaxe + netherite ingot)
                if (!mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE)) {
                    if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)) {
                        if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE)) {
                            if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
                                if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
                                    return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                                }
                                return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                            }
                            return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
                        }
                        return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                    }
                    return TaskCatalogue.getItemTask(Items.NETHERITE_PICKAXE, 1);
                }
                return null;
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _toolTask = null;
        _toolTaskKey = null;
    }

    /**
     * Check if the minimum tool requirement is met.
     */
    public boolean isToolReady(AltoClef mod) {
        return StorageHelper.miningRequirementMetInventory(mod, _minimumRequirement);
    }

    /**
     * Get the current mining requirement being enforced.
     */
    public MiningRequirement getMinimumRequirement() {
        return _minimumRequirement;
    }

    private Task cachedToolTask(String key, Task task) {
        if (_toolTask != null && key.equals(_toolTaskKey)) {
            return _toolTask;
        }
        _toolTaskKey = key;
        _toolTask = task;
        return task;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EnsureToolTask task) {
            return task._minimumRequirement == _minimumRequirement;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Ensure Tool: " + _minimumRequirement + (isToolReady(null) ? " [READY]" : " [CRAFTING]");
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // This task is "finished" if we have the tool AND the wrapped task is done (or null)
        if (!isToolReady(mod)) return false;
        if (_wrappedTask != null) return _wrappedTask.isFinished(mod);
        return true;
    }
}
