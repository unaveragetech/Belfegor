package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.DoorHelper;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repairs the two protected wooden entrance doors of the locked home camp
 * without rebuilding the whole core. Validation uses this when a remembered
 * door module exists but the world no longer contains a door block (for
 * example after pathing or combat broke it).
 */
public class RepairEntranceDoorsTask extends Task {

    private enum Phase {
        RESOLVE,
        CRAFT_DOORS,
        REPAIR,
        DONE
    }

    private final BlockPos _home;
    private final int _radius;
    private Phase _phase = Phase.RESOLVE;
    private Task _activeTask;
    private List<BlockPos> _doors = new ArrayList<>();
    private int _index;
    private int _doorPlacementCooldown;

    public RepairEntranceDoorsTask(BlockPos home, int radius) {
        _home = home == null ? BlockPos.ORIGIN : home;
        _radius = Math.max(6, Math.min(18, radius));
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.RESOLVE;
        _activeTask = null;
        _index = 0;
        _doorPlacementCooldown = 0;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case RESOLVE -> {
                resolveDoors(mod);
                if (_doors.isEmpty()) {
                    _phase = Phase.DONE;
                    return null;
                }
                _phase = Phase.CRAFT_DOORS;
                return null;
            }
            case CRAFT_DOORS -> {
                int missing = countMissingDoors(mod);
                if (missing == 0) {
                    _phase = Phase.REPAIR;
                    return null;
                }
                if (!mod.getItemStorage().hasItem(ItemHelper.WOOD_DOOR)) {
                    setDebugState("Crafting replacement wooden entrance doors");
                    return cache(mod, TaskCatalogue.getItemTask("wooden_door", missing));
                }
                _phase = Phase.REPAIR;
                return null;
            }
            case REPAIR -> {
                Task repair = repairNextDoor(mod);
                if (repair != null) return repair;
                if (countMissingDoors(mod) > 0) return null;
                rememberDoorsComplete();
                _phase = Phase.DONE;
                return null;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private void resolveDoors(Belfegor mod) {
        _doors.clear();
        String dimension = WorldHelper.getCurrentDimension().name();
        Optional<BaseMemory.BaseModule> first = BaseMemory.getInstance()
                .findModule(dimension, "entrance_door_a");
        Optional<BaseMemory.BaseModule> second = BaseMemory.getInstance()
                .findModule(dimension, "entrance_door_b");
        first.map(BaseMemory.BaseModule::center).ifPresent(_doors::add);
        second.map(BaseMemory.BaseModule::center).ifPresent(_doors::add);
        if (_doors.isEmpty()) {
            // Fall back to the canonical campsite doorway positions.
            _doors.add(_home.add(_radius, 0, 0));
            _doors.add(_home.add(_radius, 0, 1));
        }
    }

    private Task repairNextDoor(Belfegor mod) {
        while (_index < _doors.size()) {
            BlockPos target = _doors.get(_index);
            Block current = mod.getWorld().getBlockState(target).getBlock();
            if (current instanceof net.minecraft.block.DoorBlock) {
                _index++;
                continue;
            }
            Block head = mod.getWorld().getBlockState(target.up()).getBlock();
            if (current != Blocks.AIR) {
                setDebugState("Clearing entrance door foot " + (_index + 1) + "/" + _doors.size());
                return cache(mod, new DestroyBlockTask(target));
            }
            if (head != Blocks.AIR) {
                setDebugState("Clearing entrance door head " + (_index + 1) + "/" + _doors.size());
                return cache(mod, new DestroyBlockTask(target.up()));
            }
            if (!mod.getItemStorage().hasItem(ItemHelper.WOOD_DOOR)) {
                setDebugState("Crafting wooden entrance doors");
                return cache(mod, TaskCatalogue.getItemTask("wooden_door", 2));
            }
            BlockPos support = target.down();
            if (!WorldHelper.isSolid(mod, support)) {
                setDebugState("Repairing entrance door support " + (_index + 1) + "/" + _doors.size());
                return cache(mod, new PlaceBlockTask(support,
                        new Block[]{Blocks.COBBLESTONE}, false, true));
            }
            // The entrance is always on the east wall, so approach from inside.
            BlockPos stand = target.offset(Direction.WEST);
            if (mod.getPlayer() == null
                    || stand.getSquaredDistance(mod.getPlayer().getBlockPos()) > 2
                    || mod.getPlayer().getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > 20.25) {
                setDebugState("Approaching entrance door " + (_index + 1) + "/" + _doors.size() + " from inside");
                return cache(mod, new GetToBlockTask(stand));
            }
            setDebugState("Installing protected entrance door " + (_index + 1) + "/" + _doors.size());
            _activeTask = null;
            if (MinecraftClient.getInstance().currentScreen != null) {
                StorageHelper.closeScreen();
                return null;
            }
            if (_doorPlacementCooldown-- > 0) return null;
            if (!mod.getSlotHandler().forceEquipItem(new ItemTarget(ItemHelper.WOOD_DOOR, 1), false)) {
                return cache(mod, TaskCatalogue.getItemTask("wooden_door", 2));
            }
            Vec3d hit = Vec3d.ofCenter(support).add(0, 0.5, 0);
            BlockHitResult result = new BlockHitResult(hit, Direction.UP, support, false);
            ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
            mod.getPlayer().swingHand(Hand.MAIN_HAND);
            _doorPlacementCooldown = 4;
            DebugLogger.getInstance().logImmediate("BASE-DOOR",
                    "repair-place target=" + target + " support=" + support + " action=" + action);
            return null;
        }
        return null;
    }

    private int countMissingDoors(Belfegor mod) {
        int missing = 0;
        for (BlockPos door : _doors) {
            if (!DoorHelper.isDoor(mod, door)) missing++;
        }
        return missing;
    }

    private void rememberDoorsComplete() {
        String dimension = WorldHelper.getCurrentDimension().name();
        for (int i = 0; i < _doors.size() && i < 2; i++) {
            String name = i == 0 ? "entrance_door_a" : "entrance_door_b";
            BaseMemory.getInstance().rememberModule(_home, dimension, name, "fixture",
                    _doors.get(i), 1, 1, 2, "complete",
                    "protected wooden door repaired by validation");
            LocationMemory.getInstance().remember("home_door",
                    _doors.get(i).getX(), _doors.get(i).getY(), _doors.get(i).getZ(), dimension,
                    "protected_double_entrance;door=" + (i + 1) + ";repaired");
        }
        LocationMemory.getInstance().save();
        BaseMemory.getInstance().save();
    }

    private Task cache(Belfegor mod, Task task) {
        if (task == null) return null;
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RepairEntranceDoorsTask task
                && task._home.equals(_home)
                && task._radius == _radius;
    }

    @Override
    protected String toDebugString() {
        return "Repair entrance doors home=" + _home.toShortString()
                + " phase=" + _phase + " doors=" + _doors.size();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
