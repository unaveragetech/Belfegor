package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.debug.DebugLogger;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SlotClickChangedEvent;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;


public class SlotHandler {

    private final AltoClef _mod;

    private final TimerGame _slotActionTimer = new TimerGame(0);
    private boolean _overrideTimerOnce = false;

private int _clicksThisTick = 0;
    private int _lastPlayerAge = Integer.MIN_VALUE;
    private static final int MAX_CLICKS_PER_TICK = 10;

    // Track consecutive clicks on the same slot to detect stuck loops.
    // If the same slot is clicked 3+ times in a row with PICKUP, the cursor
    // is likely stuck (pick up → put back → pick up → ...). Block further clicks.
    private int _lastClickedSlot = -1;
    private int _sameSlotClickCount = 0;
    private static final int MAX_SAME_SLOT_CLICKS = 2;

    public SlotHandler(AltoClef mod) {
        _mod = mod;
    }

private boolean clickBudgetAvailable() {
        int playerAge = _mod.getPlayer() == null ? Integer.MIN_VALUE : _mod.getPlayer().age;
        if (playerAge != _lastPlayerAge) {
            _lastPlayerAge = playerAge;
            _clicksThisTick = 0;
            _sameSlotClickCount = 0;
            _lastClickedSlot = -1;
        }
        return _clicksThisTick < MAX_CLICKS_PER_TICK;
    }

    private void consumeClickBudget() {
        _clicksThisTick++;
    }

    private void forceAllowNextSlotAction() {
        _overrideTimerOnce = true;
    }

    public boolean canDoSlotAction() {
        if (_overrideTimerOnce) {
            _overrideTimerOnce = false;
            return true;
        }
        _slotActionTimer.setInterval(_mod.getModSettings().getContainerItemMoveDelay());
        return _slotActionTimer.elapsed();
    }

    public void registerSlotAction() {
        _mod.getItemStorage().registerSlotAction();
        _slotActionTimer.reset();
    }


private boolean isSameSlotStuck(int windowSlot, SlotActionType type) {
        if (type == SlotActionType.PICKUP && windowSlot >= 0 && windowSlot == _lastClickedSlot) {
            _sameSlotClickCount++;
            if (_sameSlotClickCount > MAX_SAME_SLOT_CLICKS) {
                DebugLogger.getInstance().log("SLOT-STUCK", "Blocked click on slot " + windowSlot
                        + " — clicked " + _sameSlotClickCount + " times in a row (cursor="
                        + describeStack(StorageHelper.getItemStackInCursorSlot()) + ")");
                return true;
            }
        } else {
            _lastClickedSlot = windowSlot;
            _sameSlotClickCount = 1;
        }
        return false;
    }

    public void clickSlot(Slot slot, int mouseButton, SlotActionType type) {
        if (slot == null) {
            return;
        }
        int windowSlot = slot.getWindowSlot();
        if (isSameSlotStuck(windowSlot, type)) {
            return;
        }
        if (!clickBudgetAvailable()) {
            return;
        }
        if (!canDoSlotAction()) {
            DebugLogger.getInstance().slotClickBlocked("SlotHandler", slot.getWindowSlot(), type);
            return;
        }

        ItemStack cursorBefore = StorageHelper.getItemStackInCursorSlot();
        ItemStack slotBefore = windowSlot >= 0 ? StorageHelper.getItemStackInSlot(slot) : ItemStack.EMPTY;
        DebugLogger.getInstance().slotClick("SlotHandler", slot.getWindowSlot(), mouseButton, type, cursorBefore, slotBefore);

        consumeClickBudget();
        clickWindowSlot(windowSlot, mouseButton, type);
    }

    public void clickSlotForce(Slot slot, int mouseButton, SlotActionType type) {
        if (slot == null) {
            return;
        }
        int windowSlot = slot.getWindowSlot();
        if (isSameSlotStuck(windowSlot, type)) {
            return;
        }
        if (!clickBudgetAvailable()) {
            return;
        }

        ItemStack cursorBefore = StorageHelper.getItemStackInCursorSlot();
        ItemStack slotBefore = windowSlot >= 0
                ? StorageHelper.getItemStackInSlot(slot)
                : ItemStack.EMPTY;
        DebugLogger.getInstance().slotClickForce("SlotHandler", slot.getWindowSlot(), mouseButton, type, cursorBefore, slotBefore);

        forceAllowNextSlotAction();
        clickWindowSlot(windowSlot, mouseButton, type);
    }

