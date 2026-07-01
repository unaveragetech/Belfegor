package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds and validates the full modular Belfegor base in one command.
 *
 * This intentionally serializes the work. The campsite, each expansion room,
 * and each route-validation hop run one at a time so Baritone goals and
 * inventory/container interactions do not overlap each other.
 */
public class BuildFullBaseTask extends Task {

    private static final int DEFAULT_RADIUS = 12;

    private enum Phase {
        ORIENT_HOME,
        RESTART_VALIDATE,
        SUPPLY_PREFLIGHT,
        CAMP,
        STAGING_PREFLIGHT,
        STORAGE,
        WORKSHOP,
        FARMLAND,
        MOBFARM,
        REPAIR_VALIDATE,
        VALIDATE_ROUTES,
        DONE
    }

    private static final List<String> VALIDATION_TARGETS = List.of(
            "core",
            "storage",
            "workshop",
            "farmland",
            "mob_farm"
    );

    private final int _radius;
    private final boolean _setHomeHere;
    private final boolean _resume;
    private BlockPos _home;
    private String _dimension;
    private Phase _phase = Phase.CAMP;
    private Task _activeTask;
    private int _validationIndex;
    private boolean _restartExistingBase;

    public BuildFullBaseTask(int radius, boolean setHomeHere) {
        this(radius, setHomeHere, false);
    }

    public BuildFullBaseTask(int radius, boolean setHomeHere, boolean resume) {
        _radius = Math.max(8, Math.min(18, radius <= 0 ? DEFAULT_RADIUS : radius));
        _setHomeHere = setHomeHere;
        _resume = resume;
    }

    @Override
    protected void onStart(Belfegor mod) {
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        BlockPos configured = mod.getModSettings().getHomeBasePosition();
        _dimension = WorldHelper.getCurrentDimension().name();
        if (_resume) {
            _home = BaseMemory.getInstance().nearestBase(playerPos, _dimension)
                    .map(BaseMemory.BaseRecord::center)
                    .orElse(configured != null ? configured : playerPos);
        } else {
            _home = (_setHomeHere || configured == null) ? chooseNearbyBuildSite(mod, playerPos) : configured;
        }
        mod.getModSettings().setHomeBasePosition(_home);
        if (_setHomeHere && !_resume) {
            BaseMemory.getInstance().forgetAbandonedBasesFarFrom(_home, _dimension, _radius * 4.0);
        }
        _restartExistingBase = (_resume || !_setHomeHere) && BaseMemory.getInstance().nearestBase(_home, _dimension)
                .map(base -> !base.modules.isEmpty())
                .orElse(false);
        BaseMemory.getInstance().rememberBase(_home, _dimension, _radius, 4, 5,
                _resume ? "full_base_resume_orienting"
                        : _restartExistingBase ? "full_base_restart_orienting" : "full_base_started");
        BaseMemory.getInstance().save();
        _phase = (_resume || _restartExistingBase) ? Phase.ORIENT_HOME : Phase.SUPPLY_PREFLIGHT;
        _activeTask = null;
        _validationIndex = 0;
    }

    private BlockPos chooseNearbyBuildSite(Belfegor mod, BlockPos origin) {
        if (mod.getWorld() == null) return origin;
        BlockPos best = normalizeBuildY(mod, origin);
        int bestScore = scoreBuildSite(mod, best) + estimateCampsiteClearBurden(mod, best) * 20;
        int search = Math.max(72, Math.min(160, _radius + 128));
        for (int dx = -search; dx <= search; dx += 6) {
            for (int dz = -search; dz <= search; dz += 6) {
                BlockPos surface = findSurfaceNear(mod, origin.add(dx, 0, dz), origin.getY());
                if (surface == null) continue;
                BlockPos normalized = normalizeBuildY(mod, surface);
                int score = scoreBuildSite(mod, normalized)
                        + estimateCampsiteClearBurden(mod, normalized) * 20
                        + (int) Math.sqrt(surface.getSquaredDistance(origin));
                if (score < bestScore) {
                    bestScore = score;
                    best = normalized;
                }
            }
        }
        BlockPos normalized = normalizeBuildY(mod, best);
        if (!normalized.equals(origin)) {
            BaseMemory.getInstance().rememberInspection(normalized, WorldHelper.getCurrentDimension().name(),
                    "core", "site_selection", 1, 0, 0, 1, "selected",
                    "origin=" + origin.toShortString() + ";surface=" + best.toShortString()
                            + ";buildY=" + normalized.getY() + ";score=" + bestScore);
        }
        return normalized;
    }

