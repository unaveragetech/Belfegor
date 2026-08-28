package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasksystem.Task;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Make sure we have a shovel at or above a tool tier before digging dirt,
 * sand, gravel, and other shovel-suitable blocks.
 *
 * Mirrors SatisfyAxeRequirementTask: the upgrade crafts tier-by-tier so the
 * upgrade's own wood gathering always has a working axe, and MineAndCollectTask
 * caches this child until the shovel actually exists.
 */
public class SatisfyShovelRequirementTask extends Task {

    private static boolean _shovelUpgradeInProgress = false;

    private final ToolSetTask.Tier _target;

    public SatisfyShovelRequirementTask(ToolSetTask.Tier target) {
        _target = target;
    }

    public static boolean isUpgradingShovel() {
        return _shovelUpgradeInProgress;
    }

    /** Best shovel tier currently carried, or null if the bot has no shovel. */
    public static ToolSetTask.Tier currentShovelTier(Belfegor mod) {
        if (mod.getItemStorage().hasItem(Items.DIAMOND_SHOVEL) || mod.getItemStorage().hasItem(Items.NETHERITE_SHOVEL)) {
            return ToolSetTask.Tier.DIAMOND;
        }
        if (mod.getItemStorage().hasItem(Items.IRON_SHOVEL)) return ToolSetTask.Tier.IRON;
        if (mod.getItemStorage().hasItem(Items.STONE_SHOVEL)) return ToolSetTask.Tier.STONE;
        if (mod.getItemStorage().hasItem(Items.WOODEN_SHOVEL)) return ToolSetTask.Tier.WOOD;
        return null;
    }

    public static boolean hasAnyShovel(Belfegor mod) {
        return currentShovelTier(mod) != null;
    }

    public static boolean hasShovelAtLeast(Belfegor mod, ToolSetTask.Tier tier) {
        ToolSetTask.Tier current = currentShovelTier(mod);
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

    public static Item shovelItemFor(ToolSetTask.Tier tier) {
        return switch (tier) {
            case WOOD -> Items.WOODEN_SHOVEL;
            case STONE -> Items.STONE_SHOVEL;
            case IRON -> Items.IRON_SHOVEL;
            case DIAMOND -> Items.DIAMOND_SHOVEL;
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
        _shovelUpgradeInProgress = true;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (isFinished(mod)) {
            _shovelUpgradeInProgress = false;
            return null;
        }
        ToolSetTask.Tier prev = previousTier(_target);
        if (prev != null && !hasShovelAtLeast(mod, prev)) {
            return TaskCatalogue.getItemTask(shovelItemFor(prev), 1);
        }
        return TaskCatalogue.getItemTask(shovelItemFor(_target), 1);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _shovelUpgradeInProgress = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SatisfyShovelRequirementTask task && task._target == _target;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Shovel Req: " + _target;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return hasShovelAtLeast(mod, _target);
    }
}
