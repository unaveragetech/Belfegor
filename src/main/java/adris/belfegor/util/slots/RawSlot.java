package adris.belfegor.util.slots;

/**
 * A direct window-slot wrapper for cases where the Minecraft screen handler
 * index is already known and must not be translated through an inventory
 * mapping. This is intentionally narrow: use it for open container slots that
 * are addressed by raw handler index, such as shulker contents slots 0-26.
 */
public class RawSlot extends Slot {

    public RawSlot(int windowSlot) {
        super(windowSlot, false);
    }

    @Override
    protected int inventorySlotToWindowSlot(int inventorySlot) {
        return inventorySlot;
    }

    @Override
    protected int windowSlotToInventorySlot(int windowSlot) {
        return -999;
    }

    @Override
    protected String getName() {
        return "Raw";
    }
}
