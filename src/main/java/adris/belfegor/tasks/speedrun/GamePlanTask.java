package adris.belfegor.tasks.speedrun;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.GamePlanMemory;
import adris.belfegor.tasks.construction.BuildBaseExpansionTask;
import adris.belfegor.tasks.construction.BuildCampsiteTask;
import adris.belfegor.tasks.resources.ToolSetTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.Optional;

/**
 * Drives the persistent long-term game plan (see {@link GamePlanMemory}) by
 * advancing one stage at a time, in the same spirit as the classic
 * {@code @gamer} beat-the-game route: tools -> food -> base -> nether ->
 * stronghold -> Ender Dragon.
 *
 * Progress is persisted, so stopping or crashing never resets the plan; the
 * next run picks up at the first unfinished stage.
 */
public class GamePlanTask extends Task {

    private enum Phase {
        INIT,
        RUN_STAGE,
        DONE
    }

    private final String _fromStage;
    private Phase _phase = Phase.INIT;
    private Task _activeTask;
    private GamePlanMemory.GameStage _currentStage;

    public GamePlanTask() {
        this(null);
    }

    /** Starts (or resumes) the plan at a specific stage id. */
    public GamePlanTask(String fromStage) {
        _fromStage = fromStage == null || fromStage.isBlank() ? null : fromStage.trim().toLowerCase();
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.INIT;
        _activeTask = null;
        _currentStage = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case INIT -> {
                GamePlanMemory memory = GamePlanMemory.getInstance();
                memory.ensureStages();
                if (!memory.isActive()) memory.setActive(true);
                _currentStage = pickStage(memory);
                if (_currentStage == null) {
                    memory.setActive(false);
                    memory.save();
                    _phase = Phase.DONE;
                    return null;
                }
                memory.markInProgress(_currentStage.id);
                memory.save();
                _phase = Phase.RUN_STAGE;
                return null;
            }
            case RUN_STAGE -> {
                if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
                    setDebugState("Game plan stage " + _currentStage.id
                            + " (" + _currentStage.name + "): " + _activeTask);
                    return _activeTask;
                }
                _activeTask = null;
                Task stageTask = stageTask(mod, _currentStage);
                if (stageTask == null) {
                    GamePlanMemory.getInstance().markDone(_currentStage.id,
                            completionNote(mod, _currentStage));
                    GamePlanMemory.getInstance().save();
                    _currentStage = null;
                    _phase = Phase.INIT;
                    return null;
                }
                _activeTask = stageTask;
                setDebugState("Game plan stage " + _currentStage.id + ": " + _currentStage.name);
                return _activeTask;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private GamePlanMemory.GameStage pickStage(GamePlanMemory memory) {
        if (_fromStage != null) {
            Optional<GamePlanMemory.GameStage> requested = memory.getStage(_fromStage);
            if (requested.isPresent()) {
                if (!requested.get().isDone()) return requested.get();
                // Already done: resume after it.
                boolean found = false;
                for (GamePlanMemory.GameStage stage : memory.getStages()) {
                    if (stage == null) continue;
                    if (!found && stage.id.equalsIgnoreCase(_fromStage)) {
                        found = true;
                        continue;
                    }
                    if (found && !stage.isDone()) return stage;
                }
                return null;
            }
        }
        return memory.nextStage().orElse(null);
    }

    private Task stageTask(Belfegor mod, GamePlanMemory.GameStage stage) {
        if (stage == null) return null;
        return switch (stage.id) {
            case "wood_tools" -> completeSetAtLeast(mod, ToolSetTask.Tier.WOOD)
                    ? null : new ToolSetTask(ToolSetTask.Tier.WOOD);
            case "stone_tools" -> completeSetAtLeast(mod, ToolSetTask.Tier.STONE)
                    ? null : new ToolSetTask(ToolSetTask.Tier.STONE);
            case "iron_tools" -> completeSetAtLeast(mod, ToolSetTask.Tier.IRON)
                    ? null : new ToolSetTask(ToolSetTask.Tier.IRON);
            case "diamond_tools" -> completeSetAtLeast(mod, ToolSetTask.Tier.DIAMOND)
                    ? null : new ToolSetTask(ToolSetTask.Tier.DIAMOND);
            case "food_supply" -> count(mod, Items.COOKED_BEEF) >= 8
                    ? null : TaskCatalogue.getItemTask("cooked_beef", 8);
            case "base_camp" -> coreComplete(mod) ? null : new BuildCampsiteTask(home(mod), 8);
            case "base_expansion" -> {
                BuildBaseExpansionTask.RoomType missing = nextMissingRoom(mod);
                yield missing == null ? null : new BuildBaseExpansionTask(missing, missing.name().toLowerCase());
            }
            case "nether_resources" -> {
                if (count(mod, Items.BLAZE_ROD) >= 4 && count(mod, Items.ENDER_PEARL) >= 6) {
                    yield null;
                }
                if (count(mod, Items.BLAZE_ROD) < 4) {
                    yield TaskCatalogue.getItemTask("blaze_rod", 4);
                }
                yield TaskCatalogue.getItemTask("ender_pearl", 6);
            }
            case "stronghold" -> count(mod, Items.ENDER_EYE) >= 12
                    ? null : TaskCatalogue.getItemTask("ender_eye", 12);
            case "end_dragon" -> new KillEnderDragonTask();
            default -> null;
        };
    }

    private String completionNote(Belfegor mod, GamePlanMemory.GameStage stage) {
        return switch (stage.id) {
            case "base_camp" -> "core camp at " + home(mod).toShortString();
            case "nether_resources" -> "blaze_rod=" + count(mod, Items.BLAZE_ROD)
                    + " ender_pearl=" + count(mod, Items.ENDER_PEARL);
            case "stronghold" -> "ender_eye=" + count(mod, Items.ENDER_EYE);
            default -> "stage completed";
        };
    }

    private boolean completeSetAtLeast(Belfegor mod, ToolSetTask.Tier tier) {
        ToolSetTask.Tier complete = ToolSetTask.highestCompleteSetTier(mod);
        return complete != null && complete.ordinal() >= tier.ordinal();
    }

    private boolean coreComplete(Belfegor mod) {
        BlockPos home = home(mod);
        String dimension = WorldHelper.getCurrentDimension().name();
        return BaseMemory.getInstance().baseAt(home, dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(home, dimension))
                .stream()
                .flatMap(base -> base.modules.stream())
                .anyMatch(module -> "core".equalsIgnoreCase(module.name)
                        && BaseMemory.getInstance().moduleComplete(module));
    }

    private BuildBaseExpansionTask.RoomType nextMissingRoom(Belfegor mod) {
        BuildBaseExpansionTask.RoomType[] order = {
                BuildBaseExpansionTask.RoomType.STORAGE,
                BuildBaseExpansionTask.RoomType.WORKSHOP,
                BuildBaseExpansionTask.RoomType.ARMORY,
                BuildBaseExpansionTask.RoomType.FARMLAND
        };
        BlockPos home = home(mod);
        String dimension = WorldHelper.getCurrentDimension().name();
        for (BuildBaseExpansionTask.RoomType type : order) {
            String expected = type.name().toLowerCase();
            boolean complete = BaseMemory.getInstance().baseAt(home, dimension)
                    .or(() -> BaseMemory.getInstance().nearestBase(home, dimension))
                    .stream()
                    .flatMap(base -> base.modules.stream())
                    .anyMatch(module -> expected.equals(normalize(module.type))
                            && module.parent != null && !module.parent.isBlank()
                            && BaseMemory.getInstance().moduleComplete(module));
            if (!complete) return type;
        }
        return null;
    }

    private BlockPos home(Belfegor mod) {
        BlockPos configured = mod.getModSettings().getHomeBasePosition();
        if (configured != null) return configured;
        BlockPos current = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        mod.getModSettings().setHomeBasePosition(current);
        adris.belfegor.Settings.save(mod.getModSettings());
        return current;
    }

    private int count(Belfegor mod, Item item) {
        return mod.getItemStorage().getItemCountInventoryOnly(item);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace(' ', '_');
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GamePlanTask task
                && java.util.Objects.equals(task._fromStage, _fromStage);
    }

    @Override
    protected String toDebugString() {
        return "Game plan stage=" + (_currentStage == null ? "none" : _currentStage.id)
                + " phase=" + _phase;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }

    /**
     * Human/AI-readable "what does this stage need right now" hint, computed
     * from live world/inventory state. Used by {@code @goal next}.
     */
    public String stageHint(Belfegor mod, GamePlanMemory.GameStage stage) {
        if (mod == null || stage == null || stage.id == null) return "";
        return switch (stage.id) {
            case "wood_tools", "stone_tools", "iron_tools", "diamond_tools" -> {
                ToolSetTask.Tier tier = tierForStage(stage.id);
                StringBuilder sb = new StringBuilder("Need full ")
                        .append(tier.name().toLowerCase(Locale.ROOT))
                        .append(" set:");
                for (Item tool : ToolSetTask.tierTools(tier)) {
                    sb.append(" ").append(toolName(tool)).append("=")
                            .append(count(mod, tool));
                }
                yield sb.toString();
            }
            case "food_supply" -> "cooked_beef " + count(mod, Items.COOKED_BEEF) + "/8";
            case "base_camp" -> "core camp "
                    + (coreComplete(mod) ? "complete" : "not built yet at " + home(mod).toShortString());
            case "base_expansion" -> {
                BuildBaseExpansionTask.RoomType missing = nextMissingRoom(mod);
                yield missing == null ? "all rooms complete"
                        : "next room: " + missing.name().toLowerCase(Locale.ROOT);
            }
            case "nether_resources" -> "blaze_rod " + count(mod, Items.BLAZE_ROD)
                    + "/4, ender_pearl " + count(mod, Items.ENDER_PEARL) + "/6";
            case "stronghold" -> "ender_eye " + count(mod, Items.ENDER_EYE) + "/12";
            case "end_dragon" -> "enter the End and defeat the Ender Dragon";
            default -> stage.description == null ? "" : stage.description;
        };
    }

    private static ToolSetTask.Tier tierForStage(String stageId) {
        return switch (stageId) {
            case "stone_tools" -> ToolSetTask.Tier.STONE;
            case "iron_tools" -> ToolSetTask.Tier.IRON;
            case "diamond_tools" -> ToolSetTask.Tier.DIAMOND;
            default -> ToolSetTask.Tier.WOOD;
        };
    }

    private static String toolName(Item tool) {
        return tool == null ? "?" : net.minecraft.registry.Registries.ITEM.getId(tool).getPath();
    }
}
