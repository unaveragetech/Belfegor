package adris.belfegor.eventbus.events;

import net.minecraft.util.hit.BlockHitResult;

public class BlockInteractEvent {
    public BlockHitResult hitResult;

    public BlockInteractEvent(BlockHitResult hitResult) {
        this.hitResult = hitResult;
    }
}
