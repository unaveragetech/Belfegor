package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.container.ShulkerInteractionTask;
import adris.belfegor.tasks.entity.AbstractDoToEntityTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.Optional;

public class CollectMilkTask extends ResourceTask {

    private final int _count;

    public CollectMilkTask(int targetCount) {
        super(Items.MILK_BUCKET, targetCount);
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        // Recognize milk buckets we already own. If the requirement is already
        // satisfied there is nothing to milk: finishing here prevents the bug
        // where the bot ignored owned milk and planned to craft more buckets,
        // mine more iron, and milk more cows for a cake it could craft already.
        int milkHave = mod.getItemStorage().getItemCount(Items.MILK_BUCKET);
        int needMilk = Math.max(0, _count - milkHave);
        if (needMilk <= 0) {
            setDebugState("Already have " + milkHave + "/" + _count + " milk buckets");
            return null;
        }

        // Prefer milk already stashed in a carried shulker before gathering
        // buckets or hunting cows.
        ItemTarget milkTarget = new ItemTarget(Items.MILK_BUCKET, needMilk);
        if (ShulkerInteractionTask.carriedShulkerContains(mod, milkTarget)) {
            setDebugState("Withdrawing milk from carried shulker");
            return new ShulkerInteractionTask(
                    ShulkerInteractionTask.Mode.RETRIEVE, milkTarget);
        }

        // Make sure we have every empty bucket needed before cow interaction.
        // Recipes like cake need three milk buckets. The old loop crafted one
        // bucket, found a cow, milked once, then repeated iron/coal/furnace
        // acquisition for the next bucket. Batch the bucket requirement first
        // so iron/fuel gathering and smelting can be planned in one pass.
        int emptyBucketsHave = mod.getItemStorage().getItemCount(Items.BUCKET);
        int bucketsNeeded = Math.max(0, needMilk - emptyBucketsHave);
        if (bucketsNeeded > 0) {
            setDebugState("Preparing " + bucketsNeeded
                    + " buckets before milking (have " + milkHave
                    + " milk, " + emptyBucketsHave + " empty)");
            return TaskCatalogue.getItemTask(Items.BUCKET, bucketsNeeded);
        }
        // Dimension
        if (!mod.getEntityTracker().entityFound(CowEntity.class) && isInWrongDimension(mod)) {
            return getToCorrectDimensionTask(mod);
        }
        return new MilkCowTask();
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectMilkTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " milk buckets.";
    }

    static class MilkCowTask extends AbstractDoToEntityTask {

        private int _milkFailTicks;

        public MilkCowTask() {
            super(0, -1, -1);
            _milkFailTicks = 0;
        }

        @Override
        protected boolean isSubEqual(AbstractDoToEntityTask other) {
            return other instanceof MilkCowTask;
        }

        @Override
        protected Task onEntityInteract(Belfegor mod, Entity entity) {
            if (!mod.getItemStorage().hasItem(Items.BUCKET)) {
                Debug.logWarning("Failed to milk cow because you have no bucket.");
                return null;
            }
            int before = mod.getItemStorage().getItemCount(Items.MILK_BUCKET);
            ItemStack mainHand = mod.getPlayer() == null
                    ? ItemStack.EMPTY : mod.getPlayer().getMainHandStack();
            // Only re-equip when the bucket is not already in hand; otherwise
            // every tick re-equips the "best tool" and the bot never milks.
            if (mainHand.getItem() != Items.BUCKET) {
                if (!mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
                    return null;
                }
            }
            mod.getController().interactEntity(mod.getPlayer(), entity, Hand.MAIN_HAND);
            int after = mod.getItemStorage().getItemCount(Items.MILK_BUCKET);
            if (after > before) {
                _milkFailTicks = 0;
            } else {
                _milkFailTicks++;
                if (_milkFailTicks % 60 == 0) {
                    Debug.logWarning("Milking made no progress after " + _milkFailTicks
                            + " attempts; check that cows are reachable and the bot holds a bucket.");
                }
            }
            return null;
        }

        @Override
        protected Optional<Entity> getEntityTarget(Belfegor mod) {
            return mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), CowEntity.class);
        }

        @Override
        protected String toDebugString() {
            return "Milking Cow";
        }
    }
}