    private BlockPos findSurfaceNear(Belfegor mod, BlockPos column, int originY) {
        for (int y = originY + 10; y >= originY - 14; y--) {
            BlockPos feet = new BlockPos(column.getX(), y, column.getZ());
            if (!WorldHelper.isSolid(mod, feet.down())) continue;
            if (!mod.getWorld().getBlockState(feet).isAir()) continue;
            if (!mod.getWorld().getBlockState(feet.up()).isAir()) continue;
            Block below = mod.getWorld().getBlockState(feet.down()).getBlock();
            if (below == Blocks.WATER || below == Blocks.LAVA) continue;
            return feet;
        }
        return null;
    }

    private int scoreBuildSite(Belfegor mod, BlockPos center) {
        int score = 0;
        int sampleRadius = Math.min(_radius + 3, 15);
        for (int dx = -sampleRadius; dx <= sampleRadius; dx += 3) {
            for (int dz = -sampleRadius; dz <= sampleRadius; dz += 3) {
                BlockPos localSurface = findSurfaceNear(mod, center.add(dx, 0, dz), center.getY());
                if (localSurface == null) {
                    score += 120;
                    continue;
                }
                int yDelta = Math.abs(localSurface.getY() - center.getY());
                score += yDelta * yDelta * 14;
                BlockPos floor = localSurface.down();
                Block floorBlock = mod.getWorld().getBlockState(floor).getBlock();
                if (floorBlock == Blocks.WATER || floorBlock == Blocks.LAVA) score += 80;
                if (!WorldHelper.isSolid(mod, floor)) score += 18;
                for (int h = 0; h <= 4; h++) {
                    BlockPos air = localSurface.add(0, h, 0);
                    Block block = mod.getWorld().getBlockState(air).getBlock();
                if (block == Blocks.AIR) continue;
                if (block == Blocks.WATER || block == Blocks.LAVA) {
                        score += 300;
                    } else if (isTreeBlock(block)) {
                        score += 260;
                    } else if (block == Blocks.TALL_GRASS
                            || block == Blocks.FERN || block == Blocks.LARGE_FERN
                            || block == Blocks.DANDELION || block == Blocks.POPPY) {
                        score += 1;
                    } else {
                        score += 8;
                    }
                }
            }
        }
        return score;
    }

    private int estimateCampsiteClearBurden(Belfegor mod, BlockPos center) {
        int burden = 0;
        int clearRadius = _radius + 5;
        for (int dx = -clearRadius; dx <= clearRadius; dx += 2) {
            for (int dz = -clearRadius; dz <= clearRadius; dz += 2) {
                boolean outsideWallGap = Math.abs(dx) > _radius || Math.abs(dz) > _radius;
                for (int h = 0; h <= 4; h++) {
                    Block block = mod.getWorld().getBlockState(center.add(dx, h, dz)).getBlock();
                    if (block == Blocks.AIR || block == Blocks.WATER) continue;
                    if (outsideWallGap) {
                        if (isTreeTrunkOrHazard(block)) burden += 8;
                    } else if (h > 0 || !isSoftGround(block)) {
                        burden += isTreeBlock(block) ? 10 : 3;
                    }
                }
            }
        }
        return burden;
    }

    private BlockPos normalizeBuildY(Belfegor mod, BlockPos center) {
        int sampleRadius = Math.min(_radius + 5, 18);
        int maxFeetY = center.getY();
        for (int dx = -sampleRadius; dx <= sampleRadius; dx += 3) {
            for (int dz = -sampleRadius; dz <= sampleRadius; dz += 3) {
                BlockPos localSurface = findSurfaceNear(mod, center.add(dx, 0, dz), center.getY());
                if (localSurface != null) {
                    maxFeetY = Math.max(maxFeetY, localSurface.getY());
                }
            }
        }
        return new BlockPos(center.getX(), maxFeetY, center.getZ());
    }

    private boolean isTreeBlock(Block block) {
        String key = block.getTranslationKey();
        return key.contains("_log")
                || key.contains("_wood")
                || key.contains("_leaves")
                || key.contains("mushroom");
    }

    private boolean isTreeTrunkOrHazard(Block block) {
        String key = block.getTranslationKey();
        return key.contains("_log")
                || key.contains("_wood")
                || key.contains("mushroom")
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH;
    }

