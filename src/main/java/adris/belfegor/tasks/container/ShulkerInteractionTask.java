package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.ItemInfo.ToolIdentifier;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.ShulkerMemory;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.construction.PlaceBlockNearbyTask;
import adris.belfegor.tasks.construction.PlaceShulkerUnderPlayerTask;
import adris.belfegor.tasks.movement.PickupDroppedItemTask;
import adris.belfegor.tasksystem.ITaskCanForce;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.InventoryManager;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.slots.RawSlot;
import adris.belfegor.util.slots.Slot;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Treats a carried shulker as a reusable sub-inventory:
 * select, place, open, transfer, catalog twice, close, break, and pick up.
 */
public class ShulkerInteractionTask extends Task implements ITaskCanForce {

    private static UUID ACTIVE_TRANSACTION = null;
    private static long ACTIVE_TRANSACTION_STARTED_MS = 0;
    private static final long TRANSACTION_STALE_MS = 60_000L;
    private static final long OPEN_RETRY_COOLDOWN_MS = 750L;
    private static final int MAX_OPEN_ATTEMPTS = 8;
    private static final long SHULKER_CHECK_LOG_COOLDOWN_MS = 5_000L;
    private static final Map<String, Long> LAST_SHULKER_CHECK_LOG_MS = new HashMap<>();

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
    private PlaceShulkerUnderPlayerTask _jumpPlaceTask;
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
    private Map<String, Integer> _selectedExpectedContents = new HashMap<>();
    private final Set<Integer> _failedRetrieveSlots = new HashSet<>();
    private final Set<String> _failedRetrieveFingerprints = new HashSet<>();
    private PickupDroppedItemTask _pickupTask;
    private UUID _transactionId = UUID.randomUUID();
    private long _lastOpenAttemptMs = 0;
    private int _openAttempts = 0;
    private long _lastPickupTimeoutLogMs = 0;

    public ShulkerInteractionTask(Mode mode, ItemTarget... targets) {
        _mode = mode;
        _targets = targets;
    }

    public static boolean hasCarriedShulker(Belfegor mod) {
        if (mod == null || mod.getPlayer() == null) return false;
        InventoryManager inventory = new InventoryManager(mod);
        return mod.getPlayer().getInventory().main.stream()
                .anyMatch(stack -> !stack.isEmpty() && inventory.isShulkerBox(stack.getItem()));
    }

    public static boolean carriedShulkerContains(Belfegor mod, ItemTarget target) {
        if (mod == null || mod.getPlayer() == null || target == null) return false;
        InventoryManager inventory = new InventoryManager(mod);
        syncCarriedShulkerMemory(mod);
        boolean sawShulker = false;
        ArrayList<String> shulkerSummaries = new ArrayList<>();
        for (int invSlot = 0; invSlot < mod.getPlayer().getInventory().main.size(); invSlot++) {
            final int slotIndex = invSlot;
            ItemStack stack = mod.getPlayer().getInventory().main.get(invSlot);
            if (stack.isEmpty() || !inventory.isShulkerBox(stack.getItem())) continue;
            sawShulker = true;
            Map<String, Integer> contents = inventory.readShulkerContents(stack);
            shulkerSummaries.add("invSlot=" + invSlot + " "
                    + ItemHelper.stripItemName(stack.getItem()) + "=" + contents);
            for (Item item : target.getMatches()) {
                if (inventory.isShulkerBox(item)) continue;
                String itemName = ItemHelper.stripItemName(item);
                if (contents.getOrDefault(itemName, 0) > 0
                        || ShulkerMemory.getInstance().bestInventoryShulkerFor(itemName)
                        .filter(entry -> entry.inventorySlot == slotIndex)
                        .isPresent()) {
                    logShulkerCheck(target, true, sawShulker,
                            shulkerSummaries, buildInventorySummary(mod));
                    return true;
                }
            }
        }
        logShulkerCheck(target, false, sawShulker,
                shulkerSummaries, buildInventorySummary(mod));
        return false;
    }

