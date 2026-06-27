package adris.belfegor.tasks.resources;

import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.MiningRequirement;
import adris.belfegor.util.helpers.ItemHelper;

public class CollectSaplingsTask extends MineAndCollectTask {
    public CollectSaplingsTask(int count) {
        super(new ItemTarget(ItemHelper.SAPLINGS, count), ItemHelper.SAPLING_SOURCES,
                MiningRequirement.HAND);
    }
}
