package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.InventoryManager;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.slots.PlayerSlot;
import adris.belfegor.util.slots.Slot;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic shulker placement used by managed shulker transactions.
 *
 * Generic nearby placement can choose awkward geometry that has air above but
 * is hard to open/reach. For carried shulkers, the safest repeatable behavior
 * is the normal player scaffold motion: stand on solid ground, jump, place the
 * shulker directly under the player, then open it from that known position.
 */
public class PlaceShulkerUnderPlayerTask extends Task {

    private static final int MAX_TICKS = 50;
    private static final double MIN_JUMP_CLEARANCE = 0.35;

    private final Block _shulkerBlock;
    private final int _sourceInventorySlot;
    private final Map<String, Integer> _expectedContents;
    private BlockPos _target;
    private BlockPos _placed;
    private int _ticks;
    private int _placeAttempts;
    private DestroyBlockTask _clearHeadroomTask;

    public PlaceShulkerUnderPlayerTask(Block shulkerBlock) {
        this(shulkerBlock, -1, Map.of());
    }

    public PlaceShulkerUnderPlayerTask(Block shulkerBlock, int sourceInventorySlot,
                                       Map<String, Integer> expectedContents) {
        _shulkerBlock = shulkerBlock;
        _sourceInventorySlot = sourceInventorySlot;
        _expectedContents = expectedContents == null
                ? Map.of()
                : new HashMap<>(expectedContents);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _ticks = 0;
        _placeAttempts = 0;
        _target = null;
        _placed = null;
        _clearHeadroomTask = null;
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(_shulkerBlock.asItem());
    }

    @Override
    protected Task onTick(Belfegor mod) {
        _ticks++;
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        if (!(_shulkerBlock instanceof ShulkerBoxBlock)) {
            setDebugState("Selected block is not a shulker");
            return null;
        }

        if (_placed != null) {
            return null;
        }

        Item item = _shulkerBlock.asItem();
        if (!isExpectedShulkerEquipped(mod, item)) {
            if (!equipExpectedShulker(mod, item)) {
                setDebugState("Equipping selected carried shulker for under-player placement");
                return null;
            }
        }

        if (_target == null
                || !(mod.getWorld().getBlockState(_target).isAir()
                || mod.getWorld().getBlockState(_target).getBlock() instanceof ShulkerBoxBlock)) {
            _target = mod.getPlayer().getBlockPos();
        }
        BlockPos below = _target.down();
        BlockPos head = _target.up();
        BlockPos aboveHead = _target.up(2);

        if (!WorldHelper.isSolid(mod, below) || !mod.getWorld().getBlockState(_target).isAir()) {
            setDebugState("Need solid floor and air at feet for shulker jump-place");
            return null;
        }

        if (!WorldHelper.isAir(mod, head) || !WorldHelper.isAir(mod, aboveHead)) {
            BlockPos blocker = !WorldHelper.isAir(mod, head) ? head : aboveHead;
            if (WorldHelper.canBreak(mod, blocker)) {
                if (_clearHeadroomTask == null
                        || _clearHeadroomTask.stopped()
                        || _clearHeadroomTask.isFinished(mod)) {
                    _clearHeadroomTask = new DestroyBlockTask(blocker);
                }
                setDebugState("Clearing jump-place headroom above shulker position");
                return _clearHeadroomTask;
            }
            setDebugState("Blocked headroom prevents shulker jump-place");
            return null;
        }

        mod.getInputControls().forceLook(mod.getPlayer().getYaw(), 90);
        mod.getInputControls().hold(Input.JUMP);

        double clearance = mod.getPlayer().getY() - Math.floor(mod.getPlayer().getY());
        if (clearance < MIN_JUMP_CLEARANCE && _ticks < MAX_TICKS - 8) {
            setDebugState("Jumping before placing shulker below player");
            return null;
        }

        _placeAttempts++;
        Vec3d hit = Vec3d.ofCenter(below).add(0, 0.5, 0);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, below, false);
        ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
        if (action != ActionResult.FAIL) {
            mod.getPlayer().swingHand(Hand.MAIN_HAND);
            if (mod.getWorld().getBlockState(_target).getBlock() instanceof ShulkerBoxBlock) {
                _placed = _target;
                DebugLogger.getInstance().logImmediate("SHULKER-PLACE",
                        "jump-placed under player pos=" + _placed
                                + " attempts=" + _placeAttempts
                                + " item=" + ItemHelper.stripItemName(item));
            }
            return null;
        }

        if (mod.getWorld().getBlockState(_target).getBlock() instanceof ShulkerBoxBlock) {
            _placed = _target;
            return null;
        }

        setDebugState("Trying shulker jump-place below player attempt " + _placeAttempts);
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getInputControls().release(Input.JUMP);
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _placed != null
                || (_target != null && mod.getWorld() != null
                && mod.getWorld().getBlockState(_target).getBlock() instanceof ShulkerBoxBlock)
                || _ticks > MAX_TICKS;
    }

    public boolean failed(Belfegor mod) {
        return _placed == null
                && _ticks > MAX_TICKS
                && (_target == null || mod.getWorld() == null
                || !(mod.getWorld().getBlockState(_target).getBlock() instanceof ShulkerBoxBlock));
    }

    public BlockPos getPlaced() {
        return _placed != null ? _placed : _target;
    }

    private boolean equipExpectedShulker(Belfegor mod, Item item) {
        if (mod.getPlayer() == null) return false;
        mod.getPlayer().getInventory().selectedSlot = 1;
        if (_sourceInventorySlot >= 0
                && _sourceInventorySlot < mod.getPlayer().getInventory().main.size()) {
            ItemStack source = mod.getPlayer().getInventory().main.get(_sourceInventorySlot);
            if (!source.isEmpty()
                    && source.getItem() == item
                    && contentsMatch(mod, source)) {
                Slot sourceSlot = Slot.getFromCurrentScreenInventory(_sourceInventorySlot);
                Slot targetSlot = PlayerSlot.getEquipSlot();
                if (sourceSlot != null && targetSlot != null) {
                    DebugLogger.getInstance().logImmediate("SHULKER-EQUIP",
                            "equipping exact shulker sourceSlot=" + _sourceInventorySlot
                                    + " hotbar=" + targetSlot.getInventorySlot()
                                    + " expected=" + _expectedContents);
                    mod.getSlotHandler().forceEquipSlot(sourceSlot);
                    return isExpectedShulkerEquipped(mod, item);
                }
            }
        }
        DebugLogger.getInstance().logImmediate("SHULKER-EQUIP",
                "falling back to item equip sourceSlot=" + _sourceInventorySlot
                        + " expected=" + _expectedContents);
        return mod.getSlotHandler().forceEquipItem(item)
                && isExpectedShulkerEquipped(mod, item);
    }

    private boolean isExpectedShulkerEquipped(Belfegor mod, Item item) {
        if (!StorageHelper.isEquipped(mod, item)) return false;
        ItemStack equipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        return !equipped.isEmpty()
                && equipped.getItem() == item
                && contentsMatch(mod, equipped);
    }

    private boolean contentsMatch(Belfegor mod, ItemStack stack) {
        if (_expectedContents.isEmpty()) return true;
        return new InventoryManager(mod).readShulkerContents(stack).equals(_expectedContents);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceShulkerUnderPlayerTask task
                && task._shulkerBlock == _shulkerBlock
                && task._sourceInventorySlot == _sourceInventorySlot
                && task._expectedContents.equals(_expectedContents);
    }

    @Override
    protected String toDebugString() {
        return "Jump-place shulker under player";
    }
}
