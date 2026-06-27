package baritone.belfegor;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public class BelfegorSettings {

    private static final BelfegorSettings INSTANCE = new BelfegorSettings();

    // Mutex objects for thread safety
    private final Object _breakMutex = new Object();
    private final Object _placeMutex = new Object();
    private final Object _propertiesMutex = new Object();
    private final Object _globalHeuristicMutex = new Object();

    // Break/place avoidance
    private final Set<BlockPos> _blocksToAvoidBreaking = new HashSet<>();
    private final List<Predicate<BlockPos>> _breakAvoiders = new ArrayList<>();
    private final List<Predicate<BlockPos>> _placeAvoiders = new ArrayList<>();

    // Protected items
    private final List<Item> _protectedItems = new ArrayList<>();

    // Extra properties
    private final List<Predicate<BlockPos>> _forceWalkOnPredicates = new ArrayList<>();
    private final List<Predicate<BlockPos>> _forceAvoidWalkThroughPredicates = new ArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> _forceUseToolPredicates = new ArrayList<>();

    // Global heuristics
    private final List<BiFunction<Double, BlockPos, Double>> _globalHeuristics = new ArrayList<>();

    // Other settings
    private boolean _flowingWaterPass = false;
    private boolean _canWalkOnEndPortal = false;
    private boolean _interactionPaused = false;
    private boolean _placeBucketButDontFall = false;

    private BelfegorSettings() {}

    public static BelfegorSettings getInstance() {
        return INSTANCE;
    }

    // Mutex getters
    public Object getBreakMutex() { return _breakMutex; }
    public Object getPlaceMutex() { return _placeMutex; }
    public Object getPropertiesMutex() { return _propertiesMutex; }
    public Object getGlobalHeuristicMutex() { return _globalHeuristicMutex; }

    // Break/place avoidance
    public Set<BlockPos> getBlocksToAvoidBreaking() { return _blocksToAvoidBreaking; }
    public List<Predicate<BlockPos>> getBreakAvoiders() { return _breakAvoiders; }
    public List<Predicate<BlockPos>> getPlaceAvoiders() { return _placeAvoiders; }

    // Protected items
    public List<Item> getProtectedItems() { return _protectedItems; }

    // Extra properties
    public List<Predicate<BlockPos>> getForceWalkOnPredicates() { return _forceWalkOnPredicates; }
    public List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates() { return _forceAvoidWalkThroughPredicates; }
    public List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates() { return _forceUseToolPredicates; }

    // Global heuristics
    public List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics() { return _globalHeuristics; }

    // Flowing water pass
    public boolean isFlowingWaterPassAllowed() { return _flowingWaterPass; }
    public void setFlowingWaterPass(boolean allowed) { _flowingWaterPass = allowed; }

    // End portal walking
    public boolean isCanWalkOnEndPortal() { return _canWalkOnEndPortal; }
    public void canWalkOnEndPortal(boolean canWalk) { _canWalkOnEndPortal = canWalk; }

    // Interaction pause
    public boolean isInteractionPaused() { return _interactionPaused; }
    public void setInteractionPaused(boolean paused) { _interactionPaused = paused; }

    // Place bucket
    public void configurePlaceBucketButDontFall(boolean configure) { _placeBucketButDontFall = configure; }

    public void avoidBlockBreak(Predicate<BlockPos> predicate) {
        _breakAvoiders.add(predicate);
    }
    public void avoidBlockPlace(Predicate<BlockPos> predicate) {
        _placeAvoiders.add(predicate);
    }
    public boolean shouldAvoidBreaking(BlockPos pos) {
        for (Predicate<BlockPos> pred : _breakAvoiders) {
            if (pred.test(pos)) return true;
        }
        for (BlockPos avoid : _blocksToAvoidBreaking) {
            if (avoid.equals(pos)) return true;
        }
        return false;
    }
    public boolean shouldAvoidPlacingAt(BlockPos pos) {
        for (Predicate<BlockPos> pred : _placeAvoiders) {
            if (pred.test(pos)) return true;
        }
        return false;
    }
    public void allowSwimThroughLava(boolean allow) {
        // Stored internally
    }
}
