package adris.belfegor.eventbus.events;

import net.minecraft.util.math.ChunkPos;

public class ChunkUnloadEvent {
    public ChunkPos chunkPos;

    public ChunkUnloadEvent(ChunkPos chunkPos) {
        this.chunkPos = chunkPos;
    }
}