    private void clickWindowSlot(int windowSlot, int mouseButton, SlotActionType type) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        registerSlotAction();
        if (player.currentScreenHandler == null) return;
        net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
        int syncId = handler.syncId;
        List<ItemStack> beforeStacks = new ArrayList<>(handler.slots.size());
        for (net.minecraft.screen.slot.Slot screenSlot : handler.slots) {
            beforeStacks.add(screenSlot.getStack().copy());
        }

        try {
            // Persist the attempted click before entering Minecraft's handler. If a
            // third-party mixin ever blocks inside the click again, the final log
            // line will still identify the exact action instead of disappearing.
            DebugLogger.getInstance().flush();
            _mod.getController().clickSlot(syncId, windowSlot, mouseButton, type, player);
            DebugLogger.getInstance().log("SLOT-RESULT", "completed sync=" + syncId
                    + " slot=" + windowSlot + " type=" + type
                    + " cursor_after=" + describeStack(StorageHelper.getItemStackInCursorSlot())
                    + " handler=" + describeHandler(player));
            if (player.currentScreenHandler != handler) {
                DebugLogger.getInstance().log("SLOT-RESULT", "handler changed during click from "
                        + handler.getClass().getName() + " to " + describeHandler(player));
                return;
            }
            for (int i = 0; i < beforeStacks.size() && i < handler.slots.size(); i++) {
                ItemStack before = beforeStacks.get(i);
                ItemStack after = handler.getSlot(i).getStack();
                if (!ItemStack.areEqual(before, after)) {
                    Slot changed = Slot.getFromCurrentScreen(i);
                    if (changed != null) {
                        EventBus.publish(new SlotClickChangedEvent(changed, before, after.copy()));
                    }
                }
            }
        } catch (Exception e) {
            Debug.logWarning("Slot Click Error (ignored)");
            e.printStackTrace();
        }
    }

    private String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getCount() + "x" + stack.getItem().getTranslationKey();
    }

    private String describeHandler(ClientPlayerEntity player) {
        if (player == null || player.currentScreenHandler == null) return "null";
        var handler = player.currentScreenHandler;
        return handler.getClass().getName() + "{sync=" + handler.syncId
                + ",revision=" + handler.getRevision()
                + ",slots=" + handler.slots.size() + "}";
    }

    public void forceEquipItemToOffhand(Item toEquip) {
        if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() == toEquip) {
            return;
        }
        List<Slot> currentItemSlot = _mod.getItemStorage().getSlotsWithItemPlayerInventory(false,
                toEquip);
        for (Slot CurrentItemSlot : currentItemSlot) {
            if (!Slot.isCursor(CurrentItemSlot)) {
                _mod.getSlotHandler().clickSlot(CurrentItemSlot, 0, SlotActionType.PICKUP);
            } else {
                _mod.getSlotHandler().clickSlot(PlayerSlot.OFFHAND_SLOT, 0, SlotActionType.PICKUP);
            }
        }
    }

    public boolean forceEquipItem(Item toEquip) {

        // Already equipped
        if (StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() == toEquip) return true;

        // Always equip to the second slot. First + last is occupied by baritone.
        if (_mod.getPlayer() == null) return false;
        _mod.getPlayer().getInventory().selectedSlot = 1;

        // If our item is in our cursor, simply move it to the hotbar.
        boolean inCursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT).getItem() == toEquip;

        List<Slot> itemSlots = _mod.getItemStorage().getSlotsWithItemScreen(toEquip);
        if (itemSlots.size() != 0) {
            for (Slot ItemSlots : itemSlots) {
                int hotbar = 1;
                //_mod.getPlayer().getInventory().swapSlotWithHotbar();
                clickSlotForce(Objects.requireNonNull(ItemSlots), inCursor ? 0 : hotbar, inCursor ? SlotActionType.PICKUP : SlotActionType.SWAP);
                //registerSlotAction();
            }
            return true;
        }
        return false;
    }

    public boolean forceDeequipHitTool() {
        return forceDeequip(stack -> stack.getItem() instanceof ShovelItem ||
                stack.getItem() instanceof PickaxeItem ||
                stack.getItem() instanceof AxeItem ||
                stack.getItem() instanceof HoeItem);
    }
    public void forceDeequipRightClickableItem() {
        forceDeequip(stack -> {
                    Item item = stack.getItem();
                    return item instanceof BucketItem // water, lava, milk, fishes
                            || item instanceof EnderEyeItem
                            || item == Items.BOW
                            || item == Items.CROSSBOW
                            || item == Items.FLINT_AND_STEEL
                            || item == Items.FIRE_CHARGE
                            || item == Items.ENDER_PEARL
                            || item instanceof FireworkRocketItem
                            || item instanceof SpawnEggItem
                            || item == Items.END_CRYSTAL
                            || item == Items.EXPERIENCE_BOTTLE
                            || item instanceof PotionItem // includes splash/lingering
                            || item == Items.TRIDENT
                            || item == Items.WRITABLE_BOOK
                            || item == Items.WRITTEN_BOOK
                            || item instanceof FishingRodItem
                            || item instanceof OnAStickItem
                            || item == Items.COMPASS
                            || item instanceof EmptyMapItem
                            || item == Items.LEAD
                            || item == Items.SHIELD
                            || item instanceof ArmorItem;  // Armor items that can be right-clicked or equipped
                }
        );
    }


    /**
     * Tries to de-equip any item that we don't want equipped.
     *
     * @param isBad: Whether an item is bad/shouldn't be equipped
     * @return Whether we successfully de-equipped, or if we didn't have the item equipped at all.
     */
    public boolean forceDeequip(Predicate<ItemStack> isBad) {
        ItemStack equip = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        ItemStack cursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT);
        if (isBad.test(cursor)) {
            // Throw away cursor slot OR move
            Optional<Slot> fittableSlots = _mod.getItemStorage().getSlotThatCanFitInPlayerInventory(equip, false);
            if (fittableSlots.isEmpty()) {
                // Try to swap items with the first non-bad slot.
                for (Slot slot : Slot.getCurrentScreenSlots()) {
                    if (!isBad.test(StorageHelper.getItemStackInSlot(slot))) {
                        clickSlotForce(slot, 0, SlotActionType.PICKUP);
                        return false;
                    }
                }
                if (ItemHelper.canThrowAwayStack(_mod, cursor)) {
                    clickSlotForce(PlayerSlot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return true;
                }
                // Can't throw :(
                return false;
            } else {
                // Put in the empty/available slot.
                clickSlotForce(fittableSlots.get(), 0, SlotActionType.PICKUP);
                return true;
            }
        } else if (isBad.test(equip)) {
            // Pick up the item
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, SlotActionType.PICKUP);
            return false;
        } else if (equip.isEmpty() && !cursor.isEmpty()) {
            // cursor is good and equip is empty, so finish filling it in.
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, SlotActionType.PICKUP);
            return true;
        }
        // We're already de-equipped
        return true;
    }

    public void forceEquipSlot(Slot slot) {
        Slot target = PlayerSlot.getEquipSlot();
        clickSlotForce(slot, target.getInventorySlot(), SlotActionType.SWAP);
    }

    public boolean forceEquipItem(Item[] matches, boolean unInterruptable) {
        return forceEquipItem(new ItemTarget(matches, 1), unInterruptable);
    }

    public boolean forceEquipItem(ItemTarget toEquip, boolean unInterruptable) {
        if (toEquip == null) return false;

        //If the bot try to eat
        if (_mod.getFoodChain().needsToEat() && !unInterruptable) { //unless we really need to force equip the item
            return false; //don't equip the item for now
        }

        Slot target = PlayerSlot.getEquipSlot();
        if (target == null) return false;
        // Already equipped
        if (toEquip.matches(StorageHelper.getItemStackInSlot(target).getItem())) return true;

        for (Item item : toEquip.getMatches()) {
            if (_mod.getItemStorage().hasItem(item)) {
                if (forceEquipItem(item)) return true;
            }
        }
        return false;
    }

    // By default, don't force equip if the bot is eating.
    public boolean forceEquipItem(Item... toEquip) {
        return forceEquipItem(toEquip, false);
    }

public void refreshInventory() {
        if (MinecraftClient.getInstance().player == null
                || MinecraftClient.getInstance().currentScreen != null
                || !StorageHelper.getItemStackInCursorSlot().isEmpty())
            return;
        for (int i = 0; i < MinecraftClient.getInstance().player.getInventory().main.size(); ++i) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            if (slot == null) continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;
            // Reset same-slot guard before the pick-up-put-back pair
            _lastClickedSlot = -1;
            _sameSlotClickCount = 0;
            clickSlotForce(slot, 0, SlotActionType.PICKUP);
            clickSlotForce(slot, 0, SlotActionType.PICKUP);
        }
    }

    /**
     * Reset the same-slot consecutive click counter.
     * Call this when you intentionally need to click the same slot multiple times
     * as part of a deliberate multi-click transaction (e.g., place one, place another).
     */
    public void resetSameSlotGuard() {
        _lastClickedSlot = -1;
        _sameSlotClickCount = 0;
    }
}
