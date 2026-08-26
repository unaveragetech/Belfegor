package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.misc.EquipArmorTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import net.minecraft.item.Item;

import java.util.Arrays;

/**
 * @equipment <material> - Prepares a full loadout for a material: a complete
 * tool set plus a full armor set, equipping the armor once it is crafted.
 *
 * Phases:
 *   0 -> tools (ToolSetTask for wood/stone/iron/diamond; catalogue-gathered
 *        gold/netherite tools otherwise)
 *   1 -> armor (EquipArmorTask crafts/collects missing pieces then equips)
 *   2 -> done
 *
 * Materials:
 *   wood, stone               -> tools only
 *   leather, chainmail        -> armor only
 *   iron, gold, diamond, netherite -> tools + armor
 */
public class EquipmentTask extends Task {

    private final String _label;
    private final ToolSetTask.Tier _toolTier; // null when the material's tools are not a ToolSetTask tier
    private final Item[] _toolItems;          // fallback tool list (gold/netherite)
    private final Item[] _armorItems;         // null when the material has no armor

    private int _phase = 0; // 0 = tools, 1 = armor, 2 = done
    private Task _active = null;

    public EquipmentTask(String label, ToolSetTask.Tier toolTier, Item[] toolItems, Item[] armorItems) {
        _label = label;
        _toolTier = toolTier;
        _toolItems = toolItems;
        _armorItems = armorItems;
    }

    public static EquipmentTask forMaterial(String material) throws CommandException {
        return switch (material) {
            case "wood", "wooden" -> new EquipmentTask("wood", ToolSetTask.Tier.WOOD, null, null);
            case "stone" -> new EquipmentTask("stone", ToolSetTask.Tier.STONE, null, null);
            case "leather" -> new EquipmentTask("leather", null, null, ItemHelper.LEATHER_ARMORS);
            case "chainmail", "chain" -> new EquipmentTask("chainmail", null, null, ItemHelper.CHAINMAIL_ARMORS);
            case "iron" -> new EquipmentTask("iron", ToolSetTask.Tier.IRON, null, ItemHelper.IRON_ARMORS);
            case "gold", "golden" -> new EquipmentTask("gold", null, ItemHelper.GOLDEN_TOOLS, ItemHelper.GOLDEN_ARMORS);
            case "diamond" -> new EquipmentTask("diamond", ToolSetTask.Tier.DIAMOND, null, ItemHelper.DIAMOND_ARMORS);
            case "netherite" -> new EquipmentTask("netherite", null, ItemHelper.NETHERITE_TOOLS, ItemHelper.NETHERITE_ARMORS);
            default -> throw new CommandException("Invalid equipment material: " + material
                    + ". Use wood, stone, leather, chainmail, iron, gold, diamond, or netherite.");
        };
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = 0;
        _active = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        // Advance when the active sub-task finishes or stops.
        if (_active != null && (!_active.isActive() || _active.isFinished(mod) || _active.stopped())) {
            _active = null;
            _phase++;
        }

        if (_phase == 0 && hasTools()) {
            if (_active == null) {
                _active = buildToolsTask();
            }
            if (_active != null) return _active;
            _phase++; // No tool source for this material.
        }
        if (_phase == 1 && hasArmor()) {
            if (_active == null) {
                _active = new EquipArmorTask(_armorItems);
            }
            return _active;
        }
        return null;
    }

    private Task buildToolsTask() {
        if (_toolTier != null) {
            return new ToolSetTask(_toolTier);
        }
        if (_toolItems != null && _toolItems.length > 0) {
            ItemTarget[] targets = Arrays.stream(_toolItems)
                    .map(ItemTarget::new)
                    .toArray(ItemTarget[]::new);
            return TaskCatalogue.getSquashedItemTask(targets);
        }
        return null;
    }

    private boolean hasTools() {
        return _toolTier != null || (_toolItems != null && _toolItems.length > 0);
    }

    private boolean hasArmor() {
        return _armorItems != null && _armorItems.length > 0;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _active = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EquipmentTask task) {
            return task._toolTier == _toolTier
                    && Arrays.equals(task._toolItems, _toolItems)
                    && Arrays.equals(task._armorItems, _armorItems);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Equipment " + _label;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        if (_active != null) return false;
        int expected = (hasTools() ? 1 : 0) + (hasArmor() ? 1 : 0);
        return _phase >= expected;
    }
}
