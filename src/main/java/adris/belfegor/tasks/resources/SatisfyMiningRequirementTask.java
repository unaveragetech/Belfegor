package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.MiningRequirement;
import adris.belfegor.util.helpers.StorageHelper;
import net.minecraft.item.Items;

/**
 * Make sure we have a tool at or above a mining level.
 */
public class SatisfyMiningRequirementTask extends Task {

    private final MiningRequirement _requirement;

    public SatisfyMiningRequirementTask(MiningRequirement requirement) {
        _requirement = requirement;
    }

    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_requirement) {
            case HAND:
                break;
            case WOOD:
                // Make axe before woodchopping, and full wooden tool set
                if (!mod.getItemStorage().hasItem(Items.WOODEN_AXE)) {
                    // Need planks -> need logs -> chop wood with axe first
                    return TaskCatalogue.getItemTask(Items.WOODEN_AXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
            case STONE:
                // Make full stone tool set (need wooden pickaxe first to mine cobblestone)
                if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
                    return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
            case IRON:
                // Make full iron tool set (need stone pickaxe first to mine iron)
                if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
                    return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            case DIAMOND:
                // Make full diamond tool set (need iron pickaxe first to mine diamonds)
                if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE)) {
                    return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
        }
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SatisfyMiningRequirementTask task) {
            return task._requirement == _requirement;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Mining Req: " + _requirement;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return StorageHelper.miningRequirementMetInventory(mod, _requirement);
    }
}
