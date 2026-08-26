package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasksystem.Task;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Make sure we have an axe at or above a tool tier before chopping wood.
 *
 * Wood/log blocks are registered with MiningRequirement.HAND because no
 * pickaxe tier gates them, which meant the old tool system never asked for an
 * axe at all. This task is the axe counterpart of SatisfyMiningRequirementTask:
 * it crafts the missing axe (upgrading tier-by-tier so the upgrade's own wood
 * gathering always has a working axe), and MineAndCollectTask calls it whenever
 * its target blocks are axe-suitable.
 */
public class SatisfyAxeRequirementTask extends Task {

    // Set while an axe is being crafted. Wood gathering checks this flag so it
    // does not demand the very axe that the current craft is producing (which
    // would loop forever).
    private static boolean _axeUpgradeInProgress = false;

    private final ToolSetTask.Tier _target;

    public SatisfyAxeRequirementTask(ToolSetTask.Tier target) {
        _target = target;
    }

    public static boolean isUpgradingAxe() {
        return _axeUpgradeInProgress;
    }

    /** Best axe tier currently carried, or null if the bot has no axe. */
    public static ToolSetTask.Tier currentAxeTier(Belfegor mod) {
        if (mod.getItemStorage().hasItem(Items.DIAMOND_AXE) || mod.getItemStorage().hasItem(Items.NETHERITE_AXE)) {
            return ToolSetTask.Tier.DIAMOND;
        }
        if (mod.getItemStorage().hasItem(Items.IRON_AXE)) return ToolSetTask.Tier.IRON;
        if (mod.getItemStorage().hasItem(Items.STONE_AXE)) return ToolSetTask.Tier.STONE;
        if (mod.getItemStorage().hasItem(Items.WOODEN_AXE)) return ToolSetTask.Tier.WOOD;
        return null;
    }

    public static boolean hasAnyAxe(Belfegor mod) {
        return currentAxeTier(mod) != null;
    }

    public static boolean hasAxeAtLeast(Belfegor mod, ToolSetTask.Tier tier) {
        ToolSetTask.Tier current = currentAxeTier(mod);
        return current != null && tierRank(current) >= tierRank(tier);
    }

    private static int tierRank(ToolSetTask.Tier tier) {
        return switch (tier) {
            case WOOD -> 1;
            case STONE -> 2;
            case IRON -> 3;
            case DIAMOND -> 4;
        };
    }

    public static Item axeItemFor(ToolSetTask.Tier tier) {
        return switch (tier) {
            case WOOD -> Items.WOODEN_AXE;
            case STONE -> Items.STONE_AXE;
            case IRON -> Items.IRON_AXE;
            case DIAMOND -> Items.DIAMOND_AXE;
        };
    }

    private static ToolSetTask.Tier previousTier(ToolSetTask.Tier tier) {
        return switch (tier) {
            case WOOD -> null;
            case STONE -> ToolSetTask.Tier.WOOD;
            case IRON -> ToolSetTask.Tier.STONE;
            case DIAMOND -> ToolSetTask.Tier.IRON;
        };
    }

    @Override
    protected void onStart(Belfegor mod) {
        _axeUpgradeInProgress = true;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (isFinished(mod)) {
            _axeUpgradeInProgress = false;
            return null;
        }
        // Craft the tier below first when needed, so the upgrade's own wood
        // gathering always has a working axe to chop with.
        ToolSetTask.Tier prev = previousTier(_target);
        if (prev != null && !hasAxeAtLeast(mod, prev)) {
            return TaskCatalogue.getItemTask(axeItemFor(prev), 1);
        }
        return TaskCatalogue.getItemTask(axeItemFor(_target), 1);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _axeUpgradeInProgress = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SatisfyAxeRequirementTask task && task._target == _target;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Axe Req: " + _target;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return hasAxeAtLeast(mod, _target);
    }
}
