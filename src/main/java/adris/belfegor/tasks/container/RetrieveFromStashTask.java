package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.memory.ErrandMemory;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Walks to a remembered stash chest and withdraws supplies that a previous
 * task stored there (see {@link ErrandMemory}). This closes the loop for the
 * "collect supplies, store them at base, come back for them when needed"
 * behavior.
 */
public class RetrieveFromStashTask extends Task {

    private enum Phase {
        RESOLVE,
        RETRIEVE,
        DONE
    }

    private final BlockPos _home;
    private final ItemTarget[] _targets;
    private Phase _phase = Phase.RESOLVE;
    private Task _activeTask;
    private List<ErrandMemory.Errand> _stashes = new ArrayList<>();
    private int _index;

    public RetrieveFromStashTask(BlockPos home, ItemTarget... targets) {
        _home = home;
        _targets = targets == null ? new ItemTarget[0] : targets;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.RESOLVE;
        _activeTask = null;
        _index = 0;
        _stashes.clear();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case RESOLVE -> {
                resolveStashes(mod);
                if (_stashes.isEmpty()) {
                    _phase = Phase.DONE;
                    return null;
                }
                _phase = Phase.RETRIEVE;
                return null;
            }
            case RETRIEVE -> {
                return retrieveNext(mod);
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private void resolveStashes(Belfegor mod) {
        _stashes.clear();
        String dimension = WorldHelper.getCurrentDimension().name();
        float maxDistance = mod.getModSettings().getMaxResourceTravelDistance();
        for (ItemTarget target : _targets) {
            if (target == null) continue;
            for (Item item : target.getMatches()) {
                Optional<ErrandMemory.Errand> stash =
                        ErrandMemory.getInstance().findStash(_home, dimension, item);
                if (stash.isEmpty()) continue;
                ErrandMemory.Errand errand = stash.get();
                if (!ErrandMemory.getInstance().stashUsable(mod, errand, maxDistance)) continue;
                if (isAlreadySatisfied(mod, target)) continue;
                _stashes.add(errand);
            }
        }
        _stashes.sort((a, b) -> Integer.compare(b.remaining, a.remaining));
    }

    private Task retrieveNext(Belfegor mod) {
        while (_index < _stashes.size()) {
            ErrandMemory.Errand errand = _stashes.get(_index);
            BlockPos chest = errand.chestPos();
            if (_activeTask == null) {
                if (mod.getPlayer() != null
                        && chest.getSquaredDistance(mod.getPlayer().getBlockPos()) > 9) {
                    setDebugState("Walking to stash chest " + chest.toShortString());
                    _activeTask = GetToBlockTask.baseAware(mod, chest);
                    return _activeTask;
                }
                ItemTarget[] need = targetsForErrand(mod, errand);
                if (need.length == 0) {
                    _index++;
                    continue;
                }
                setDebugState("Withdrawing stash " + Arrays.toString(need)
                        + " from " + chest.toShortString());
                _activeTask = new PickupFromContainerTask(chest, need);
                return _activeTask;
            }
            if (_activeTask.isFinished(mod) || _activeTask.stopped()) {
                markErrandRetrieved(mod, errand);
                _activeTask = null;
                _index++;
                continue;
            }
            return _activeTask;
        }
        _phase = Phase.DONE;
        return null;
    }

    private ItemTarget[] targetsForErrand(Belfegor mod, ErrandMemory.Errand errand) {
        Optional<Item> item = ErrandMemory.itemFromId(errand.item);
        if (item.isEmpty()) return new ItemTarget[0];
        List<ItemTarget> result = new ArrayList<>();
        for (ItemTarget target : _targets) {
            if (target == null || !target.matches(item.get())) continue;
            int have = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            int need = Math.max(0, target.getTargetCount() - have);
            if (need <= 0) continue;
            result.add(new ItemTarget(item.get(), Math.min(need, errand.remaining)));
        }
        return result.toArray(ItemTarget[]::new);
    }

    private void markErrandRetrieved(Belfegor mod, ErrandMemory.Errand errand) {
        int withdrawn = 0;
        for (ItemTarget target : _targets) {
            if (target == null) continue;
            for (Item item : target.getMatches()) {
                if (ErrandMemory.itemFromId(errand.item).map(item::equals).orElse(false)) {
                    withdrawn += Math.max(0, target.getTargetCount());
                }
            }
        }
        ErrandMemory.getInstance().markRetrieved(errand, Math.min(errand.remaining, withdrawn));
        ErrandMemory.getInstance().save();
    }

    private boolean isAlreadySatisfied(Belfegor mod, ItemTarget target) {
        return target != null
                && mod.getItemStorage().getItemCountInventoryOnly(target.getMatches())
                >= target.getTargetCount();
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RetrieveFromStashTask task
                && task._home.equals(_home)
                && Arrays.equals(task._targets, _targets);
    }

    @Override
    protected String toDebugString() {
        return "Retrieve stash at " + (_home == null ? "?" : _home.toShortString())
                + " targets=" + Arrays.toString(_targets)
                + " stashes=" + _stashes.size();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        if (_phase == Phase.DONE) return true;
        if (_targets.length == 0) return true;
        return Arrays.stream(_targets).allMatch(target -> isAlreadySatisfied(mod, target));
    }
}
