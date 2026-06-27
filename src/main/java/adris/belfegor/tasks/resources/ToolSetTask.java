package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasksystem.Task;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.*;

/**
 * Crafts a full set of tools for a given tier.
 *
 * Delegates to TaskCatalogue.getItemTask for each tool, which handles
 * all resource gathering, crafting table/furnace creation, and crafting.
 *
 * Tools crafted in order: pickaxe, axe, shovel, sword.
 * If you already have a tool, it is skipped.
 *
 * Progression:
 *   WOOD    -> wooden_pickaxe, wooden_axe, wooden_shovel, wooden_sword
 *   STONE   -> stone_pickaxe, stone_axe, stone_shovel, stone_sword
 *   IRON    -> iron_pickaxe, iron_axe, iron_shovel, iron_sword
 *   DIAMOND -> diamond_pickaxe, diamond_axe, diamond_shovel, diamond_sword
 */
public class ToolSetTask extends Task {

    public enum Tier {
        WOOD, STONE, IRON, DIAMOND
    }

    private final Tier _targetTier;
    private List<Item> _toolsToCraft = new ArrayList<>();
    private int _craftIndex = 0;
    private Task _activeCraftTask = null;
    private Item _activeCraftItem = null;

    public ToolSetTask(Tier targetTier) {
        _targetTier = targetTier;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _craftIndex = 0;
        _activeCraftTask = null;
        _activeCraftItem = null;
        _toolsToCraft = buildCraftList();
        Debug.logInternal("ToolSetTask: starting " + _targetTier + " tier, will craft " + _toolsToCraft);
    }

    private List<Item> buildCraftList() {
        List<Item> tools = new ArrayList<>();
        Item tierBase = getTierBaseItem();
        tools.add(getTool(tierBase, "pickaxe"));
        tools.add(getTool(tierBase, "axe"));
        tools.add(getTool(tierBase, "shovel"));
        tools.add(getTool(tierBase, "sword"));
        tools.removeIf(Objects::isNull);
        return tools;
    }

    private Item getTierBaseItem() {
        return switch (_targetTier) {
            case WOOD -> Items.WOODEN_PICKAXE;
            case STONE -> Items.STONE_PICKAXE;
            case IRON -> Items.IRON_PICKAXE;
            case DIAMOND -> Items.DIAMOND_PICKAXE;
        };
    }

    private static Item getTool(Item base, String name) {
        String baseId = net.minecraft.registry.Registries.ITEM.getId(base).getPath();
        String prefix = baseId.replace("_pickaxe", "");
        return net.minecraft.registry.Registries.ITEM.get(
                net.minecraft.util.Identifier.of("minecraft", prefix + "_" + name));
    }

    @Override
    protected Task onTick(Belfegor mod) {
        // Clear finished/stopped cached task so we advance to the next tool
        if (_activeCraftTask != null && (!_activeCraftTask.isActive() || _activeCraftTask.isFinished(mod) || _activeCraftTask.stopped())) {
            _activeCraftTask = null;
            _activeCraftItem = null;
        }

        if (_craftIndex >= _toolsToCraft.size()) {
            return null;
        }

        Item toolToCraft = _toolsToCraft.get(_craftIndex);
        if (toolToCraft == null) {
            _craftIndex++;
            return null;
        }

        if (mod.getItemStorage().hasItem(toolToCraft)) {
            _craftIndex++;
            _activeCraftTask = null;
            _activeCraftItem = null;
            return null;
        }

        String toolName = net.minecraft.registry.Registries.ITEM.getId(toolToCraft).getPath();
        setDebugState("Crafting " + toolName);

        // TaskCatalogue.getItemTask(String, int) now wraps ALL CraftInTableTask
        // results with EnsureWoodSupplyTask, so we don't need to wrap here.
        if (_activeCraftTask == null || _activeCraftItem != toolToCraft) {
            _activeCraftTask = TaskCatalogue.getItemTask(toolName, 1);
            _activeCraftItem = toolToCraft;
        }
        return _activeCraftTask;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeCraftTask = null;
        _activeCraftItem = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ToolSetTask task) {
            return task._targetTier == _targetTier;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "ToolSet " + _targetTier;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        // _toolsToCraft is populated in onStart(). Before onStart runs, return false
        // so the task framework actually calls tick(). Returning true here when
        // the list is empty (before onStart) causes the framework to instantly
        // finish the task without ever running it.
        if (_toolsToCraft == null || _toolsToCraft.isEmpty()) return false;
        for (Item tool : _toolsToCraft) {
            if (tool != null && !mod.getItemStorage().hasItem(tool)) {
                return false;
            }
        }
        return true;
    }
}