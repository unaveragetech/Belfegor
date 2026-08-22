package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Settings;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.RouteToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.ExternalAutomationGuard;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int MAX_DETAILED_SITE_CANDIDATES = 16;

    private record SiteCandidate(BlockPos position, int quickScore) {}

    private enum Phase {
        ORIENT_HOME,
        RESTART_VALIDATE,
        SUPPLY_PREFLIGHT,
        CAMP,
        STAGING_PREFLIGHT,
        STORAGE,
        WORKSHOP,
        ARMORY,
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
            "armory",
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
    private ExternalAutomationGuard.Lease _externalPrinterLease;

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
        _externalPrinterLease = ExternalAutomationGuard.suspendLitematicaPrinter("build-full-base");
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        BlockPos configured = mod.getModSettings().getHomeBasePosition();
        _dimension = WorldHelper.getCurrentDimension().name();
        boolean hasLockedHome = configured != null;
        if (hasLockedHome) {
            _home = configured;
            if (_setHomeHere && !_resume) {
                BaseMemory.getInstance().rememberInspection(_home, _dimension,
                        "home_lock", "persistence",
                        1, 0, 0, 1, "kept_existing_home",
                        "@build full here ignored here-position because home is locked; run @drop home first");
            }
        } else if (_resume) {
            _home = BaseMemory.getInstance().nearestBase(playerPos, _dimension)
                    .map(BaseMemory.BaseRecord::center)
                    .orElse(playerPos);
        } else {
            _home = _setHomeHere ? chooseNearbyBuildSite(mod, playerPos) : playerPos;
        }
        if (!hasLockedHome) {
            mod.getModSettings().setHomeBasePosition(_home);
            Settings.save(mod.getModSettings());
        }
        if (_setHomeHere && !_resume && !hasLockedHome) {
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
        // Resume an interrupted full-base run from its remembered phase.
        BaseMemory.getInstance().loadBuildPhase(_home, _dimension, "fullbase").ifPresent(saved -> {
            try {
                Phase restored = Phase.valueOf(saved);
                if (restored != Phase.DONE && restored != Phase.ORIENT_HOME) {
                    _phase = restored;
                }
            } catch (Exception ignored) {
            }
        });
        persistPhase();
        _activeTask = null;
        _validationIndex = 0;
    }

    private BlockPos chooseNearbyBuildSite(Belfegor mod, BlockPos origin) {
        if (mod.getWorld() == null) return origin;
        BlockPos best = normalizeBuildY(mod, origin);
        int bestScore = scoreBuildSite(mod, best) + estimateCampsiteClearBurden(mod, best) * 20;
        int search = Math.max(48, Math.min(96, _radius + 64));
        List<SiteCandidate> candidates = new ArrayList<>();
        for (int dx = -search; dx <= search; dx += 8) {
            for (int dz = -search; dz <= search; dz += 8) {
                BlockPos surface = findSurfaceNear(mod, origin.add(dx, 0, dz), origin.getY());
                if (surface == null) continue;
                candidates.add(new SiteCandidate(surface, quickSiteScore(mod, surface, origin)));
            }
        }
        candidates.sort(Comparator.comparingInt(SiteCandidate::quickScore));
        int detailed = Math.min(MAX_DETAILED_SITE_CANDIDATES, candidates.size());
        for (int i = 0; i < detailed; i++) {
            BlockPos surface = candidates.get(i).position();
            BlockPos normalized = normalizeBuildY(mod, surface);
            int score = scoreBuildSite(mod, normalized)
                    + estimateCampsiteClearBurden(mod, normalized) * 20
                    + (int) Math.sqrt(surface.getSquaredDistance(origin));
                if (score < bestScore) {
                    bestScore = score;
                    best = normalized;
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

    private int quickSiteScore(Belfegor mod, BlockPos center, BlockPos origin) {
        int score = (int) Math.sqrt(center.getSquaredDistance(origin));
        int sample = Math.min(_radius, 12);
        for (int dx = -sample; dx <= sample; dx += 6) {
            for (int dz = -sample; dz <= sample; dz += 6) {
                BlockPos surface = findSurfaceNear(mod, center.add(dx, 0, dz), center.getY());
                if (surface == null) {
                    score += 80;
                    continue;
                }
                int delta = Math.abs(surface.getY() - center.getY());
                score += delta * delta * 8;
                Block floor = mod.getWorld().getBlockState(surface.down()).getBlock();
                if (floor == Blocks.WATER || floor == Blocks.LAVA) score += 200;
                Block feet = mod.getWorld().getBlockState(surface).getBlock();
                Block head = mod.getWorld().getBlockState(surface.up()).getBlock();
                if (isTreeBlock(feet) || isTreeBlock(head)) score += 120;
            }
        }
        return score;
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
                    new BuildSupplyPreflightTask(_home, _radius, false, false,
                            !hasCompleteExpansion(BuildBaseExpansionTask.RoomType.FARMLAND)),
                    "Preparing full base supplies before campsite construction");
            case CAMP -> runPhase(mod, Phase.STAGING_PREFLIGHT,
                    new BuildCampsiteTask(_home, _radius),
                    "Building full base core campsite");
            case STAGING_PREFLIGHT -> runPhase(mod, Phase.STORAGE,
                    new BuildSupplyPreflightTask(_home, _radius, true, true,
                            !hasCompleteExpansion(BuildBaseExpansionTask.RoomType.FARMLAND)),
                    "Preparing central construction staging chest and inventory space");
            case STORAGE -> runExpansionPhase(mod, Phase.WORKSHOP,
                    BuildBaseExpansionTask.RoomType.STORAGE, "storage",
                    "Building full base bulk storage room");
            case WORKSHOP -> runExpansionPhase(mod, Phase.ARMORY,
                    BuildBaseExpansionTask.RoomType.WORKSHOP, "workshop",
                    "Building full base workshop");
            case ARMORY -> runExpansionPhase(mod, Phase.FARMLAND,
                    BuildBaseExpansionTask.RoomType.ARMORY, "armory",
                    "Building full base armory and reserve-gear storage");
            case FARMLAND -> runExpansionPhase(mod, Phase.MOBFARM,
                    BuildBaseExpansionTask.RoomType.FARMLAND, "farmland",
                    "Building full base hydrated crop farm");
            case MOBFARM -> runExpansionPhase(mod, Phase.REPAIR_VALIDATE,
                    BuildBaseExpansionTask.RoomType.MOBFARM, "mob_farm",
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
            persistPhase();
            return null;
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = GetToBlockTask.baseAware(mod, _home);
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
                setPhase(next);
                return null;
            }
            _activeTask = phaseTask;
        }
        setDebugState(debug);
        return _activeTask;
    }

    private Task runExpansionPhase(Belfegor mod, Phase next,
                                   BuildBaseExpansionTask.RoomType type,
                                   String name, String debug) {
        if (_activeTask == null && hasCompleteExpansion(type)) {
            BaseMemory.getInstance().rememberInspection(_home, _dimension, name,
                    "full_base_phase", 1, 0, 0, 1, "reused",
                    "existing complete connected " + type.name().toLowerCase(Locale.ROOT)
                            + " room reused; no duplicate expansion created");
            BaseMemory.getInstance().save();
            setPhase(next);
            return null;
        }
        return runPhase(mod, next, new BuildBaseExpansionTask(type, name), debug);
    }

    private boolean hasCompleteExpansion(BuildBaseExpansionTask.RoomType type) {
        Optional<BaseMemory.BaseRecord> base = BaseMemory.getInstance().baseAt(_home, _dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_home, _dimension));
        if (base.isEmpty()) return false;
        String expected = type.name().toLowerCase(Locale.ROOT);
        return base.get().modules.stream()
                .anyMatch(module -> expected.equals(normalize(module.type))
                        && module.parent != null && !module.parent.isBlank()
                        && BaseMemory.getInstance().moduleComplete(module));
    }

    private Task validateRoutes(Belfegor mod) {
        while (_validationIndex < VALIDATION_TARGETS.size()) {
            String target = VALIDATION_TARGETS.get(_validationIndex);
            Optional<BaseMemory.BaseModule> module = validationModule(target);
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
                List<BlockPos> waypoints = BaseMemory.getInstance()
                        .routeWaypoints(mod.getPlayer().getBlockPos(), center, _dimension);
                _activeTask = waypoints.isEmpty()
                        ? GetToBlockTask.baseAware(mod, center)
                        : new RouteToBlockTask(waypoints, center);
            }
            setDebugState("Validating route to full base room " + target + " at " + center.toShortString());
            return _activeTask;
        }
        BaseMemory.getInstance().rememberBase(_home, _dimension, _radius, 4, 5, "full_base_complete");
        BaseMemory.getInstance().save();
        setPhase(Phase.DONE);
        return null;
    }

    private void setPhase(Phase next) {
        _phase = next;
        persistPhase();
    }

    private void persistPhase() {
        if (_home == null || _dimension == null) return;
        if (_phase == Phase.DONE) {
            BaseMemory.getInstance().clearBuildPhase(_home, _dimension, "fullbase");
        } else {
            BaseMemory.getInstance().rememberBuildPhase(_home, _dimension, "fullbase", _phase.name());
        }
        BaseMemory.getInstance().save();
    }

    private Optional<BaseMemory.BaseModule> validationModule(String target) {
        Optional<BaseMemory.BaseRecord> base = BaseMemory.getInstance().baseAt(_home, _dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_home, _dimension));
        if (base.isEmpty()) return Optional.empty();
        String query = normalize(target);
        if (query.equals("core")) {
            return base.get().modules.stream()
                    .filter(module -> normalize(module.name).equals("core"))
                    .findFirst();
        }
        return base.get().modules.stream()
                .filter(module -> normalize(module.type).equals(query))
                .filter(module -> module.parent != null && !module.parent.isBlank())
                .filter(BaseMemory.getInstance()::moduleComplete)
                .min(Comparator.comparingDouble(module -> module.center().getSquaredDistance(_home)));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
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
        if (_externalPrinterLease != null) {
            _externalPrinterLease.close();
            _externalPrinterLease = null;
        }
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
