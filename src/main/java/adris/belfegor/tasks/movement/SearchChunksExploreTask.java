package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.eventbus.EventBus;
import adris.belfegor.eventbus.Subscription;
import adris.belfegor.eventbus.events.ChunkLoadEvent;
import adris.belfegor.tasksystem.Task;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Searches/explores a continuous "blob" of chunks, attempting to load in ALL nearby chunks that are part of this "blob"
 * <p>
 * You must define a function that determines whether a chunk is to be included within this "blob".
 * <p>
 * For instance, if you wish to explore an entire desert, this function will return whether a chunk is a desert chunk.
 */
public abstract class SearchChunksExploreTask extends Task {

    private final Object _searcherMutex = new Object();
    private final Set<ChunkPos> _alreadyExplored = new HashSet<>();
    private ChunkSearchTask _searcher;
    private Belfegor _mod;
    private Subscription<ChunkLoadEvent> _chunkLoadedSubscription;

    // Virtual
    protected ChunkPos getBestChunkOverride(Belfegor mod, List<ChunkPos> chunks) {
        return null;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _mod = mod;

        // Listen for chunk loading
        _chunkLoadedSubscription = EventBus.subscribe(ChunkLoadEvent.class, evt -> onChunkLoad(evt.chunk.getPos()));

        resetSearch(mod);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        synchronized (_searcherMutex) {
            if (_searcher == null) {
                setDebugState("Exploring/Searching for valid chunk");
                // Explore
                return getWanderTask(mod);
            }

            if (_searcher.isActive() && _searcher.isFinished(mod)) {
                Debug.logWarning("Target object search failed.");
                _alreadyExplored.addAll(_searcher.getSearchedChunks());
                _searcher = null;
            } else if (_searcher.finished()) {
                setDebugState("Searching for target object...");
                Debug.logMessage("Search finished.");
                _alreadyExplored.addAll(_searcher.getSearchedChunks());
                _searcher = null;
            }
            //Debug.logMessage("wtf: " + (_searcher == null? "(null)" :_searcher.finished()));
            setDebugState("Searching within chunks...");
            return _searcher;
        }
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        EventBus.unsubscribe(_chunkLoadedSubscription);
    }

    // When we find a valid chunk, start our search there.
    private void onChunkLoad(ChunkPos pos) {
        if (_searcher != null) return;
        if (!this.isActive()) return;
        if (isChunkWithinSearchSpace(_mod, pos)) {
            synchronized (_searcherMutex) {
                if (!_alreadyExplored.contains(pos)) {
                    Debug.logMessage("New searcher: " + pos);
                    _searcher = new SearchSubTask(pos);
                }
            }
        }
    }

    protected Task getWanderTask(Belfegor mod) {
        return new TimeoutWanderTask(true);
    }

    protected abstract boolean isChunkWithinSearchSpace(Belfegor mod, ChunkPos pos);

    public boolean failedSearch() {
        return _searcher == null;
    }

    public void resetSearch(Belfegor mod) {
        //Debug.logMessage("Search reset");
        _searcher = null;
        _alreadyExplored.clear();
        // We want to search the currently loaded chunks too!!!
        for (ChunkPos start : mod.getChunkTracker().getLoadedChunks()) {
            onChunkLoad(start);
        }
    }

    class SearchSubTask extends ChunkSearchTask {

        public SearchSubTask(ChunkPos start) {
            super(start);
        }

        @Override
        protected boolean isChunkPartOfSearchSpace(Belfegor mod, ChunkPos pos) {
            return isChunkWithinSearchSpace(mod, pos);
        }

        @Override
        public ChunkPos getBestChunk(Belfegor mod, List<ChunkPos> chunks) {
            ChunkPos override = getBestChunkOverride(mod, chunks);
            if (override != null) return override;
            return super.getBestChunk(mod, chunks);
        }

        @Override
        protected boolean isChunkSearchEqual(ChunkSearchTask other) {
            // Since we're keeping track of "_searcher", we expect the subchild routine to ALWAYS be consistent!
            return other == this;//return other instanceof SearchSubTask;
        }

        @Override
        protected String toDebugString() {
            return "Searching chunks...";
        }
    }

}