    private static void logShulkerCheck(ItemTarget target, boolean found, boolean sawShulker,
                                        ArrayList<String> shulkerSummaries,
                                        String inventorySummary) {
        String key = target + "|" + found + "|" + sawShulker + "|" + shulkerSummaries;
        long now = System.currentTimeMillis();
        long last = LAST_SHULKER_CHECK_LOG_MS.getOrDefault(key, 0L);
        if (now - last < SHULKER_CHECK_LOG_COOLDOWN_MS) return;
        LAST_SHULKER_CHECK_LOG_MS.put(key, now);
        DebugLogger.getInstance().logImmediate("SHULKER-CHECK",
                "target=" + target
                        + " result=" + found
                        + " carriedShulker=" + sawShulker
                        + " shulkers=" + shulkerSummaries
                        + " inventory=" + inventorySummary);
    }

    private static String buildInventorySummary(Belfegor mod) {
        if (mod == null || mod.getPlayer() == null) return "[]";
        ArrayList<String> stacks = new ArrayList<>();
        for (int slot = 0; slot < mod.getPlayer().getInventory().main.size(); slot++) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(slot);
            if (stack.isEmpty()) continue;
            stacks.add(slot + ":" + stack.getCount() + "x"
                    + ItemHelper.stripItemName(stack.getItem()));
        }
        return stacks.toString();
    }

    public static ItemTarget[] getAutoStoreTargets(Belfegor mod) {
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
    protected void onStart(Belfegor mod) {
        _phase = Phase.GET_SHULKER;
        _selectedShulker = null;
        _placePos = null;
        _placeTask = null;
        _jumpPlaceTask = null;
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
        _selectedExpectedContents = new HashMap<>();
        _failedRetrieveSlots.clear();
        _failedRetrieveFingerprints.clear();
        _pickupTask = null;
        _transactionId = UUID.randomUUID();
        _lastOpenAttemptMs = 0;
        _openAttempts = 0;
        mod.getBehaviour().push();
        mod.getBehaviour().avoidWalkingThrough(pos ->
                _placePos != null && (pos.equals(_placePos) || pos.equals(_placePos.up())));
        syncCarriedShulkerMemory(mod);
        logState("start", mod);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_phase == Phase.DONE) {
            releaseTransactionLock();
            return null;
        }
        if (!acquireTransactionLock(mod)) {
            setDebugState("Waiting for active shulker transaction lock");
            return null;
        }
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
                _selectedExpectedContents = new HashMap<>(_inventory.readShulkerContents(selected));
                _initialSelectedCount = countCarriedShulkers(mod, _selectedShulker);
                transition(Phase.PLACE, mod, "selected " + ItemHelper.stripItemName(_selectedShulker));
                return null;
            }

            case PLACE: {
                Block block = Block.getBlockFromItem(_selectedShulker);
                if (!(block instanceof ShulkerBoxBlock)) {
                    DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                            "selected item is not a shulker block: " + _selectedShulker);
                    transition(Phase.DONE, mod, "selected item is not a shulker block");
                    return null;
                }
                if (_jumpPlaceTask == null || (_jumpPlaceTask.stopped() && !_jumpPlaceTask.failed(mod))) {
                    _jumpPlaceTask = new PlaceShulkerUnderPlayerTask(block,
                            _originalInventorySlot, _selectedExpectedContents);
                }
                if (_jumpPlaceTask != null && _jumpPlaceTask.isFinished(mod) && !_jumpPlaceTask.failed(mod)) {
                    _placePos = _jumpPlaceTask.getPlaced();
                    rememberPlacedPosition("jump-placed-under-player");
                    _noProgressTicks = 0;
                    transition(Phase.OPEN, mod, "jump-placed under player at " + _placePos);
                    return null;
                }
                if (_jumpPlaceTask != null && !_jumpPlaceTask.failed(mod)) {
                    setDebugState("Jump-placing carried shulker under player");
                    return _jumpPlaceTask;
                }

                if (_placeTask != null && _placeTask.isFinished(mod)) {
                    _placePos = _placeTask.getPlaced();
                    rememberPlacedPosition("placed-by-task");
                    _noProgressTicks = 0;
                    transition(Phase.OPEN, mod, "placed at " + _placePos);
                    return null;
                }
                if (_selectedShulker == null || !hasCarriedShulker(mod, _selectedShulker)) {
                    BlockPos nearby = findNearbyShulker(mod);
                    if (nearby != null) {
                        _placePos = nearby;
                        rememberPlacedPosition("found-nearby-after-place");
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
                if (_placeTask == null || _placeTask.stopped()) {
                    DebugLogger.getInstance().logImmediate("SHULKER-PLACE",
                            "jump-place failed; falling back to nearby placement selected="
                                    + ItemHelper.stripItemName(_selectedShulker));
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
                    rememberPlacedPosition("placed-by-task");
                    transition(Phase.OPEN, mod, "placed at " + _placePos);
                    return null;
                }
                setDebugState("Placing carried shulker");
                return _placeTask;
            }

            case OPEN: {
                if (isShulkerOpen()) {
                    _openAttempts = 0;
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
                rememberPlacedPosition("open-phase");
                if (_openAttempts >= MAX_OPEN_ATTEMPTS) {
                    DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                            "open retry limit reached pos=" + _placePos
                                    + " attempts=" + _openAttempts
                                    + " selected=" + (_selectedShulker == null ? "none"
                                    : ItemHelper.stripItemName(_selectedShulker)));
                    transition(Phase.BREAK, mod, "open retry limit reached");
                    return null;
                }
                long now = System.currentTimeMillis();
                if (now - _lastOpenAttemptMs < OPEN_RETRY_COOLDOWN_MS) {
                    setDebugState("Waiting before shulker open retry "
                            + _openAttempts + "/" + MAX_OPEN_ATTEMPTS);
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
                    _lastOpenAttemptMs = now;
                    _openAttempts++;
                    DebugLogger.getInstance().logImmediate("SHULKER-OPEN",
                            "attempt=" + _openAttempts + "/" + MAX_OPEN_ATTEMPTS
                                    + " pos=" + _placePos
                                    + " above=" + _placePos.up());
                }
                setDebugState("Opening shulker at " + _placePos.toShortString());
                return _openTask;
            }

            case TRANSFER: {
                if (!isShulkerOpen()) {
                    _lastOpenAttemptMs = System.currentTimeMillis();
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
                    if (_mode == Mode.RETRIEVE && !openShulkerHasNeededItem(mod)) {
                        rememberFailedRetrieveCandidate();
                        transition(Phase.CATALOG, mod,
                                "selected shulker lacks requested item; will retry another candidate");
                    } else {
                        DebugLogger.getInstance().logImmediate("SHULKER-TRANSFER",
                                "no transfer possible mode=" + _mode
                                        + " openContents=" + readOpenContents()
                                        + " targets=" + Arrays.toString(_targets)
                                        + " inventory=" + buildInventorySummary(mod));
                        transition(Phase.CATALOG, mod, "no transferable slot or shulker full");
                    }
                }
                return null;
            }

            case CATALOG: {
                if (!isShulkerOpen()) {
                    _lastOpenAttemptMs = System.currentTimeMillis();
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
                int carriedCount = _selectedShulker == null ? 0 : countCarriedShulkers(mod, _selectedShulker);
                if (_selectedShulker != null
                        && carriedCount >= _initialSelectedCount) {
                    if (_placePos != null) {
                        ShulkerMemory.getInstance().forgetContents(_placePos);
                    }
                    syncCarriedShulkerMemory(mod);
                    transition(Phase.RESTORE, mod, "shulker recovered into inventory");
                    return null;
                }
                if (_selectedShulker != null
                        && mod.getEntityTracker().itemDropped(
                        new ItemTarget(_selectedShulker, 1))) {
                    if (_pickupTask == null || _pickupTask.stopped()) {
                        _pickupTask = new PickupDroppedItemTask(
                                _selectedShulker, 1, true);
                    }
                    setDebugState("Picking up managed shulker");
                    return _pickupTask;
                }
                if (_pickupTicks > 200) {
                    long now = System.currentTimeMillis();
                    if (now - _lastPickupTimeoutLogMs > 1_000L) {
                        _lastPickupTimeoutLogMs = now;
                        DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                                "pickup timeout selected=" + _selectedShulker
                                        + " pos=" + _placePos
                                        + " carried=" + carriedCount
                                        + " initial=" + _initialSelectedCount);
                    }
                    if (_placePos != null
                            && mod.getWorld().getBlockState(_placePos).getBlock() instanceof ShulkerBoxBlock) {
                        _breakTask = null;
                        _pickupTask = null;
                        _pickupTicks = 0;
                        transition(Phase.BREAK, mod,
                                "pickup timed out but managed shulker block still exists");
                    } else if (_selectedShulker != null && carriedCount > 0) {
                        // If no placed block/dropped item is visible but a matching carried
                        // shulker exists, prefer releasing the transaction over holding the
                        // global shulker lock forever. This can happen when the player had
                        // more than one shulker at transaction start and pickup accounting
                        // cannot distinguish the exact stack after Minecraft merges items.
                        if (_placePos != null) {
                            ShulkerMemory.getInstance().forgetContents(_placePos);
                        }
                        syncCarriedShulkerMemory(mod);
                        transition(Phase.RESTORE, mod,
                                "pickup timed out; matching carried shulker present");
                    } else {
                        DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                                "pickup timed out; no block, dropped item, or carried shulker remains");
                        transition(Phase.DONE, mod, "managed shulker pickup failed");
                    }
                }
                return null;
            }

            case RESTORE: {
                if (restoreOriginalSlot(mod)) {
                    syncCarriedShulkerMemory(mod);
                    if (_mode == Mode.RETRIEVE
                            && !requestAlreadySatisfied(mod)
                            && hasUntriedRetrieveCandidate(mod)) {
                        resetForNextCandidate();
                        transition(Phase.GET_SHULKER, mod,
                                "request still missing after wrong shulker; retrying next candidate");
                    } else if (_mode == Mode.RETRIEVE && !requestAlreadySatisfied(mod)) {
                        DebugLogger.getInstance().logImmediate("SHULKER-ERROR",
                                "retrieve ended without satisfying request targets="
                                        + Arrays.toString(_targets)
                                        + " failedSlots=" + _failedRetrieveSlots
                                        + " failedFingerprints=" + _failedRetrieveFingerprints
                                        + " inventory=" + buildInventorySummary(mod));
                        transition(Phase.DONE, mod,
                                "retrieve incomplete and no untried candidate remains");
                    } else {
                        transition(Phase.DONE, mod, "transaction complete and slot restored");
                    }
                }
                return null;
            }

            case DONE:
                releaseTransactionLock();
                return null;
        }
        return null;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }

    @Override
    public boolean shouldForce(Belfegor mod, Task interruptingCandidate) {
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
    protected void onStop(Belfegor mod, Task interruptTask) {
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
        releaseTransactionLock();
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

    private boolean requestAlreadySatisfied(Belfegor mod) {
        if (_mode == Mode.STORE) {
            return _targets.length == 0
                    || Arrays.stream(_targets).allMatch(target -> target.getTargetCount() <= 0);
        }
        return Arrays.stream(_targets).allMatch(target ->
                mod.getItemStorage().getItemCountInventoryOnly(target.getMatches())
                        >= target.getTargetCount());
    }

    private boolean transferComplete(Belfegor mod) {
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

    private boolean beginOneTransfer(Belfegor mod) {
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
                        for (Slot destination : getOpenShulkerSlotsThatCanFit(stack)) {
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
                    for (Slot slot : getOpenShulkerSlotsWithItem(item)) {
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

    private boolean openShulkerHasNeededItem(Belfegor mod) {
        if (_mode != Mode.RETRIEVE || !isShulkerOpen()) return false;
        Map<String, Integer> contents = readOpenContents();
        for (ItemTarget target : _targets) {
            int have = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            if (have >= target.getTargetCount()) continue;
            for (Item item : target.getMatches()) {
                String itemName = ItemHelper.stripItemName(item);
                if (contents.getOrDefault(itemName, 0) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private ArrayList<Slot> getOpenShulkerSlotsWithItem(Item item) {
        ArrayList<Slot> slots = new ArrayList<>();
        if (!isShulkerOpen()) return slots;
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player == null || player.currentScreenHandler == null) return slots;
        int containerSlots = player.currentScreenHandler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = player.currentScreenHandler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == item) {
                slots.add(new RawSlot(i));
            }
        }
        return slots;
    }

    private ArrayList<Slot> getOpenShulkerSlotsThatCanFit(ItemStack moving) {
        ArrayList<Slot> slots = new ArrayList<>();
        if (!isShulkerOpen()) return slots;
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player == null || player.currentScreenHandler == null) return slots;
        int containerSlots = player.currentScreenHandler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            Slot slot = new RawSlot(i);
            if (slot != null && roomInSlot(slot, moving) > 0) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private boolean startTransfer(Slot source, Slot destination, Item item, int amount) {
        if (amount <= 0 || !_inventory.isCursorEmpty()) return false;
        if (_mode == Mode.STORE && destination.isSlotInPlayerInventory()) {
            DebugLogger.getInstance().logImmediate("SHULKER-TRANSFER",
                    "refuse store destination in player inventory source="
                            + source.getWindowSlot()
                            + " destination=" + destination.getWindowSlot()
                            + " item=" + ItemHelper.stripItemName(item));
            return false;
        }
        if (_mode == Mode.RETRIEVE && !destination.isSlotInPlayerInventory()) {
            DebugLogger.getInstance().logImmediate("SHULKER-TRANSFER",
                    "refuse retrieve destination outside player inventory source="
                            + source.getWindowSlot()
                            + " destination=" + destination.getWindowSlot()
                            + " item=" + ItemHelper.stripItemName(item));
            return false;
        }
        _transferSource = source;
        _transferDestination = destination;
        _transferItem = item;
        _transferRemaining = amount;
        logTransfer(_mode == Mode.STORE ? "store-begin" : "retrieve-begin",
                source, StorageHelper.getItemStackInSlot(source));
        return _inventory.pickupFromSlot(source);
    }

    private boolean continueTransfer(Belfegor mod) {
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

    private int findInventorySlot(Belfegor mod, ItemStack selected) {
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

    private int countCarriedShulkers(Belfegor mod, Item item) {
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private boolean restoreOriginalSlot(Belfegor mod) {
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

    private int findRecoveredShulkerSlot(Belfegor mod) {
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

    private int findEmptyInventoryIndex(Belfegor mod, int... excluded) {
        outer:
        for (int i = 0; i < mod.getPlayer().getInventory().main.size(); i++) {
            for (int skip : excluded) if (i == skip) continue outer;
            if (mod.getPlayer().getInventory().main.get(i).isEmpty()) return i;
        }
        return -1;
    }

    private ItemStack selectCarriedShulker(Belfegor mod) {
        InventoryManager inventory = new InventoryManager(mod);
        syncCarriedShulkerMemory(mod);
        ItemStack best = ItemStack.EMPTY;
        int bestScore = Integer.MIN_VALUE;
        for (int invSlot = 0; invSlot < mod.getPlayer().getInventory().main.size(); invSlot++) {
            final int slotIndex = invSlot;
            ItemStack stack = mod.getPlayer().getInventory().main.get(invSlot);
            if (stack.isEmpty() || !inventory.isShulkerBox(stack.getItem())) continue;
            Map<String, Integer> contents = inventory.readShulkerContents(stack);
            if (_mode == Mode.RETRIEVE
                    && (_failedRetrieveSlots.contains(slotIndex)
                    || _failedRetrieveFingerprints.contains(fingerprint(contents)))) {
                continue;
            }
            ShulkerMemory.ShulkerEntry remembered = ShulkerMemory.getInstance()
                    .getAllShulkers().stream()
                    .filter(entry -> "inventory".equals(entry.location)
                            && entry.inventorySlot == slotIndex)
                    .findFirst()
                    .orElse(null);
            int matching = 0;
            int total = 0;
            for (int count : contents.values()) total += count;
            for (ItemTarget target : _targets) {
                for (Item item : target.getMatches()) {
                    String itemName = ItemHelper.stripItemName(item);
                    matching += contents.getOrDefault(itemName, 0);
                    if (remembered != null) {
                        matching += remembered.getItemCount(itemName);
                    }
                }
            }
            if (remembered != null) total = Math.max(total, remembered.totalItems);
            int score = _mode == Mode.RETRIEVE
                    ? matching * 1000 + (remembered == null ? 0 : remembered.slots.size())
                    : matching * 1000 + Math.max(0, 1728 - total)
                    + (remembered == null ? 0 : remembered.freeSlots * 2);
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

    private boolean hasUntriedRetrieveCandidate(Belfegor mod) {
        if (_mode != Mode.RETRIEVE || mod == null || mod.getPlayer() == null) return false;
        InventoryManager inventory = new InventoryManager(mod);
        for (int invSlot = 0; invSlot < mod.getPlayer().getInventory().main.size(); invSlot++) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(invSlot);
            if (stack.isEmpty() || !inventory.isShulkerBox(stack.getItem())) continue;
            Map<String, Integer> contents = inventory.readShulkerContents(stack);
            if (_failedRetrieveSlots.contains(invSlot)
                    || _failedRetrieveFingerprints.contains(fingerprint(contents))) {
                continue;
            }
            for (ItemTarget target : _targets) {
                int have = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
                if (have >= target.getTargetCount()) continue;
                for (Item item : target.getMatches()) {
                    if (contents.getOrDefault(ItemHelper.stripItemName(item), 0) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void rememberFailedRetrieveCandidate() {
        if (_mode != Mode.RETRIEVE) return;
        if (_originalInventorySlot >= 0) {
            _failedRetrieveSlots.add(_originalInventorySlot);
        }
        _failedRetrieveFingerprints.add(fingerprint(readOpenContents()));
        DebugLogger.getInstance().logImmediate("SHULKER-RETRY",
                "mark failed retrieve slot=" + _originalInventorySlot
                        + " contents=" + readOpenContents()
                        + " failedSlots=" + _failedRetrieveSlots);
    }

    private void resetForNextCandidate() {
        _selectedShulker = null;
        _placePos = null;
        _placeTask = null;
        _jumpPlaceTask = null;
        _openTask = null;
        _breakTask = null;
        _clearAboveTask = null;
        _noProgressTicks = 0;
        _catalogPass = 0;
        _pickupTicks = 0;
        _originalInventorySlot = -1;
        _initialSelectedCount = 0;
        _verifiedContents = new HashMap<>();
        _selectedExpectedContents = new HashMap<>();
        _pickupTask = null;
        _lastOpenAttemptMs = 0;
        _openAttempts = 0;
        clearTransfer();
    }

    private String fingerprint(Map<String, Integer> contents) {
        if (contents == null || contents.isEmpty()) return "{}";
        ArrayList<String> parts = new ArrayList<>();
        contents.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parts.add(entry.getKey() + "x" + entry.getValue()));
        return String.join("|", parts);
    }

    private boolean hasCarriedShulker(Belfegor mod, Item item) {
        return !mod.getItemStorage().getSlotsWithItemPlayerInventory(false, item).isEmpty();
    }

    private boolean isShulkerOpen() {
        return net.minecraft.client.MinecraftClient.getInstance().player != null
                && net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler
                instanceof ShulkerBoxScreenHandler;
    }

    private BlockPos findNearbyShulker(Belfegor mod) {
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

    private Map<Integer, ShulkerMemory.ShulkerSlotItem> readOpenSlotContents() {
        Map<Integer, ShulkerMemory.ShulkerSlotItem> contents = new HashMap<>();
        if (!isShulkerOpen()) return contents;
        var handler = net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler;
        int containerSlots = handler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty()) {
                String name = ItemHelper.stripItemName(stack.getItem());
                contents.put(i, new ShulkerMemory.ShulkerSlotItem(i, name, name, stack.getCount()));
            }
        }
        return contents;
    }

    private void scanOpenContents(Belfegor mod) {
        if (_placePos == null || !isShulkerOpen()) return;
        ShulkerMemory.getInstance().rememberContentsDetailed(_placePos,
                _selectedShulker == null ? "" : ItemHelper.stripItemName(_selectedShulker),
                readOpenSlotContents(),
                "open-screen");
        ShulkerMemory.getInstance().save();
    }

    private boolean acquireTransactionLock(Belfegor mod) {
        long now = System.currentTimeMillis();
        if (ACTIVE_TRANSACTION == null
                || ACTIVE_TRANSACTION.equals(_transactionId)
                || now - ACTIVE_TRANSACTION_STARTED_MS > TRANSACTION_STALE_MS) {
            if (ACTIVE_TRANSACTION == null || !ACTIVE_TRANSACTION.equals(_transactionId)) {
                ACTIVE_TRANSACTION = _transactionId;
                ACTIVE_TRANSACTION_STARTED_MS = now;
                DebugLogger.getInstance().logImmediate("SHULKER-LOCK",
                        "acquired id=" + _transactionId
                                + " mode=" + _mode
                                + " targets=" + Arrays.toString(_targets));
            }
            return true;
        }
        DebugLogger.getInstance().logImmediate("SHULKER-LOCK",
                "waiting active=" + ACTIVE_TRANSACTION
                        + " requester=" + _transactionId
                        + " mode=" + _mode
                        + " targets=" + Arrays.toString(_targets));
        return false;
    }

    private void releaseTransactionLock() {
        if (ACTIVE_TRANSACTION != null && ACTIVE_TRANSACTION.equals(_transactionId)) {
            DebugLogger.getInstance().logImmediate("SHULKER-LOCK",
                    "released id=" + _transactionId
                            + " mode=" + _mode);
            ACTIVE_TRANSACTION = null;
            ACTIVE_TRANSACTION_STARTED_MS = 0;
        }
    }

    private void rememberPlacedPosition(String reason) {
        if (_placePos == null) return;
        ShulkerMemory.getInstance().rememberPlacement(_placePos,
                _selectedShulker == null ? "" : ItemHelper.stripItemName(_selectedShulker),
                reason);
        ShulkerMemory.getInstance().save();
    }

    public static void syncCarriedShulkerMemory(Belfegor mod) {
        if (mod.getPlayer() == null) return;
        InventoryManager inventory = new InventoryManager(mod);
        ShulkerMemory memory = ShulkerMemory.getInstance();
        memory.clearInventoryEntries();
        for (int slot = 0; slot < mod.getPlayer().getInventory().main.size(); slot++) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(slot);
            if (!stack.isEmpty() && inventory.isShulkerBox(stack.getItem())) {
                memory.rememberInventoryContentsDetailed(slot,
                        ItemHelper.stripItemName(stack.getItem()),
                        toShulkerSlotItems(inventory.readShulkerStacksBySlot(stack)));
            }
        }
        memory.save();
    }

    private static Map<Integer, ShulkerMemory.ShulkerSlotItem> toShulkerSlotItems(
            Map<Integer, ItemStack> stacks) {
        Map<Integer, ShulkerMemory.ShulkerSlotItem> result = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : stacks.entrySet()) {
            ItemStack stack = entry.getValue();
            if (stack == null || stack.isEmpty()) continue;
            String name = ItemHelper.stripItemName(stack.getItem());
            result.put(entry.getKey(),
                    new ShulkerMemory.ShulkerSlotItem(entry.getKey(), name, name, stack.getCount()));
        }
        return result;
    }

    private void transition(Phase next, Belfegor mod, String reason) {
        DebugLogger.getInstance().logImmediate("SHULKER-STATE",
                _phase + " -> " + next + " reason=" + reason
                        + " pos=" + _placePos
                        + " selected=" + (_selectedShulker == null
                        ? "none" : ItemHelper.stripItemName(_selectedShulker)));
        _phase = next;
        setDebugState(next + ": " + reason);
        if (next == Phase.DONE) {
            releaseTransactionLock();
        }
    }

    private void logState(String reason, Belfegor mod) {
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
