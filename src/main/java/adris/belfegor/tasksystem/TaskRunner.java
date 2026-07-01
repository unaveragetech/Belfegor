package adris.belfegor.tasksystem;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.debug.DebugLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TaskRunner {

    private final ArrayList<TaskChain> _chains = new ArrayList<>();
    private final Belfegor _mod;
    private boolean _active;

    private TaskChain _cachedCurrentTaskChain = null;
    private InterruptSnapshot _lastInterrupt = null;

    public TaskRunner(Belfegor mod) {
        _mod = mod;
        _active = false;
    }

    public void tick() {
        if (!_active || !Belfegor.inGame()) return;
        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : _chains) {
            if (!chain.isActive()) continue;
            float priority = chain.getPriority(_mod);
            if (priority > maxPriority) {
                maxPriority = priority;
                maxChain = chain;
            }
        }
        if (_cachedCurrentTaskChain != null && maxChain != _cachedCurrentTaskChain) {
            recordInterrupt(_cachedCurrentTaskChain, maxChain, "chain-switch");
            _cachedCurrentTaskChain.onInterrupt(_mod, maxChain);
        }
        _cachedCurrentTaskChain = maxChain;
        if (maxChain != null) {
            maxChain.tick(_mod);
        }
    }

    public void addTaskChain(TaskChain chain) {
        _chains.add(chain);
    }

    public void enable() {
        if (!_active) {
            _mod.getBehaviour().push();
            _mod.getBehaviour().setPauseOnLostFocus(false);
        }
        _active = true;
    }

    public void disable() {
        if (_active) {
            _mod.getBehaviour().pop();
        }
        for (TaskChain chain : _chains) {
            chain.stop(_mod);
        }
        _active = false;

        Debug.logMessage("Stopped");
    }

    public TaskChain getCurrentTaskChain() {
        return _cachedCurrentTaskChain;
    }

    public InterruptSnapshot getLastInterrupt() {
        return _lastInterrupt;
    }

    public void annotateLastInterrupt(String outcome, Task interruptedRoot, boolean actuallyInterrupted) {
        if (_lastInterrupt == null) return;
        _lastInterrupt = _lastInterrupt.withOutcome(outcome, interruptedRoot, actuallyInterrupted);
        DebugLogger.getInstance().log("TASK-INTERRUPT",
                "outcome=" + _lastInterrupt.outcome()
                        + " from=" + _lastInterrupt.fromChain()
                        + " to=" + _lastInterrupt.toChain()
                        + " root=" + _lastInterrupt.interruptedRoot()
                        + " actuallyInterrupted=" + _lastInterrupt.actuallyInterrupted()
                        + " ageMs=" + _lastInterrupt.ageMs());
    }

    private void recordInterrupt(TaskChain from, TaskChain to, String reason) {
        _lastInterrupt = new InterruptSnapshot(
                System.currentTimeMillis(),
                from == null ? "none" : from.getName(),
                to == null ? "none" : to.getName(),
                from == null ? "" : from.describeTaskChain(),
                to == null ? "" : to.describeTaskChain(),
                "",
                false,
                reason);
        DebugLogger.getInstance().log("TASK-INTERRUPT",
                "reason=" + reason
                        + " from=" + _lastInterrupt.fromChain()
                        + " to=" + _lastInterrupt.toChain()
                        + " fromTasks=" + _lastInterrupt.fromTasks()
                        + " toTasks=" + _lastInterrupt.toTasks());
    }

    public List<TaskChain> getAllChains() {
        return Collections.unmodifiableList(_chains);
    }

    // Kinda jank ngl
    public Belfegor getMod() {
        return _mod;
    }

    public record InterruptSnapshot(
            long timestampMs,
            String fromChain,
            String toChain,
            String fromTasks,
            String toTasks,
            String interruptedRoot,
            boolean actuallyInterrupted,
            String outcome
    ) {
        public long ageMs() {
            return Math.max(0L, System.currentTimeMillis() - timestampMs);
        }

        private InterruptSnapshot withOutcome(String outcome, Task interruptedRoot, boolean actuallyInterrupted) {
            return new InterruptSnapshot(
                    timestampMs,
                    fromChain,
                    toChain,
                    fromTasks,
                    toTasks,
                    interruptedRoot == null ? "" : interruptedRoot.toDebugString(),
                    actuallyInterrupted,
                    outcome == null ? "" : outcome);
        }
    }
}
