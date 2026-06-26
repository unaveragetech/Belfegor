package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Human-like inventory manager that can navigate any slot, split stacks,
 * move items between hotbar/main inventory/crafting grid, and craft items.
 *
 * Uses clickSlotForce for rapid within-tick operations.
 */
public class InventoryManager {

    private final AltoClef _mod;
    private int _clicksThisTick = 0;
    private static final int MAX_CLICKS_PER_TICK = 3;
    private int _lastPlayerAge = Integer.MIN_VALUE;
    private int _lastClickedSlotId = -1;
    private int _sameSlotConsecutiveCount = 0;
    private static final int MAX_SAME_SLOT_CONSECUTIVE = 2;

    public InventoryManager(AltoClef mod) {
        _mod = mod;
    }

    private void resetClickCounter() {
        int playerAge = _mod.getPlayer() == null ? Integer.MIN_VALUE : _mod.getPlayer().age;
        if (playerAge != _lastPlayerAge) {
            _lastPlayerAge = playerAge;
            _clicksThisTick = 0;
            _lastClickedSlotId = -1;
            _sameSlotConsecutiveCount = 0;
        }
    }

    private boolean canClick() {
        resetClickCounter();
        return _clicksThisTick < MAX_CLICKS_PER_TICK;
    }

    private void recordClick() {
        _clicksThisTick++;
    }

    private boolean checkSameSlotGuard(Slot slot) {
        int slotId = slot == null ? -1 : slot.getWindowSlot();
        if (slotId == _lastClickedSlotId) {
            _sameSlotConsecutiveCount++;
            if (_sameSlotConsecutiveCount > MAX_SAME_SLOT_CONSECUTIVE) {
                adris.altoclef.debug.DebugLogger.getInstance().log("INVMGR-STUCK",
                        "Blocked consecutive click #" + _sameSlotConsecutiveCount + " on slot " + slotId);
                return false;
            }
        } else {
            _lastClickedSlotId = slotId;
            _sameSlotConsecutiveCount = 1;
        }
        return true;
    }

    /**
     * Reset the same-slot consecutive click guard.
     * Call this between deliberate multi-click transactions on the same slot
     * (e.g., placing one item then another in the same crafting slot).
     */
    public void resetSameSlotGuard() {
        _lastClickedSlotId = -1;
        _sameSlotConsecutiveCount = 0;
    }

    // === LOW-LEVEL SLOT OPERATIONS ===

    /**
     * Left-click a slot: pick up entire stack / place entire cursor / swap.
     */
    public boolean leftClick(Slot slot) {
        if (!canClick()) return false;
        if (!checkSameSlotGuard(slot)) return false;
        _mod.getSlotHandler().clickSlotForce(slot, 0, SlotActionType.PICKUP);
        recordClick();
        return true;
    }

    /**
     * Right-click a slot: pick up half / place one item / fill stack.
     */
    public boolean rightClick(Slot slot) {
        if (!canClick()) return false;
        if (!checkSameSlotGuard(slot)) return false;
        _mod.getSlotHandler().clickSlotForce(slot, 1, SlotActionType.PICKUP);
        recordClick();
        return true;
    }

    /**
     * Shift-click a slot: quick move to other inventory section.
     */
    public boolean shiftClick(Slot slot) {
        if (!canClick()) return false;
        if (!checkSameSlotGuard(slot)) return false;
        _mod.getSlotHandler().clickSlotForce(slot, 0, SlotActionType.QUICK_MOVE);
        recordClick();
        return true;
    }

    /**
     * Swap a slot's contents with a hotbar slot (0-8).
     */
    public boolean swapWithHotbar(Slot slot, int hotbarIndex) {
        if (!canClick()) return false;
        if (!checkSameSlotGuard(slot)) return false;
        _mod.getSlotHandler().clickSlotForce(slot, hotbarIndex, SlotActionType.SWAP);
        recordClick();
        return true;
    }

