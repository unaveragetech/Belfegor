package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import net.minecraft.item.Items;

/**
 * Ensures we have enough wood supply (logs, planks, sticks, crafting table)
 * before attempting to craft wooden tools/armor.
 * This prevents the bot from getting stuck trying to craft items without materials.
 */
public class EnsureWoodSupplyTask extends ResourceTask {

    private final ResourceTask _actualCraftTask;
    private Task _activeSubTask = null;
    private String _activeSubTaskKey = null;

    public EnsureWoodSupplyTask(ResourceTask actualCraftTask, int count) {
        super(actualCraftTask.getItemTargets());
        _actualCraftTask = actualCraftTask;
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        _activeSubTask = null;
        _activeSubTaskKey = null;
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        // Clear finished/stopped cached sub-tasks so we don't return stale instances
        // that immediately report "done" and cause infinite idle loops.
        if (_activeSubTask != null && (!_activeSubTask.isActive() || _activeSubTask.isFinished(mod) || _activeSubTask.stopped())) {
            _activeSubTask = null;
            _activeSubTaskKey = null;
        }

        int planksNeeded = 8;
        int sticksNeeded = 4;

        int logsHave = mod.getItemStorage().getItemCount(ItemHelper.LOG);
        int planksHave = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
        int sticksHave = mod.getItemStorage().getItemCount(Items.STICK);
        boolean hasTable = mod.getItemStorage().hasItem(Items.CRAFTING_TABLE);

        int potentialPlanks = planksHave + logsHave * 4;
        if (potentialPlanks < planksNeeded) {
            String key = "logs:3";
            if (_activeSubTask != null && key.equals(_activeSubTaskKey)) return _activeSubTask;
            _activeSubTaskKey = key;
            _activeSubTask = TaskCatalogue.getItemTask("log", 3);
            setDebugState("Collecting logs for planks...");
            return _activeSubTask;
        }

        if (planksHave < planksNeeded && logsHave > 0) {
            // Use TOTAL count (planksNeeded), NOT deficit (planksNeeded - planksHave).
            // TaskCatalogue.getItemTask("planks", N) means "ensure we have N planks total",
            // NOT "get N more planks". Using the deficit creates a task that immediately
            // finishes because we already have that many, AND the changing key causes
            // task interruption mid-craft.
            String key = "planks:" + planksNeeded;
            if (_activeSubTask != null && key.equals(_activeSubTaskKey)) return _activeSubTask;
            _activeSubTaskKey = key;
            _activeSubTask = TaskCatalogue.getItemTask("planks", planksNeeded);
            setDebugState("Crafting planks...");
            return _activeSubTask;
        }

        if (sticksHave < sticksNeeded && planksHave >= 2) {
            // Same fix: use TOTAL count, not deficit.
            String key = "sticks:" + sticksNeeded;
            if (_activeSubTask != null && key.equals(_activeSubTaskKey)) return _activeSubTask;
            _activeSubTaskKey = key;
            _activeSubTask = TaskCatalogue.getItemTask("stick", sticksNeeded);
            setDebugState("Crafting sticks...");
            return _activeSubTask;
        }

        if (!hasTable && planksHave >= 4) {
            String key = "table:1";
            if (_activeSubTask != null && key.equals(_activeSubTaskKey)) return _activeSubTask;
            _activeSubTaskKey = key;
            _activeSubTask = TaskCatalogue.getItemTask("crafting_table", 1);
            setDebugState("Crafting crafting table...");
            return _activeSubTask;
        }

        _activeSubTask = null;
        _activeSubTaskKey = null;
        setDebugState("Materials ready, crafting...");
        return _actualCraftTask;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        _activeSubTask = null;
        _activeSubTaskKey = null;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof EnsureWoodSupplyTask task) {
            return _actualCraftTask.getClass().equals(task._actualCraftTask.getClass());
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Ensure Wood Supply: " + _actualCraftTask.getItemTargets()[0];
    }
}