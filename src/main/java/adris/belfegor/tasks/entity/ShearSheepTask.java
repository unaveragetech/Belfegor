package adris.belfegor.tasks.entity;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.tasksystem.Task;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.Optional;

public class ShearSheepTask extends AbstractDoToEntityTask {

    public ShearSheepTask() {
        super(0, -1, -1);
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        return other instanceof ShearSheepTask;
    }

    @Override
    protected Task onEntityInteract(Belfegor mod, Entity entity) {
        if (!mod.getItemStorage().hasItem(Items.SHEARS)) {
            Debug.logWarning("Failed to shear sheep because you have no shears.");
            return null;
        }
        if (mod.getSlotHandler().forceEquipItem(Items.SHEARS)) {
            mod.getController().interactEntity(mod.getPlayer(), entity, Hand.MAIN_HAND);
        }


        return null;
    }

    @Override
    protected Optional<Entity> getEntityTarget(Belfegor mod) {
        return mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                entity -> {
                    if (entity instanceof SheepEntity sheep) {
                        return sheep.isShearable() && !sheep.isSheared();
                    }
                    return false;
                }, SheepEntity.class
        );
    }

    @Override
    protected String toDebugString() {
        return "Shearing Sheep";
    }
}
