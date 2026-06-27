package adris.belfegor.tasks;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Optional;

/**
 * Use this whenever you want to travel to a target position that may change.
 * <p>
 * https://www.notion.so/Closest-threshold-ing-system-utility-c3816b880402494ba9209c9f9b62b8bf
 */
public abstract class AbstractDoToClosestObjectTask<T> extends Task {

    private final HashMap<T, CachedHeuristic> _heuristicMap = new HashMap<>();
    private T _currentlyPursuing = null;
    private boolean _wasWandering;
    private Task _goalTask = null;

    // Cooldown: don't switch targets more than once per N ticks
    private int _switchCooldownTicks = 0;
    private static final int SWITCH_COOLDOWN = 20; // 1 second between switches

    // Distance threshold: switch if new target is within this fraction of current distance.
    // 0.75 = switch if new target is at least 25% closer than current.
    private static final double DISTANCE_SWITCH_FACTOR = 0.5625; // 0.75^2 for squared distance

    // Locked: when true, don't switch targets at all (e.g. actively mining)
    private boolean _locked = false;

    protected abstract Vec3d getPos(Belfegor mod, T obj);

    protected abstract Optional<T> getClosestTo(Belfegor mod, Vec3d pos);

    protected abstract Vec3d getOriginPos(Belfegor mod);

    protected abstract Task getGoalTask(T obj);

    protected abstract boolean isValid(Belfegor mod, T obj);

    // Virtual
    protected Task getWanderTask(Belfegor mod) {
        return new TimeoutWanderTask(true);
    }

    public void resetSearch() {
        _currentlyPursuing = null;
        _heuristicMap.clear();
        _goalTask = null;
        _locked = false;
        _switchCooldownTicks = 0;
    }

    public boolean wasWandering() {
        return _wasWandering;
    }

    /**
     * Lock the current target so the bot sticks to it until mining is complete.
     */
    protected void lockTarget() {
        _locked = true;
    }

    /**
     * Unlock so the bot can switch targets again.
     */
    protected void unlockTarget() {
        _locked = false;
    }

    protected boolean isTargetLocked() {
        return _locked;
    }

    private double getCurrentCalculatedHeuristic(Belfegor mod) {
        Optional<Double> ticksRemainingOp = mod.getClientBaritone().getPathingBehavior().ticksRemainingInSegment();
        return ticksRemainingOp.orElse(Double.POSITIVE_INFINITY);
    }

    private boolean isMovingToClosestPos(Belfegor mod) {
        return _goalTask != null;
    }

    @Override
    protected Task onTick(Belfegor mod) {

        _wasWandering = false;

        // Decrement switch cooldown
        if (_switchCooldownTicks > 0) {
            _switchCooldownTicks--;
        }

        // Reset our pursuit if our pursuing object no longer is pursuable.
        if (_currentlyPursuing != null && !isValid(mod, _currentlyPursuing)) {
            _heuristicMap.remove(_currentlyPursuing);
            _currentlyPursuing = null;
            _locked = false;
        }

        // Get closest object
        Optional<T> checkNewClosest = getClosestTo(mod, getOriginPos(mod));

        // Receive closest object and position
        // DON'T switch targets if locked or on cooldown
        if (checkNewClosest.isPresent() && !checkNewClosest.get().equals(_currentlyPursuing)) {
            if (!_locked && _switchCooldownTicks <= 0) {
                T newClosest = checkNewClosest.get();
                // Different closest object
                if (_currentlyPursuing == null) {
                    _currentlyPursuing = newClosest;
                } else {
                    if (isMovingToClosestPos(mod)) {
                        setDebugState("Moving towards closest...");
                        double currentHeuristic = getCurrentCalculatedHeuristic(mod);
                        double closestDistanceSqr = getPos(mod, _currentlyPursuing).squaredDistanceTo(mod.getPlayer().getPos());
                        int lastTick = WorldHelper.getTicks();

                        if (!_heuristicMap.containsKey(_currentlyPursuing)) {
                            _heuristicMap.put(_currentlyPursuing, new CachedHeuristic());
                        }
                        CachedHeuristic h = _heuristicMap.get(_currentlyPursuing);
                        h.updateHeuristic(currentHeuristic);
                        h.updateDistance(closestDistanceSqr);
                        h.setTickAttempted(lastTick);
                        if (_heuristicMap.containsKey(newClosest)) {
                            CachedHeuristic maybeReAttempt = _heuristicMap.get(newClosest);
                            double maybeClosestDistance = getPos(mod, newClosest).squaredDistanceTo(mod.getPlayer().getPos());
                            // Switch if new target is closer (better heuristic AND within distance factor)
                            if (maybeReAttempt.getHeuristicValue() < h.getHeuristicValue()
                                    && maybeClosestDistance < maybeReAttempt.getClosestDistanceSqr() * DISTANCE_SWITCH_FACTOR) {
                                setDebugState("Retrying old heuristic!");
                                _currentlyPursuing = newClosest;
                                maybeReAttempt.updateDistance(maybeClosestDistance);
                                _switchCooldownTicks = SWITCH_COOLDOWN;
                            }
                        } else {
                            // New object with no history — switch if meaningfully closer
                            double newDistanceSqr = getPos(mod, newClosest).squaredDistanceTo(mod.getPlayer().getPos());
                            if (newDistanceSqr < closestDistanceSqr * DISTANCE_SWITCH_FACTOR) {
                                setDebugState("Trying out NEW pursuit (much closer)");
                                _currentlyPursuing = newClosest;
                                _switchCooldownTicks = SWITCH_COOLDOWN;
                            }
                        }
                    } else {
                        setDebugState("Waiting for move task to kick in...");
                    }
                }
            }
        }

        if (_currentlyPursuing != null) {
            _goalTask = getGoalTask(_currentlyPursuing);
            return _goalTask;
        } else {
            _goalTask = null;
        }

        //noinspection ConstantConditions
        if (checkNewClosest.isEmpty() && _currentlyPursuing == null) {
            setDebugState("Waiting for calculations I think (wandering)");
            _wasWandering = true;
            return getWanderTask(mod);
        }

        setDebugState("Waiting for calculations I think (NOT wandering)");
        return null;
    }

    private static class CachedHeuristic {

        private double _closestDistanceSqr;
        private int _tickAttempted;
        private double _heuristicValue;

        public CachedHeuristic() {
            _closestDistanceSqr = Double.POSITIVE_INFINITY;
            _heuristicValue = Double.POSITIVE_INFINITY;
        }

        public CachedHeuristic(double closestDistanceSqr, int tickAttempted, double heuristicValue) {
            _closestDistanceSqr = closestDistanceSqr;
            _tickAttempted = tickAttempted;
            _heuristicValue = heuristicValue;
        }

        public double getHeuristicValue() {
            return _heuristicValue;
        }

        public void updateHeuristic(double heuristicValue) {
            _heuristicValue = Math.min(_heuristicValue, heuristicValue);
        }

        public double getClosestDistanceSqr() {
            return _closestDistanceSqr;
        }

        public void updateDistance(double closestDistanceSqr) {
            _closestDistanceSqr = Math.min(_closestDistanceSqr, closestDistanceSqr);
        }

        public int getTickAttempted() {
            return _tickAttempted;
        }

        public void setTickAttempted(int tickAttempted) {
            _tickAttempted = tickAttempted;
        }
    }
}
