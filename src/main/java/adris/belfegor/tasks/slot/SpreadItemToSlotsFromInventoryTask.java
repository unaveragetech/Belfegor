package adris.belfegor.tasks.slot;

import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.slots.Slot;

public class SpreadItemToSlotsFromInventoryTask extends SpreadItemToSlotsTask {
    public SpreadItemToSlotsFromInventoryTask(ItemTarget toMove, Slot[] destinations) {
        super(toMove, destinations, mod -> mod.getItemStorage().getSlotsWithItemPlayerInventory(true, toMove.getMatches()));
    }
}