package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.ItemInfo.ToolIdentifier;
import adris.altoclef.debug.DebugLogger;
import adris.altoclef.memory.ShulkerMemory;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.InventoryManager;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Treats a carried shulker as a reusable sub-inventory:
 * select, place, open, transfer, catalog twice, close, break, and pick up.
 */
public class ShulkerInteractionTask extends Task implements ITaskCanForce {

    public enum Mode { STORE, RETRIEVE }

    private enum Phase {
        GET_SHULKER, PLACE, OPEN, TRANSFER, CATALOG, BREAK, PICKUP, RESTORE, DONE
    }

    private final Mode _mode;
    private final ItemTarget[] _targets;
    private Phase _phase = Phase.GET_SHULKER;
    private Item _selectedShulker;
    private BlockPos _placePos;
    private PlaceBlockNearbyTask _placeTask;
    private InteractWithBlockTask _openTask;
    private DestroyBlockTask _breakTask;
    private DestroyBlockTask _clearAboveTask;
    private int _noProgressTicks;
    private int _catalogPass;
    private int _pickupTicks;
    private InventoryManager _inventory;
    private int _originalInventorySlot = -1;
    private int _initialSelectedCount;
    private final Map<Item, Integer> _movedCounts = new HashMap<>();
    private Slot _transferSource;
    private Slot _transferDestination;
    private Item _transferItem;
    private int _transferRemaining;
    private Map<String, Integer> _verifiedContents = new HashMap<>();
    private PickupDroppedItemTask _pickupTask;

    public ShulkerInteractionTask(Mode mode, ItemTarget... targets) {
        _mode = mode;
        _targets = targets;
    }

    public static boolean hasCarriedShulker(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return false;
        InventoryManager inventory = new InventoryManager(mod);
        return mod.getPlayer().getInventory().main.stream()
                .anyMatch(stack -> !stack.isEmpty() && inventory.isShulkerBox(stack.getItem()));
    }