    private boolean isSoftGround(Block block) {
        return block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.FARMLAND;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        return switch (_phase) {
            case ORIENT_HOME -> orientHome(mod);
            case RESTART_VALIDATE -> runPhase(mod, Phase.SUPPLY_PREFLIGHT,
                    new BuildBaseValidationTask(),
                    "Restarting partial base: validating existing rooms before new build work");
            case SUPPLY_PREFLIGHT -> runPhase(mod, Phase.CAMP,
                    new BuildSupplyPreflightTask(_home, _radius, false, false),
                    "Preparing full base supplies before campsite construction");
            case CAMP -> runPhase(mod, Phase.STAGING_PREFLIGHT,
                    new BuildCampsiteTask(_home, _radius),
                    "Building full base core campsite");
            case STAGING_PREFLIGHT -> runPhase(mod, Phase.STORAGE,
                    new BuildSupplyPreflightTask(_home, _radius, true, true),
                    "Preparing central construction staging chest and inventory space");
            case STORAGE -> runPhase(mod, Phase.WORKSHOP,
                    new BuildBaseExpansionTask(BuildBaseExpansionTask.RoomType.STORAGE, "storage"),
                    "Building full base storage room");
            case WORKSHOP -> runPhase(mod, Phase.FARMLAND,
                    new BuildBaseExpansionTask(BuildBaseExpansionTask.RoomType.WORKSHOP, "workshop"),
                    "Building full base workshop");
            case FARMLAND -> runPhase(mod, Phase.MOBFARM,
                    new BuildBaseExpansionTask(BuildBaseExpansionTask.RoomType.FARMLAND, "farmland"),
                    "Building full base hydrated crop farm");
            case MOBFARM -> runPhase(mod, Phase.REPAIR_VALIDATE,
                    new BuildBaseExpansionTask(BuildBaseExpansionTask.RoomType.MOBFARM, "mob_farm"),
                    "Building full base roofed mob farm");
            case REPAIR_VALIDATE -> runPhase(mod, Phase.VALIDATE_ROUTES,
                    new BuildBaseValidationTask(),
                    "Validating and repairing full base rooms");
            case VALIDATE_ROUTES -> validateRoutes(mod);
            case DONE -> null;
        };
    }

    private Task orientHome(Belfegor mod) {
        if (mod.getPlayer() != null && _home.getSquaredDistance(mod.getPlayer().getBlockPos()) <= 9) {
            BaseMemory.getInstance().rememberInspection(_home, _dimension, "core", "restart_orientation",
                    1, 0, 0, 1, "oriented", "standing in remembered home room before partial-base validation");
            BaseMemory.getInstance().save();
            _activeTask = null;
            _phase = Phase.RESTART_VALIDATE;
            return null;
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new GetToBlockTask(_home);
        }
        setDebugState((_resume ? "Resuming" : "Restarting")
                + " partial base: walking to remembered home room " + _home.toShortString());
        return _activeTask;
    }

    private Task runPhase(Belfegor mod, Phase next, Task phaseTask, String debug) {
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            if (_activeTask != null && _activeTask.isFinished(mod)) {
                _activeTask = null;
                BaseMemory.getInstance().rememberBase(_home, _dimension, _radius, 4, 5,
                        next == Phase.REPAIR_VALIDATE ? "full_base_rooms_built"
                                : next == Phase.VALIDATE_ROUTES ? "full_base_repaired"
                                : "full_base_" + next.name().toLowerCase(Locale.ROOT));
                BaseMemory.getInstance().save();
                _phase = next;
                return null;
            }
            _activeTask = phaseTask;
        }
        setDebugState(debug);
        return _activeTask;
    }

    private Task validateRoutes(Belfegor mod) {
        while (_validationIndex < VALIDATION_TARGETS.size()) {
            String target = VALIDATION_TARGETS.get(_validationIndex);
            Optional<BaseMemory.BaseModule> module = BaseMemory.getInstance()
                    .findNearestModule(_home, _dimension, target);
            if (module.isEmpty()) {
                BaseMemory.getInstance().rememberInspection(_home, _dimension, target, "route_validation",
                        1, 0, 1, 0, "missing", "no remembered module center");
                _validationIndex++;
                continue;
            }
            BlockPos center = module.get().center();
            if (mod.getPlayer() != null && center.getSquaredDistance(mod.getPlayer().getBlockPos()) <= 9) {
                BaseMemory.getInstance().rememberInspection(_home, _dimension, target, "route_validation",
                        1, 0, 0, 1, "reachable", "navigated to " + center.toShortString());
                BaseMemory.getInstance().save();
                _activeTask = null;
                _validationIndex++;
                continue;
            }
            if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
                _activeTask = new GetToBlockTask(center);
            }
            setDebugState("Validating route to full base room " + target + " at " + center.toShortString());
            return _activeTask;
        }
        BaseMemory.getInstance().rememberBase(_home, _dimension, _radius, 4, 5, "full_base_complete");
        BaseMemory.getInstance().save();
        _phase = Phase.DONE;
        return null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildFullBaseTask task
                && task._radius == _radius
                && task._setHomeHere == _setHomeHere
                && task._resume == _resume;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected String toDebugString() {
        return "Build full modular base phase=" + _phase
                + " radius=" + _radius
                + (_resume ? " resume" : "")
                + " home=" + (_home == null ? "unset" : _home.toShortString());
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
