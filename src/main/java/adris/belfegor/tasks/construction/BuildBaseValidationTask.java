package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.schematic.BelfegorSchematic;
import adris.belfegor.schematic.LitematicSchematicLoader;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * Validates remembered base rooms and schedules productive repair work.
 *
 * The important property here is that repair never picks a random new room
 * while an old room is half-built. It first repairs the core campsite modules,
 * then repairs named expansion rooms using the remembered room name.
 */
public class BuildBaseValidationTask extends Task {

    private BlockPos _baseCenter;
    private String _dimension;
    private BaseMemory.BaseRecord _base;
    private Task _activeTask;
    private boolean _coreChecked;

    @Override
    protected void onStart(Belfegor mod) {
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        _dimension = WorldHelper.getCurrentDimension().name();
        _base = BaseMemory.getInstance().nearestBase(playerPos, _dimension)
                .orElse(null);
        _baseCenter = _base == null ? playerPos : _base.center();
        _activeTask = null;
        _coreChecked = false;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_base == null) {
            _base = BaseMemory.getInstance().rememberBase(_baseCenter, _dimension, 8, 4, 5,
                    "validation_created_base");
        }
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        if (_activeTask != null && _activeTask.isFinished(mod)) {
            _activeTask = null;
            _base = BaseMemory.getInstance().nearestBase(_baseCenter, _dimension).orElse(_base);
        }
        if (!_coreChecked) {
            _coreChecked = true;
            Optional<BelfegorSchematic> litematic = loadDefaultCampSchematic();
            int coreMismatches = litematic.map(schematic -> schematic.countMismatches(mod))
                    .orElseGet(() -> countCoreMismatches(mod));
            if (coreNeedsRepair() || coreMismatches > 0) {
                BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                        "core_campsite", "validation", 1, coreMismatches, 1, 0,
                        "repairing", "core campsite modules incomplete or blueprint mismatches="
                                + coreMismatches + "; rerunning campsite builder");
                BaseMemory.getInstance().save();
                if (litematic.isPresent()) {
                    _activeTask = new BuildRegionSchematicTask("camp litematic repair",
                            litematic.get().toBuildTargets(), false);
                    setDebugState("Repairing/building camp from parsed Litematica blueprint mismatches="
                            + coreMismatches + " blocks=" + litematic.get().totalBlocks());
                } else {
                    _activeTask = new BuildCampsiteTask(_baseCenter, Math.max(8, _base.radius));
                    setDebugState("Repairing incomplete core campsite modules");
                }
                return _activeTask;
            }
        }

        Optional<BaseMemory.BaseModule> next = _base.modules.stream()
                .filter(module -> isRepairableExpansion(module)
                        && !BaseMemory.getInstance().moduleComplete(module))
                .min(Comparator.comparingLong(module -> module.lastUpdated));
        if (next.isPresent()) {
            BaseMemory.BaseModule module = next.get();
            BuildBaseExpansionTask.RoomType type = repairType(module.type);
            BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                    module.name, "validation", 1, 0, 1, 0,
                    "repairing", "rerunning named expansion type=" + type);
            BaseMemory.getInstance().save();
            _activeTask = new BuildBaseExpansionTask(type, module.name);
            setDebugState("Repairing incomplete room " + module.name + " type=" + type);
            return _activeTask;
        }

        BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                "base", "validation", _base.modules.size(), 0, 0,
                _base.modules.size(), "complete", "all remembered repairable rooms complete");
        BaseMemory.getInstance().markBaseStatus(_baseCenter, _dimension, "validated_complete");
        BaseMemory.getInstance().save();
        return null;
    }

    private boolean coreNeedsRepair() {
        if (_base == null || _base.modules.isEmpty()) return true;
        String[] coreNames = {
                "core", "perimeter_wall", "interior_dividers",
                "crafting_workshop", "smelting_workshop", "storage_wing"
        };
        for (String name : coreNames) {
            Optional<BaseMemory.BaseModule> module = _base.modules.stream()
                    .filter(candidate -> normalize(candidate.name).equals(name))
                    .findFirst();
            if (module.isEmpty() || !BaseMemory.getInstance().moduleComplete(module.get())) {
                return true;
            }
        }
        return false;
    }

    private int countCoreMismatches(Belfegor mod) {
        if (_baseCenter == null) return 0;
        int radius = _base == null ? 8 : Math.max(8, _base.radius);
        Optional<BelfegorSchematic> litematic = loadDefaultCampSchematic();
        if (litematic.isPresent()) {
            return litematic.get().countMismatches(mod);
        }
        return BuildCampsiteTask.countCoreBlueprintMismatches(mod, _baseCenter, radius);
    }

    private Optional<BelfegorSchematic> loadDefaultCampSchematic() {
        File file = LitematicSchematicLoader.defaultCampFile();
        if (!file.exists()) return Optional.empty();
        try {
            BelfegorSchematic schematic = LitematicSchematicLoader.load(file, _baseCenter, _dimension);
            schematic.save(BelfegorSchematic.baseCoreFile(_dimension, _baseCenter));
            BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                    "camp_litematic", "schematic", schematic.totalBlocks(), 0, 0,
                    schematic.totalBlocks(), "loaded",
                    "imported " + file.getName() + " as authoritative campsite blueprint");
            return Optional.of(schematic);
        } catch (Exception e) {
            Debug.logWarning("Failed to load camp litematic for validation: " + e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isRepairableExpansion(BaseMemory.BaseModule module) {
        if (module == null) return false;
        String name = normalize(module.name);
        if (name.equals("mob_farm_entrance")
                || name.equals("construction_staging")
                || name.equals("construction_staging_chest")) {
            return false;
        }
        String type = normalize(module.type);
        return type.equals("farmland")
                || type.equals("storage")
                || type.equals("workshop")
                || type.equals("mob_farm")
                || type.equals("empty");
    }

    private BuildBaseExpansionTask.RoomType repairType(String type) {
        return BuildBaseExpansionTask.parseType(type == null ? "" : type);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildBaseValidationTask;
    }

    @Override
    protected String toDebugString() {
        return "Validate and repair remembered base rooms";
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _coreChecked
                && (_activeTask == null || _activeTask.isFinished(mod))
                && _base != null
                && !coreNeedsRepair()
                && countCoreMismatches(mod) == 0
                && _base.modules.stream().noneMatch(module ->
                isRepairableExpansion(module) && !BaseMemory.getInstance().moduleComplete(module));
    }
}
