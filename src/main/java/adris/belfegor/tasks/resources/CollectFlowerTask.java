package adris.belfegor.tasks.resources;

import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.MiningRequirement;
import adris.belfegor.util.helpers.ItemHelper;

public class CollectFlowerTask extends MineAndCollectTask {
    public CollectFlowerTask(int count) {
        super(new ItemTarget(ItemHelper.FLOWER, count), ItemHelper.itemsToBlocks(ItemHelper.FLOWER), MiningRequirement.HAND);
    }
}
