package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.tasks.CraftInInventoryTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.*;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.Arrays;

public class CollectPlanksTask extends ResourceTask {

    private final Item[] _planks;
    private final Item[] _logs;
    private final int _targetCount;
    private boolean _logsInNether;

    private enum Phase { MINING, CRAFTING }
    private Phase _phase = Phase.MINING;
    private CraftInInventoryTask _activeCraftTask = null;
    private MineAndCollectTask _activeMineTask = null;

    public CollectPlanksTask(Item[] planks, Item[] logs, int count, boolean logsInNether) {
        super(new ItemTarget(planks, count));
        _planks = planks;
        _logs = logs;
        _targetCount = count;
        _logsInNether = logsInNether;
    }

    public CollectPlanksTask(int count) {
        this(ItemHelper.PLANKS, ItemHelper.LOG, count, false);
    }

    public CollectPlanksTask(Item plank, Item log, int count) {
        this(new Item[]{plank}, new Item[]{log}, count, false);
    }

    public CollectPlanksTask(Item plank, int count) {
        this(plank, ItemHelper.planksToLog(plank), count);
    }

    private static CraftingRecipe generatePlankRecipe(Item[] logs) {
        return CraftingRecipe.newShapedRecipe(
                "planks",
                new Item[][]{
                        logs, null,
                        null, null
                },
                4
        );
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        _phase = Phase.MINING;
        _activeCraftTask = null;
        _activeMineTask = null;
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {

        // Do not replace an in-flight craft while it owns the cursor. Output
        // arriving can satisfy this task one tick before the remaining log is
        // returned to inventory.
        if (_activeCraftTask != null
                && !_activeCraftTask.stopped()
                && !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            adris.belfegor.debug.DebugLogger.getInstance().logImmediate("CRAFT-HANDOFF",
                    "holding active plank craft until cursor clears target="
                            + _activeCraftTask.getRecipeTarget()
                            + " cursor=" + StorageHelper.getItemStackInCursorSlot());
            return _activeCraftTask;
        }

        // Craft when we can
        int totalInventoryPlankCount = mod.getItemStorage().getItemCount(_planks);

        // If we already have enough planks, we're done
        if (totalInventoryPlankCount >= _targetCount) {
            _phase = Phase.MINING;
            _activeCraftTask = null;
            _activeMineTask = null;
            setDebugState("Already have " + totalInventoryPlankCount + "/" + _targetCount + " planks");
            return null;
        }

        int potentialPlanks = totalInventoryPlankCount + mod.getItemStorage().getItemCount(_logs) * 4;
        if (potentialPlanks >= _targetCount) {
            for (Item logCheck : _logs) {
                int count = mod.getItemStorage().getItemCount(logCheck);
                if (count > 0) {
                    Item plankCheck = ItemHelper.logToPlanks(logCheck);
                    if (plankCheck == null) {
                        Debug.logError("Invalid/Un-convertable log: " + logCheck + " (failed to find corresponding plank)");
                        continue;
                    }
                    int plankCount = mod.getItemStorage().getItemCount(plankCheck);
                    int otherPlankCount = totalInventoryPlankCount - plankCount;
                    int targetTotalPlanks = Math.min(count * 4 + plankCount, _targetCount - otherPlankCount);
                    setDebugState("We have " + logCheck + ", crafting " + targetTotalPlanks + " planks.");

                    // Transition to CRAFTING phase — commit to crafting
                    _phase = Phase.CRAFTING;
                    _activeMineTask = null;

                    // If we already have an active crafting task with the same target, reuse it
                    // to avoid interrupting and restarting every tick
                    if (_activeCraftTask != null && !_activeCraftTask.isFinished(mod) && !_activeCraftTask.stopped()) {
                        RecipeTarget newTarget = new RecipeTarget(plankCheck, targetTotalPlanks, generatePlankRecipe(_logs));
                        if (_activeCraftTask.getRecipeTarget() != null
                                && _activeCraftTask.getRecipeTarget().getOutputItem().equals(newTarget.getOutputItem())
                                && _activeCraftTask.getRecipeTarget().getTargetCount() == newTarget.getTargetCount()) {
                            return _activeCraftTask;
                        }
                    }

                    // Create new crafting task
                    _activeCraftTask = new CraftInInventoryTask(new RecipeTarget(plankCheck, targetTotalPlanks, generatePlankRecipe(_logs)));
                    return _activeCraftTask;
                }
            }
        }

        // If we were in CRAFTING phase but logs ran out, go back to mining
        if (_phase == Phase.CRAFTING) {
            _phase = Phase.MINING;
            _activeCraftTask = null;
        }

        // Reuse mining task instance if still active (prevent constant restarts every tick)
        if (_activeMineTask != null && !_activeMineTask.isFinished(mod) && !_activeMineTask.stopped()) {
            return _activeMineTask;
        }
        _activeMineTask = null;

        // Collect planks and logs
        ArrayList<ItemTarget> blocksTomine = new ArrayList<>(2);
        blocksTomine.add(new ItemTarget(_logs));
        // Ignore planks if we're told to.
        if (!mod.getBehaviour().exclusivelyMineLogs()) {
            // TODO: Add planks back in, but with a heuristic check (so we don't go for abandoned mineshafts)
            //blocksTomine.add(new ItemTarget(ItemUtil.PLANKS));
        }

        _activeMineTask = new MineAndCollectTask(blocksTomine.toArray(ItemTarget[]::new), MiningRequirement.HAND);
        // Kinda jank
        if (_logsInNether) {
            _activeMineTask.forceDimension(Dimension.NETHER);
        }
        return _activeMineTask;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        _phase = Phase.MINING;
        _activeCraftTask = null;
        _activeMineTask = null;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectPlanksTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _targetCount + " planks " + Arrays.toString(_planks);
    }

    public CollectPlanksTask logsInNether() {
        _logsInNether = true;
        return this;
    }
}