    public static boolean carriedShulkerContains(AltoClef mod, ItemTarget target) {
        if (mod == null || mod.getPlayer() == null || target == null) return false;
        InventoryManager inventory = new InventoryManager(mod);
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack.isEmpty() || !inventory.isShulkerBox(stack.getItem())) continue;
            Map<String, Integer> contents = inventory.readShulkerContents(stack);
            for (Item item : target.getMatches()) {
                if (inventory.isShulkerBox(item)) continue;
                if (contents.getOrDefault(ItemHelper.stripItemName(item), 0) > 0) return true;
            }
        }
        return false;
    }

    public static ItemTarget[] getAutoStoreTargets(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return new ItemTarget[0];
        InventoryManager inventory = new InventoryManager(mod);
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack.isEmpty() || inventory.isShulkerBox(stack.getItem())) continue;
            String name = ItemHelper.stripItemName(stack.getItem());
            if (ToolIdentifier.isTool(name)) continue;
            if (isProtectedAutoStoreStack(stack)) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        ArrayList<ItemTarget> result = new ArrayList<>();
        counts.forEach((item, count) -> result.add(new ItemTarget(item, count)));
        return result.toArray(ItemTarget[]::new);
    }

    private static boolean isProtectedAutoStoreStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        Item item = stack.getItem();
        String name = ItemHelper.stripItemName(item);
        if (item instanceof ArmorItem) return true;
        if (stack.isDamageable()) return true;
        return name.endsWith("_helmet")
                || name.endsWith("_chestplate")
                || name.endsWith("_leggings")
                || name.endsWith("_boots")
                || name.endsWith("_sword")
                || name.endsWith("_pickaxe")
                || name.endsWith("_axe")
                || name.endsWith("_shovel")
                || name.endsWith("_hoe")
                || name.equals("shield")
                || name.equals("bow")
                || name.equals("crossbow")
                || name.equals("trident")
                || name.equals("mace")
                || name.equals("flint_and_steel")
                || name.equals("shears")
                || name.equals("fishing_rod");
    }

    @Override
    protected void onStart(AltoClef mod) {
        _phase = Phase.GET_SHULKER;
        _selectedShulker = null;
        _placePos = null;
        _placeTask = null;
        _openTask = null;
        _breakTask = null;
        _clearAboveTask = null;
        _noProgressTicks = 0;
        _catalogPass = 0;
        _pickupTicks = 0;
        _inventory = new InventoryManager(mod);
        _originalInventorySlot = -1;
        _initialSelectedCount = 0;
        _movedCounts.clear();
        clearTransfer();
        _verifiedContents = new HashMap<>();
        _pickupTask = null;
        mod.getBehaviour().push();
        mod.getBehaviour().avoidWalkingThrough(pos ->
                _placePos != null && (pos.equals(_placePos) || pos.equals(_placePos.up())));
        syncCarriedShulkerMemory(mod);
        logState("start", mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_phase) {
            case GET_SHULKER: {
                if (requestAlreadySatisfied(mod)) {
                    transition(Phase.DONE, mod, "request already satisfied");
                    return null;
                }
                ItemStack selected = selectCarriedShulker(mod);
                if (selected.isEmpty()) {
                    DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                            "no carried shulker available; refusing catalogue request for multi-match shulker target");
                    transition(Phase.DONE, mod, "no carried shulker available");
                    return null;
                }
                _selectedShulker = selected.getItem();
                _originalInventorySlot = findInventorySlot(mod, selected);
                _initialSelectedCount = countCarriedShulkers(mod, _selectedShulker);
                transition(Phase.PLACE, mod, "selected " + ItemHelper.stripItemName(_selectedShulker));
                return null;
            }

            case PLACE: {
                if (_placeTask != null && _placeTask.isFinished(mod)) {
                    _placePos = _placeTask.getPlaced();
                    _noProgressTicks = 0;
                    transition(Phase.OPEN, mod, "placed at " + _placePos);
                    return null;
                }
                if (_selectedShulker == null || !hasCarriedShulker(mod, _selectedShulker)) {
                    BlockPos nearby = findNearbyShulker(mod);
                    if (nearby != null) {
                        _placePos = nearby;
                        _noProgressTicks = 0;
                        transition(Phase.OPEN, mod,
                                "selected shulker left inventory; found placed shulker at " + nearby);
                    } else if (++_noProgressTicks > 20) {
                        DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                                "selected shulker left inventory but no nearby placed shulker was found; selected="
                                        + _selectedShulker);
                        transition(Phase.DONE, mod, "placed shulker could not be located");
                    } else {
                        setDebugState("Waiting for placed shulker block to appear");
                    }
                    return null;
                }
                Block block = Block.getBlockFromItem(_selectedShulker);
                if (!(block instanceof ShulkerBoxBlock)) {
                    DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                            "selected item is not a shulker block: " + _selectedShulker);
                    transition(Phase.DONE, mod, "selected item is not a shulker block");
                    return null;
                }
                if (_placeTask == null || _placeTask.stopped()) {
                    _placeTask = new PlaceBlockNearbyTask(canPlace -> {
                        BlockPos below = canPlace.down();
                        return WorldHelper.isSolid(mod, below)
                                && !WorldHelper.isInsidePlayer(mod, canPlace)
                                && canPlace.getSquaredDistance(mod.getPlayer().getBlockPos()) > 2
                                && WorldHelper.isAir(mod, canPlace.up());
                    }, block);
                }
                if (_placeTask.isFinished(mod)) {
                    _placePos = _placeTask.getPlaced();
                    transition(Phase.OPEN, mod, "placed at " + _placePos);
                    return null;
                }
                setDebugState("Placing carried shulker");
                return _placeTask;
            }

            case OPEN: {
                if (isShulkerOpen()) {
                    transition(Phase.TRANSFER, mod, "shulker screen open");
                    return null;
                }
                if (_placePos == null || !(mod.getWorld().getBlockState(_placePos).getBlock() instanceof ShulkerBoxBlock)) {
                    _placePos = findNearbyShulker(mod);
                }
                if (_placePos == null) {
                    transition(Phase.PLACE, mod, "placed shulker not found");
                    _placeTask = null;
                    return null;
                }
                if (!WorldHelper.isAir(mod, _placePos.up())) {
                    if (!WorldHelper.canBreak(mod, _placePos.up())) {
                        DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                                "cannot open managed shulker because block above is blocked and unbreakable pos="
                                        + _placePos + " above=" + _placePos.up());
                        transition(Phase.BREAK, mod, "blocked above by unbreakable block");
                        return null;
                    }
                    if (_clearAboveTask == null || _clearAboveTask.stopped()) {
                        _clearAboveTask = new DestroyBlockTask(_placePos.up());
                    }
                    setDebugState("Clearing block above shulker before opening");
                    return _clearAboveTask;
                }
                if (_openTask == null || _openTask.stopped()) {
                    _openTask = new InteractWithBlockTask(_placePos);
                }
                setDebugState("Opening shulker at " + _placePos.toShortString());
                return _openTask;
            }

            case TRANSFER: {
                if (!isShulkerOpen()) {
                    transition(Phase.OPEN, mod, "screen closed during transfer");
                    _openTask = null;
                    return null;
                }
                if (transferComplete(mod)) {
                    _catalogPass = 0;
                    transition(Phase.CATALOG, mod, "requested transfer complete");
                    return null;
                }
                if (continueTransfer(mod) || beginOneTransfer(mod)) {
                    _noProgressTicks = 0;
                } else if (++_noProgressTicks > 40) {
                    transition(Phase.CATALOG, mod, "no transferable slot or shulker full");
                }
                return null;
            }

            case CATALOG: {
                if (!isShulkerOpen()) {
                    transition(Phase.OPEN, mod, "screen closed before catalog");
                    _openTask = null;
                    return null;
                }
                scanOpenContents(mod);
                _verifiedContents = readOpenContents();
                _catalogPass++;
                DebugLogger.getInstance().logImmediate("SHULKER-CATALOG",
                        "pass=" + _catalogPass + " pos=" + _placePos
                                + " contents=" + readOpenContents());
                if (_catalogPass >= 2) {
                    transition(Phase.BREAK, mod, "catalog verified twice");
                }
                return null;
            }

            case BREAK: {
                if (isShulkerOpen()) {
                    StorageHelper.closeScreen();
                    return null;
                }
                if (_placePos == null
                        || !(mod.getWorld().getBlockState(_placePos).getBlock() instanceof ShulkerBoxBlock)) {
                    transition(Phase.PICKUP, mod, "shulker block removed");
                    return null;
                }
                if (_breakTask == null || _breakTask.stopped()) {
                    _breakTask = new DestroyBlockTask(_placePos);
                }
                setDebugState("Breaking shulker for pickup");
                return _breakTask;
            }

            case PICKUP: {
                _pickupTicks++;
                if (_selectedShulker != null
                        && countCarriedShulkers(mod, _selectedShulker) >= _initialSelectedCount) {
                    if (_placePos != null) {
                        ShulkerMemory.getInstance().forgetContents(_placePos);
                    }
                    syncCarriedShulkerMemory(mod);
                    transition(Phase.RESTORE, mod, "shulker recovered into inventory");
                    return null;
                }
                if (_selectedShulker != null
                        && mod.getEntityTracker().itemDropped(
                        new ItemTarget(_selectedShulker, _initialSelectedCount))) {
                    if (_pickupTask == null || _pickupTask.stopped()) {
                        _pickupTask = new PickupDroppedItemTask(
                                _selectedShulker, _initialSelectedCount, true);
                    }
                    setDebugState("Picking up managed shulker");
                    return _pickupTask;
                }
                if (_pickupTicks > 200) {
                    DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                            "pickup timeout selected=" + _selectedShulker + " pos=" + _placePos);
                }
                return null;
            }

            case RESTORE: {
                if (restoreOriginalSlot(mod)) {
                    syncCarriedShulkerMemory(mod);
                    transition(Phase.DONE, mod, "transaction complete and slot restored");
                }
                return null;
            }

            case DONE:
                return null;
        }
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _phase == Phase.DONE;
    }

    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        boolean inTransaction = _phase != Phase.DONE && _phase != Phase.GET_SHULKER;
        boolean cursorHeld = !StorageHelper.getItemStackInCursorSlot().isEmpty();
        boolean shulkerOpen = isShulkerOpen();
        boolean force = inTransaction || cursorHeld || shulkerOpen;
        if (force) {
            DebugLogger.getInstance().logImmediate("SHULKER-FORCE",
                    "continuing transaction phase=" + _phase
                            + " pos=" + _placePos
                            + " cursor=" + StorageHelper.getItemStackInCursorSlot()
                            + " interrupt="
                            + (interruptingCandidate == null ? "null" : interruptingCandidate.toString()));
        }
        return force;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_transferSource != null && !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            _inventory.resetSameSlotGuard();
            _inventory.placeAllInSlot(_transferSource);
            clearTransfer();
        }
        if (isShulkerOpen()) {
            scanOpenContents(mod);
            StorageHelper.closeScreen();
        }
        syncCarriedShulkerMemory(mod);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ShulkerInteractionTask task
                && task._mode == _mode
                && Arrays.equals(task._targets, _targets);
    }

    @Override
    protected String toDebugString() {
        return "ShulkerInteraction[" + _mode + "] " + Arrays.toString(_targets);
    }

    private boolean requestAlreadySatisfied(AltoClef mod) {
        if (_mode == Mode.STORE) {
            return _targets.length == 0
                    || Arrays.stream(_targets).allMatch(target -> target.getTargetCount() <= 0);
        }
        return Arrays.stream(_targets).allMatch(target ->
                mod.getItemStorage().getItemCountInventoryOnly(target.getMatches())
                        >= target.getTargetCount());
    }

    private boolean transferComplete(AltoClef mod) {
        if (_transferSource != null || !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        if (_mode == Mode.STORE) {
            return Arrays.stream(_targets).allMatch(target ->
                    movedForTarget(target) >= target.getTargetCount());
        }
        return Arrays.stream(_targets).allMatch(target ->
                mod.getItemStorage().getItemCountInventoryOnly(target.getMatches())
                        >= target.getTargetCount());
    }

    private boolean beginOneTransfer(AltoClef mod) {
        if (_transferSource != null || !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        for (ItemTarget target : _targets) {
            for (Item item : target.getMatches()) {
                if (_mode == Mode.STORE) {
                    int needed = target.getTargetCount() - movedForTarget(target);
                    if (needed <= 0) continue;
                    if (_inventory.isShulkerBox(item)) {
                        _movedCounts.merge(item, needed, Integer::sum);
                        DebugLogger.getInstance().logImmediate("SHULKER-TRANSFER",
                                "skip nested-shulker store target item=" + ItemHelper.stripItemName(item)
                                        + " amount=" + needed);
                        continue;
                    }
                    for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, item)) {
                        ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                        if (stack.isEmpty()) continue;
                        for (Slot destination : mod.getItemStorage()
                                .getSlotsThatCanFitInOpenContainer(stack, true)) {
                            int room = roomInSlot(destination, stack);
                            if (room <= 0) continue;
                            return startTransfer(slot, destination, item,
                                    Math.min(needed, Math.min(stack.getCount(), room)));
                        }
                    }
                } else {
                    int have = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
                    if (have >= target.getTargetCount()) continue;
                    int needed = target.getTargetCount() - have;
                    for (Slot slot : mod.getItemStorage().getSlotsWithItemContainer(item)) {
                        ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                        if (stack.isEmpty()) continue;
                        for (Slot destination : mod.getItemStorage()
                                .getSlotsThatCanFitInPlayerInventory(stack, true)) {
                            int room = roomInSlot(destination, stack);
                            if (room <= 0) continue;
                            return startTransfer(slot, destination, item,
                                    Math.min(needed, Math.min(stack.getCount(), room)));
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean startTransfer(Slot source, Slot destination, Item item, int amount) {
        if (amount <= 0 || !_inventory.isCursorEmpty()) return false;
        _transferSource = source;
        _transferDestination = destination;
        _transferItem = item;
        _transferRemaining = amount;
        logTransfer(_mode == Mode.STORE ? "store-begin" : "retrieve-begin",
                source, StorageHelper.getItemStackInSlot(source));
        return _inventory.pickupFromSlot(source);
    }

    private boolean continueTransfer(AltoClef mod) {
        if (_transferSource == null) return false;
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (_transferRemaining > 0) {
            if (cursor.isEmpty() || cursor.getItem() != _transferItem) {
                clearTransfer();
                return false;
            }
            int before = cursor.getCount();
            _inventory.resetSameSlotGuard();
            if (_inventory.placeOneInSlot(_transferDestination)) {
                int after = StorageHelper.getItemStackInCursorSlot().getCount();
                if (after < before) {
                    _transferRemaining--;
                    _movedCounts.merge(_transferItem, 1, Integer::sum);
                    DebugLogger.getInstance().logImmediate("SHULKER-TRANSFER",
                            (_mode == Mode.STORE ? "stored" : "retrieved")
                                    + " one " + ItemHelper.stripItemName(_transferItem)
                                    + " remaining=" + _transferRemaining);
                }
            }
            return true;
        }
        if (!cursor.isEmpty()) {
            _inventory.resetSameSlotGuard();
            _inventory.placeAllInSlot(_transferSource);
            return true;
        }
        clearTransfer();
        return true;
    }

    private void clearTransfer() {
        _transferSource = null;
        _transferDestination = null;
        _transferItem = null;
        _transferRemaining = 0;
    }

    private int movedForTarget(ItemTarget target) {
        int moved = 0;
        for (Item item : target.getMatches()) {
            moved += _movedCounts.getOrDefault(item, 0);
        }
        return moved;
    }

    private int roomInSlot(Slot slot, ItemStack moving) {
        ItemStack present = StorageHelper.getItemStackInSlot(slot);
        if (present.isEmpty()) return moving.getMaxCount();
        if (!ItemHelper.canStackTogether(moving, present)) return 0;
        return present.getMaxCount() - present.getCount();
    }

    private int findInventorySlot(AltoClef mod, ItemStack selected) {
        for (int i = 0; i < mod.getPlayer().getInventory().main.size(); i++) {
            if (mod.getPlayer().getInventory().main.get(i) == selected) return i;
        }
        for (int i = 0; i < mod.getPlayer().getInventory().main.size(); i++) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(i);
            if (!stack.isEmpty() && stack.getItem() == selected.getItem()
                    && _inventory.readShulkerContents(stack)
                    .equals(_inventory.readShulkerContents(selected))) {
                return i;
            }
        }
        return -1;
    }

    private int countCarriedShulkers(AltoClef mod, Item item) {
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private boolean restoreOriginalSlot(AltoClef mod) {
        if (_originalInventorySlot < 0
                || _originalInventorySlot >= mod.getPlayer().getInventory().main.size()) {
            return true;
        }
        int current = findRecoveredShulkerSlot(mod);
        if (current < 0) return false;
        if (current == _originalInventorySlot) return true;

        ItemStack original = mod.getPlayer().getInventory().main.get(_originalInventorySlot);
        if (!original.isEmpty()) {
            int empty = findEmptyInventoryIndex(mod, current, _originalInventorySlot);
            if (empty < 0) {
                DebugLogger.getInstance().logImmediate("SHULKER-RESTORE",
                        "original slot occupied and no empty slot available; keeping slot=" + current);
                return true;
            }
            Slot occupiedSlot = Slot.getFromCurrentScreenInventory(_originalInventorySlot);
            Slot emptySlot = Slot.getFromCurrentScreenInventory(empty);
            _inventory.resetSameSlotGuard();
            if (_inventory.leftClick(occupiedSlot)) {
                _inventory.leftClick(emptySlot);
            }
            return false;
        }

        Slot currentSlot = Slot.getFromCurrentScreenInventory(current);
        Slot originalSlot = Slot.getFromCurrentScreenInventory(_originalInventorySlot);
        _inventory.resetSameSlotGuard();
        if (_inventory.leftClick(currentSlot)) {
            _inventory.leftClick(originalSlot);
        }
        boolean restored = !mod.getPlayer().getInventory().main.get(_originalInventorySlot).isEmpty()
                && mod.getPlayer().getInventory().main.get(_originalInventorySlot).getItem()
                == _selectedShulker;
        DebugLogger.getInstance().logImmediate("SHULKER-RESTORE",
                "from=" + current + " to=" + _originalInventorySlot + " restored=" + restored);
        return restored;
    }

    private int findRecoveredShulkerSlot(AltoClef mod) {
        int fallback = -1;
        for (int i = 0; i < mod.getPlayer().getInventory().main.size(); i++) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(i);
            if (stack.isEmpty() || stack.getItem() != _selectedShulker) continue;
            if (fallback < 0) fallback = i;
            if (!_verifiedContents.isEmpty()
                    && _inventory.readShulkerContents(stack).equals(_verifiedContents)) {
                return i;
            }
        }
        return fallback;
    }

    private int findEmptyInventoryIndex(AltoClef mod, int... excluded) {
        outer:
        for (int i = 0; i < mod.getPlayer().getInventory().main.size(); i++) {
            for (int skip : excluded) if (i == skip) continue outer;
            if (mod.getPlayer().getInventory().main.get(i).isEmpty()) return i;
        }
        return -1;
    }

    private ItemStack selectCarriedShulker(AltoClef mod) {
        InventoryManager inventory = new InventoryManager(mod);
        ItemStack best = ItemStack.EMPTY;
        int bestScore = Integer.MIN_VALUE;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack.isEmpty() || !inventory.isShulkerBox(stack.getItem())) continue;
            Map<String, Integer> contents = inventory.readShulkerContents(stack);
            int matching = 0;
            int total = 0;
            for (int count : contents.values()) total += count;
            for (ItemTarget target : _targets) {
                for (Item item : target.getMatches()) {
                    matching += contents.getOrDefault(ItemHelper.stripItemName(item), 0);
                }
            }
            int score = _mode == Mode.RETRIEVE
                    ? matching * 1000
                    : matching * 1000 + Math.max(0, 1728 - total);
            if (_mode == Mode.RETRIEVE && matching == 0) continue;
            if (score > bestScore) {
                bestScore = score;
                best = stack;
            }
        }
        // For STORE an empty/new shulker is valid. RETRIEVE deliberately fails
        // when no carried shulker's NBT contains the requested item.
        return best;
    }

    private boolean hasCarriedShulker(AltoClef mod, Item item) {
        return !mod.getItemStorage().getSlotsWithItemPlayerInventory(false, item).isEmpty();
    }

    private boolean isShulkerOpen() {
        return net.minecraft.client.MinecraftClient.getInstance().player != null
                && net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler
                instanceof ShulkerBoxScreenHandler;
    }

    private BlockPos findNearbyShulker(AltoClef mod) {
        BlockPos origin = mod.getPlayer().getBlockPos();
        BlockPos closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos check = origin.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(check).getBlock() instanceof ShulkerBoxBlock) {
                        double distance = check.getSquaredDistance(origin);
                        if (distance < closestDistance) {
                            closest = check;
                            closestDistance = distance;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private Map<String, Integer> readOpenContents() {
        Map<String, Integer> contents = new HashMap<>();
        if (!isShulkerOpen()) return contents;
        var handler = net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler;
        int containerSlots = handler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty()) {
                contents.merge(ItemHelper.stripItemName(stack.getItem()),
                        stack.getCount(), Integer::sum);
            }
        }
        return contents;
    }

    private void scanOpenContents(AltoClef mod) {
        if (_placePos == null || !isShulkerOpen()) return;
        ShulkerMemory.getInstance().rememberContents(_placePos, readOpenContents());
        ShulkerMemory.getInstance().save();
    }

    public static void syncCarriedShulkerMemory(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        InventoryManager inventory = new InventoryManager(mod);
        ShulkerMemory memory = ShulkerMemory.getInstance();
        memory.clearInventoryEntries();
        for (int slot = 0; slot < mod.getPlayer().getInventory().main.size(); slot++) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(slot);
            if (!stack.isEmpty() && inventory.isShulkerBox(stack.getItem())) {
                memory.rememberInventoryContents(slot,
                        ItemHelper.stripItemName(stack.getItem()),
                        inventory.readShulkerContents(stack));
            }
        }
        memory.save();
    }

    private void transition(Phase next, AltoClef mod, String reason) {
        DebugLogger.getInstance().logImmediate("SHULKER-STATE",
                _phase + " -> " + next + " reason=" + reason
                        + " pos=" + _placePos
                        + " selected=" + (_selectedShulker == null
                        ? "none" : ItemHelper.stripItemName(_selectedShulker)));
        _phase = next;
        setDebugState(next + ": " + reason);
    }

    private void logState(String reason, AltoClef mod) {
        DebugLogger.getInstance().logImmediate("SHULKER-STATE",
                "phase=" + _phase + " reason=" + reason
                        + " cursor=" + StorageHelper.getItemStackInCursorSlot()
                        + " targets=" + Arrays.toString(_targets));
    }

    private void logTransfer(String operation, Slot slot, ItemStack stack) {
        DebugLogger.getInstance().logImmediate("SHULKER-TRANSFER",
                operation + " slot=" + slot.getWindowSlot()
                        + " stack=" + stack.getCount() + "x"
                        + ItemHelper.stripItemName(stack.getItem()));
    }
}