    /**
     * Drop the cursor contents.
     */
    public boolean dropCursor() {
        if (!canClick()) return false;
        if (!checkSameSlotGuard(Slot.UNDEFINED)) return false;
        _mod.getSlotHandler().clickSlotForce(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        recordClick();
        return true;
    }

    // === ITEM SEARCH ===

    /**
     * Get the item in a specific slot.
     */
    public ItemStack getItemInSlot(Slot slot) {
        return StorageHelper.getItemStackInSlot(slot);
    }

    /**
     * Get the item held by the cursor.
     */
    public ItemStack getCursorItem() {
        return StorageHelper.getItemStackInCursorSlot();
    }

    /**
     * Check if cursor is empty.
     */
    public boolean isCursorEmpty() {
        return getCursorItem().isEmpty();
    }

    /**
     * Find all slots in the player inventory (main + hotbar) that contain any of the given items.
     * Includes hotbar (window 36-44) and main inventory (window 9-35).
     * Does NOT include crafting input, armor, offhand.
     */
    public List<Slot> findAllItemSlots(Item... items) {
        List<Slot> result = new ArrayList<>();
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        boolean isPlayerInv = handler instanceof net.minecraft.screen.PlayerScreenHandler;
        int firstInventorySlot = isPlayerInv ? 9 : 10;
        for (int windowSlot = firstInventorySlot; windowSlot < handler.slots.size(); windowSlot++) {
            // In player inventory screen, slot 45 is the offhand — skip it.
            // In crafting table screen, slot 45 is the last hotbar slot — keep it.
            if (isPlayerInv && windowSlot == 45) continue;

            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (!stack.isEmpty()) {
                for (Item item : items) {
                    if (stack.getItem() == item) {
                        result.add(Slot.getFromCurrentScreen(windowSlot));
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Find the first slot containing any of the given items.
     */
    public Optional<Slot> findFirstItemSlot(Item... items) {
        List<Slot> slots = findAllItemSlots(items);
        return slots.isEmpty() ? Optional.empty() : Optional.of(slots.get(0));
    }

    /**
     * Find a slot in the crafting grid that contains the wrong item (not what the recipe needs).
     */
    public Optional<Slot> findWrongCraftingSlot(Slot[] craftSlots, Item... validItems) {
        for (Slot slot : craftSlots) {
            ItemStack stack = getItemInSlot(slot);
            if (!stack.isEmpty()) {
                boolean valid = false;
                for (Item item : validItems) {
                    if (stack.getItem() == item) {
                        valid = true;
                        break;
                    }
                }
                if (!valid) {
                    return Optional.of(slot);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Find an empty slot in the crafting grid.
     */
    public Optional<Slot> findEmptyCraftingSlot(Slot[] craftSlots) {
        for (Slot slot : craftSlots) {
            if (getItemInSlot(slot).isEmpty()) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /**
     * Count how many of an item are in the crafting grid.
     */
    public int countInCraftingGrid(Slot[] craftSlots, Item item) {
        int count = 0;
        for (Slot slot : craftSlots) {
            ItemStack stack = getItemInSlot(slot);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Count how many of an item are in the player inventory (main + hotbar).
     */
    public int countInInventory(Item... items) {
        int count = 0;
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        boolean isPlayerInv = handler instanceof net.minecraft.screen.PlayerScreenHandler;
        int firstInventorySlot = isPlayerInv ? 9 : 10;
        for (int windowSlot = firstInventorySlot; windowSlot < handler.slots.size(); windowSlot++) {
            if (isPlayerInv && windowSlot == 45) continue;

            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (!stack.isEmpty()) {
                for (Item item : items) {
                    if (stack.getItem() == item) {
                        count += stack.getCount();
                        break;
                    }
                }
            }
        }
        return count;
    }

    // === INVENTORY MOVEMENT OPERATIONS ===

    /**
     * Pick up an item from a slot into the cursor.
     * Only works if cursor is empty.
     * Returns true if successful.
     */
    public boolean pickupFromSlot(Slot slot) {
        if (!isCursorEmpty()) return false;
        ItemStack stack = getItemInSlot(slot);
        if (stack.isEmpty()) return false;
        return leftClick(slot);
    }

    /**
     * Pick up half a stack from a slot.
     * Only works if cursor is empty.
     */
    public boolean pickupHalfFromSlot(Slot slot) {
        if (!isCursorEmpty()) return false;
        ItemStack stack = getItemInSlot(slot);
        if (stack.isEmpty()) return false;
        return rightClick(slot);
    }

    /**
     * Place exactly one item from cursor into a slot.
     * Only works if cursor has an item.
     */
    public boolean placeOneInSlot(Slot slot) {
        if (isCursorEmpty()) return false;
        return rightClick(slot);
    }

    /**
     * Place all items from cursor into a slot.
     * Only works if cursor has an item.
     */
    public boolean placeAllInSlot(Slot slot) {
        if (isCursorEmpty()) return false;
        return leftClick(slot);
    }

    /**
     * Move an item from one slot to another via cursor.
     * Pickup from source, place at destination.
     * If destination is occupied with different item, swaps them.
     */
    public boolean moveItem(Slot from, Slot to) {
        if (!pickupFromSlot(from)) return false;
        return leftClick(to); // place or swap
    }

    /**
     * Clear a crafting grid slot by shift-clicking it to move items back to inventory.
     * If shift-click doesn't work (e.g., inventory full), pick up and drop.
     */
    public boolean clearSlot(Slot slot) {
        ItemStack stack = getItemInSlot(slot);
        if (stack.isEmpty()) return true;

        // Try shift-click first (moves to inventory)
        if (!shiftClick(slot)) return false;

        // Check if slot is now empty
        if (getItemInSlot(slot).isEmpty()) return true;

        // Shift-click failed (maybe inventory full), pick up and drop
        if (pickupFromSlot(slot)) {
            return dropCursor();
        }
        return false;
    }

    /**
     * Clear all crafting grid slots.
     */
    public void clearCraftingGrid(Slot[] craftSlots) {
        for (Slot slot : craftSlots) {
            if (!getItemInSlot(slot).isEmpty()) {
                if (!clearSlot(slot)) break; // Stop if we can't clear
            }
        }
        // Clear cursor if anything left
        if (!isCursorEmpty()) {
            Optional<Slot> emptySlot = findEmptyInventorySlot();
            if (emptySlot.isPresent()) {
                leftClick(emptySlot.get());
            }
        }
    }

    /**
     * Find an empty slot in the player inventory (main + hotbar).
     */
    public Optional<Slot> findEmptyInventorySlot() {
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        boolean isPlayerInv = handler instanceof net.minecraft.screen.PlayerScreenHandler;
        int firstInventorySlot = isPlayerInv ? 9 : 10;
        for (int windowSlot = firstInventorySlot; windowSlot < handler.slots.size(); windowSlot++) {
            if (isPlayerInv && windowSlot == 45) continue;

            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (stack.isEmpty()) {
                return Optional.of(Slot.getFromCurrentScreen(windowSlot));
            }
        }
        return Optional.empty();
    }

    /**
     * Move an item from inventory to a specific crafting grid slot.
     * Handles stack splitting: if we need 1 item but have a stack,
     * right-click to place one, then return the rest.
     *
     * @return true if the item was placed
     */
    public boolean moveToCraftSlot(Item item, Slot craftSlot, int neededCount) {
        ItemStack craftStack = getItemInSlot(craftSlot);

        // If craft slot already has correct item and enough
        if (!craftStack.isEmpty() && craftStack.getItem() == item && craftStack.getCount() >= neededCount) {
            return true; // Already done
        }

        // If craft slot has wrong item, clear it first
        if (!craftStack.isEmpty() && craftStack.getItem() != item) {
            clearSlot(craftSlot);
        }

        // Find the item in inventory
        Optional<Slot> sourceSlot = findFirstItemSlot(item);
        if (sourceSlot.isEmpty()) return false;

        ItemStack sourceStack = getItemInSlot(sourceSlot.get());
        int sourceCount = sourceStack.getCount();

        // How many more do we need in this craft slot?
        int inGrid = countInCraftingGrid(getCurrentCraftSlots(), item);
        int totalNeeded = neededCount;
        int alreadyPlaced = inGrid - (craftStack.isEmpty() ? 0 : craftStack.getCount());
        int toPlace = Math.min(totalNeeded - alreadyPlaced, sourceCount);
        if (toPlace <= 0) return true;

        // Pick up the source stack
        if (!pickupFromSlot(sourceSlot.get())) return false;

        if (toPlace >= sourceCount) {
            // Place the entire stack
            placeAllInSlot(craftSlot);
        } else {
            // Place exactly toPlace items using right-clicks.
            // Reset the same-slot guard between each right-click since these
            // are deliberate multi-clicks on the same craft slot.
            for (int i = 0; i < toPlace; i++) {
                _lastClickedSlotId = -1;
                _sameSlotConsecutiveCount = 0;
                _mod.getSlotHandler().resetSameSlotGuard();
                placeOneInSlot(craftSlot);
            }
            // Return remainder to source slot
            if (!isCursorEmpty()) {
                leftClick(sourceSlot.get());
            }
        }

        return true;
    }

    /**
     * Begin moving an item to a crafting slot.
     *
     * This deliberately performs at most one click. CraftGenericManuallyTask owns the
     * cursor state machine and distributes the held stack over subsequent ticks. The
     * old three-click transaction could lose its final click to a click budget and
     * leave the cursor permanently holding the remainder.
     */
    public boolean placeOneInCraftSlot(Item item, Slot craftSlot) {
        ItemStack craftStack = getItemInSlot(craftSlot);

        // Already has correct item
        if (!craftStack.isEmpty() && craftStack.getItem() == item) {
            return true;
        }

        // Has wrong item, clear it
        if (!craftStack.isEmpty() && craftStack.getItem() != item) {
            clearSlot(craftSlot);
        }

        // Find item in inventory
        Optional<Slot> sourceSlot = findFirstItemSlot(item);
        if (sourceSlot.isEmpty()) return false;

        // Pick up once. The next task tick will place from the cursor.
        return pickupFromSlot(sourceSlot.get());
    }

    // === CRAFTING TABLE / INVENTORY MANAGEMENT ===

    /**
     * Get the current crafting grid slots based on what screen is open.
     */
    public Slot[] getCurrentCraftSlots() {
        if (StorageHelper.isBigCraftingOpen()) {
            return new Slot[]{
                Slot.getFromCurrentScreen(1), Slot.getFromCurrentScreen(2), Slot.getFromCurrentScreen(3),
                Slot.getFromCurrentScreen(4), Slot.getFromCurrentScreen(5), Slot.getFromCurrentScreen(6),
                Slot.getFromCurrentScreen(7), Slot.getFromCurrentScreen(8), Slot.getFromCurrentScreen(9)
            };
        } else if (StorageHelper.isPlayerInventoryOpen()) {
            return new Slot[]{
                PlayerSlot.CRAFT_INPUT_SLOTS[0], PlayerSlot.CRAFT_INPUT_SLOTS[1],
                PlayerSlot.CRAFT_INPUT_SLOTS[2], PlayerSlot.CRAFT_INPUT_SLOTS[3]
            };
        }
        return new Slot[0];
    }

    /**
     * Get the crafting output slot.
     */
    public Slot getOutputSlot() {
        if (StorageHelper.isBigCraftingOpen()) {
            return Slot.getFromCurrentScreen(0);
        } else if (StorageHelper.isPlayerInventoryOpen()) {
            return PlayerSlot.CRAFT_OUTPUT_SLOT;
        }
        return null;
    }

    /**
     * Check if the crafting output slot has our target item.
     */
    public boolean outputHasItem(Item target) {
        Slot output = getOutputSlot();
        if (output == null) return false;
        ItemStack stack = getItemInSlot(output);
        return !stack.isEmpty() && stack.getItem() == target;
    }

    /**
     * Collect the crafting output via shift-click.
     */
    public boolean collectOutput() {
        Slot output = getOutputSlot();
        if (output == null) return false;
        if (outputHasItem(StorageHelper.getItemStackInSlot(output).getItem())) {
            shiftClick(output);
            return true;
        }
        return false;
    }

    /**
     * Check if ALL crafting grid slots are empty.
     */
    public boolean isCraftingGridEmpty() {
        for (Slot slot : getCurrentCraftSlots()) {
            if (!getItemInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Check if the cursor is holding a valid item for any recipe slot.
     */
    public boolean cursorHasRecipeItem(Item[] recipeItems) {
        if (isCursorEmpty()) return false;
        Item cursorItem = getCursorItem().getItem();
        for (Item item : recipeItems) {
            if (cursorItem == item) return true;
        }
        return false;
    }

    /**
     * Move cursor item back to inventory.
     */
    public boolean returnCursorToInventory() {
        if (isCursorEmpty()) return true;
        Optional<Slot> empty = findEmptyInventorySlot();
        if (empty.isPresent()) {
            leftClick(empty.get());
            return true;
        }
        // Try to stack with existing items (limit to avoid excessive scanning)
        ItemStack cursor = getCursorItem();
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        boolean isPlayerInv = handler instanceof net.minecraft.screen.PlayerScreenHandler;
        int scanned = 0;
        int firstInventorySlot = isPlayerInv ? 9 : 10;
        for (int windowSlot = firstInventorySlot; windowSlot < handler.slots.size() && scanned < 5; windowSlot++) {
            if (isPlayerInv && windowSlot == 45) continue;
            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (!stack.isEmpty() && stack.getItem() == cursor.getItem() && stack.getCount() < stack.getMaxCount()) {
                leftClick(Slot.getFromCurrentScreen(windowSlot));
                if (isCursorEmpty()) return true;
                scanned++;
            }
        }
        return false;
    }

    /**
     * Open the player inventory screen.
     */
    public void openInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null || !(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
            client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player));
        }
    }

    /**
     * Close the current screen.
     */
    public void closeScreen() {
        StorageHelper.closeScreen();
        MinecraftClient.getInstance().setScreen(null);
    }

    /**
     * Check if the player inventory is open.
     */
    public boolean isInventoryOpen() {
        return StorageHelper.isPlayerInventoryOpen();
    }

    /**
     * Check if a crafting table screen is open.
     */
    public boolean isCraftingTableOpen() {
        return StorageHelper.isBigCraftingOpen();
    }

    /**
     * Check if any inventory screen is open.
     */
    public boolean isScreenOpen() {
        return isInventoryOpen() || isCraftingTableOpen();
    }

    // === ALL-SLOT SCANNING ===

    /**
     * Find ALL slots containing any of the given items across ALL visible slots
     * including crafting grid, armor, offhand, container slots, chest/shulker slots.
     * Iterates from window slot 0 to handler.slots.size().
     */
    public List<Slot> findAllItemsInAllSlots(Item... items) {
        List<Slot> result = new ArrayList<>();
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        if (handler == null) return result;
        for (int windowSlot = 0; windowSlot < handler.slots.size(); windowSlot++) {
            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (!stack.isEmpty()) {
                for (Item item : items) {
                    if (stack.getItem() == item) {
                        result.add(Slot.getFromCurrentScreen(windowSlot));
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Count items across ALL visible slots (crafting, armor, offhand, containers).
     */
    public int countInAllSlots(Item... items) {
        int count = 0;
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        if (handler == null) return 0;
        for (int windowSlot = 0; windowSlot < handler.slots.size(); windowSlot++) {
            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (!stack.isEmpty()) {
                for (Item item : items) {
                    if (stack.getItem() == item) {
                        count += stack.getCount();
                        break;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Read the contents of a shulker box item from its Container data component.
     * Returns a map of item names (via ItemHelper.stripItemName) to counts.
     */
    public Map<String, Integer> readShulkerContents(ItemStack shulkerStack) {
        Map<String, Integer> contents = new HashMap<>();
        if (shulkerStack.isEmpty()) return contents;
        try {
            var container = shulkerStack.get(DataComponentTypes.CONTAINER);
            if (container != null) {
                container.stream().forEach(stored -> {
                    if (!stored.isEmpty()) {
                        String name = adris.altoclef.util.helpers.ItemHelper.stripItemName(stored.getItem());
                        contents.merge(name, stored.getCount(), Integer::sum);
                    }
                });
            }
        } catch (Exception ignored) {}
        return contents;
    }

    /**
     * Check if an item is any color of shulker box.
     */
    public boolean isShulkerBox(Item item) {
        if (item == null) return false;
        for (Item shulker : adris.altoclef.util.helpers.ItemHelper.SHULKER_BOXES) {
            if (item == shulker) return true;
        }
        return false;
    }

    /**
     * Scan all inventory slots for shulker boxes and return their contents.
     * Returns a map of window slot -> contents.
     */
    public Map<Integer, Map<String, Integer>> scanAllShulkerContents() {
        Map<Integer, Map<String, Integer>> result = new HashMap<>();
        var handler = MinecraftClient.getInstance().player.currentScreenHandler;
        if (handler == null) return result;
        for (int windowSlot = 0; windowSlot < handler.slots.size(); windowSlot++) {
            ItemStack stack = handler.getSlot(windowSlot).getStack();
            if (!stack.isEmpty() && isShulkerBox(stack.getItem())) {
                Map<String, Integer> contents = readShulkerContents(stack);
                if (!contents.isEmpty()) {
                    result.put(windowSlot, contents);
                }
            }
        }
        return result;
    }
}
