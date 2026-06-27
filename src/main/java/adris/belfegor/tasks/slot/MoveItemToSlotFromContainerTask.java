package adris.belfegor.tasks.slot;

import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.slots.Slot;

public class MoveItemToSlotFromContainerTask extends MoveItemToSlotTask {
    public MoveItemToSlotFromContainerTask(ItemTarget toMove, Slot destination) {
        super(toMove, destination, mod -> mod.getItemStorage().getSlotsWithItemContainer(toMove.getMatches()));
    }
}
