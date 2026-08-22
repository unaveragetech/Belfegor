package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structured persistent memory for @player home bases.
 *
 * LocationMemory is intentionally broad and point-based. BaseMemory is the
 * higher-level model: a base has bounds, rooms/modules, construction stages,
 * safety clearances, and timestamps. This lets @player expand an existing base
 * without treating every run like a fresh campsite.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class BaseMemory {

    private static BaseMemory INSTANCE = new BaseMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_bases.json";

    private final Map<String, BaseRecord> _bases = new ConcurrentHashMap<>();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class BaseRecord {
        public String id = "";
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public int radius;
        public int wallHeight;
        public int exteriorClearance;
        public long createdAt;
        public long lastUpdated;
        public String status = "planned";
        public List<BaseModule> modules = new ArrayList<>();
        public List<BaseInspection> inspections = new ArrayList<>();
        /** Per-build-task phase bookkeeping so interrupted construction can resume. */
        public Map<String, String> buildPhases = new HashMap<>();

        public BaseRecord() {}

        public BaseRecord(String id, BlockPos center, String dimension,
                          int radius, int wallHeight, int exteriorClearance) {
            this.id = id;
            this.dimension = dimension;
            this.x = center.getX();
            this.y = center.getY();
            this.z = center.getZ();
            this.radius = radius;
            this.wallHeight = wallHeight;
            this.exteriorClearance = exteriorClearance;
            this.createdAt = System.currentTimeMillis();
            this.lastUpdated = this.createdAt;
        }

        public BlockPos center() {
            return new BlockPos(x, y, z);
        }

        public double distanceSq(BlockPos pos) {
            return center().getSquaredDistance(pos);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class BaseModule {
        public String name = "";
        public String type = "";
        public int x;
        public int y;
        public int z;
        public int width = 1;
        public int depth = 1;
        public int height = 1;
        public int centerX;
        public int centerY;
        public int centerZ;
        public int progressDone;
        public int progressTotal;
        public String status = "planned";
        public String note = "";
        public String parent = "";
        public String direction = "";
        public int hallLength;
        public int hallWidth;
        public long lastUpdated;

        public BaseModule() {}

        public BaseModule(String name, String type, BlockPos anchor,
                          int width, int depth, int height,
                          String status, String note) {
            this.name = name;
            this.type = type;
            this.x = anchor.getX();
            this.y = anchor.getY();
            this.z = anchor.getZ();
            this.width = width;
            this.depth = depth;
            this.height = height;
            this.centerX = anchor.getX() + width / 2;
            this.centerY = anchor.getY();
            this.centerZ = anchor.getZ() + depth / 2;
            this.status = status;
            this.note = note == null ? "" : note;
            this.lastUpdated = System.currentTimeMillis();
        }

        public BlockPos center() {
            return new BlockPos(centerX, centerY, centerZ);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class BaseInspection {
        public String module = "";
        public String type = "";
        public int checked;
        public int blocked;
        public int missing;
        public int complete;
        public String status = "unknown";
        public String note = "";
        public long inspectedAt;

        public BaseInspection() {}
    }

    public static BaseMemory getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(new File(gameDir, FOLDER), FILE_NAME);
        if (!file.exists()) {
            file = new File(gameDir, FILE_NAME);
        }
        if (!file.exists()) {
            INSTANCE = new BaseMemory();
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            INSTANCE = mapper.readValue(file, BaseMemory.class);
        } catch (Exception e) {
            INSTANCE = new BaseMemory();
        }
    }

    public BaseRecord rememberBase(BlockPos center, String dimension,
                                   int radius, int wallHeight,
                                   int exteriorClearance, String status) {
        String id = baseId(dimension, center);
        BaseRecord record = _bases.computeIfAbsent(id,
                ignored -> new BaseRecord(id, center, dimension, radius, wallHeight, exteriorClearance));
        record.dimension = dimension;
        record.x = center.getX();
        record.y = center.getY();
        record.z = center.getZ();
        record.radius = Math.max(record.radius, radius);
        record.wallHeight = Math.max(record.wallHeight, wallHeight);
        record.exteriorClearance = Math.max(record.exteriorClearance, exteriorClearance);
        if (status != null && !status.isBlank()) record.status = status;
        record.lastUpdated = System.currentTimeMillis();
        _dirty = true;
        return record;
    }

    public void rememberModule(BlockPos baseCenter, String dimension, String name, String type,
                               BlockPos anchor, int width, int depth, int height,
                               String status, String note) {
        rememberModule(baseCenter, dimension, name, type, anchor, width, depth, height,
                status, note, "", "", 0, 0);
    }

    public void rememberModule(BlockPos baseCenter, String dimension, String name, String type,
                               BlockPos anchor, int width, int depth, int height,
                               String status, String note, String parent,
                               String direction, int hallLength, int hallWidth) {
        BaseRecord base = rememberBase(baseCenter, dimension, 4, 3, 5, "planned");
        Optional<BaseModule> existing = base.modules.stream()
                .filter(module -> module.name.equals(name))
                .findFirst();
        BaseModule module = existing.orElseGet(() -> {
            BaseModule created = new BaseModule();
            base.modules.add(created);
            return created;
        });
        module.name = name;
        module.type = type;
        module.x = anchor.getX();
        module.y = anchor.getY();
        module.z = anchor.getZ();
        module.width = width;
        module.depth = depth;
        module.height = height;
        module.centerX = anchor.getX() + width / 2;
        module.centerY = anchor.getY();
        module.centerZ = anchor.getZ() + depth / 2;
        module.status = status == null || status.isBlank() ? module.status : status;
        module.note = note == null ? "" : note;
        module.parent = parent == null ? "" : parent;
        module.direction = direction == null ? "" : direction;
        module.hallLength = Math.max(0, hallLength);
        module.hallWidth = Math.max(0, hallWidth);
        module.lastUpdated = System.currentTimeMillis();
        base.lastUpdated = module.lastUpdated;
        _dirty = true;
    }

    public void rememberModuleProgress(BlockPos baseCenter, String dimension, String name,
                                       int done, int total, String status, String note) {
        BaseRecord base = rememberBase(baseCenter, dimension, 4, 4, 5, "planned");
        base.modules.stream()
                .filter(module -> module.name.equals(name))
                .findFirst()
                .ifPresent(module -> {
                    module.progressDone = Math.max(0, done);
                    module.progressTotal = Math.max(0, total);
                    if (status != null && !status.isBlank()) module.status = status;
                    if (note != null) module.note = note;
                    module.lastUpdated = System.currentTimeMillis();
                    base.lastUpdated = module.lastUpdated;
                    _dirty = true;
                });
    }

    public void rememberInspection(BlockPos baseCenter, String dimension,
                                   String moduleName, String type,
                                   int checked, int blocked, int missing,
                                   int complete, String status, String note) {
        BaseRecord base = rememberBase(baseCenter, dimension, 4, 4, 5, "planned");
        BaseInspection inspection = new BaseInspection();
        inspection.module = moduleName == null ? "" : moduleName;
        inspection.type = type == null ? "" : type;
        inspection.checked = checked;
        inspection.blocked = blocked;
        inspection.missing = missing;
        inspection.complete = complete;
        inspection.status = status == null || status.isBlank() ? "unknown" : status;
        inspection.note = note == null ? "" : note;
        inspection.inspectedAt = System.currentTimeMillis();
        base.inspections.removeIf(existing ->
                existing.module.equals(inspection.module)
                        && existing.type.equals(inspection.type));
        base.inspections.add(inspection);
        base.lastUpdated = inspection.inspectedAt;
        _dirty = true;
    }

    public Optional<BaseModule> findModule(String dimension, String nameOrType) {
        if (nameOrType == null || nameOrType.isBlank()) return Optional.empty();
        String query = normalize(nameOrType);
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .flatMap(base -> base.modules.stream())
                .filter(module -> normalize(module.name).equals(query)
                        || normalize(module.type).equals(query)
                        || normalize(module.name).equals("home_room_" + query)
                        || normalize(module.name).contains(query))
                .max(Comparator.comparingLong(module -> module.lastUpdated));
    }

    public Optional<BaseModule> findNearestModule(BlockPos pos, String dimension, String nameOrType) {
        if (pos == null) return findModule(dimension, nameOrType);
        String query = normalize(nameOrType);
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .flatMap(base -> base.modules.stream())
                .filter(module -> query.isBlank()
                        || normalize(module.name).equals(query)
                        || normalize(module.type).equals(query)
                        || normalize(module.name).equals("home_room_" + query)
                        || normalize(module.name).contains(query))
                .min(Comparator.comparingDouble(module -> module.center().getSquaredDistance(pos)));
    }

    public int countModulesOfType(BaseRecord base, String type) {
        if (base == null || type == null) return 0;
        String query = normalize(type);
        int count = 0;
        for (BaseModule module : base.modules) {
            if (normalize(module.type).equals(query)) count++;
        }
        return count;
    }

    public boolean footprintOverlaps(BaseRecord base, String ignoreName,
                                     BlockPos anchor, int width, int depth,
                                     int margin) {
        if (base == null || anchor == null) return false;
        String ignored = normalize(ignoreName);
        int ax1 = anchor.getX() - Math.max(0, margin);
        int az1 = anchor.getZ() - Math.max(0, margin);
        int ax2 = anchor.getX() + Math.max(1, width) - 1 + Math.max(0, margin);
        int az2 = anchor.getZ() + Math.max(1, depth) - 1 + Math.max(0, margin);
        for (BaseModule module : base.modules) {
            if (module == null) continue;
            if (!ignored.isBlank() && normalize(module.name).equals(ignored)) continue;
            if (normalize(module.type).equals("hall") || normalize(module.type).equals("access")) continue;
            int bx1 = module.x - Math.max(0, margin);
            int bz1 = module.z - Math.max(0, margin);
            int bx2 = module.x + Math.max(1, module.width) - 1 + Math.max(0, margin);
            int bz2 = module.z + Math.max(1, module.depth) - 1 + Math.max(0, margin);
            if (ax1 <= bx2 && ax2 >= bx1 && az1 <= bz2 && az2 >= bz1) {
                return true;
            }
        }
        return false;
    }

    public boolean hasModuleNamed(BaseRecord base, String name) {
        if (base == null || name == null || name.isBlank()) return false;
        String query = normalize(name);
        return base.modules.stream().anyMatch(module -> normalize(module.name).equals(query));
    }

    public boolean moduleComplete(BaseModule module) {
        if (module == null) return false;
        String status = normalize(module.status);
        return status.equals("complete")
                || status.equals("reachable");
    }

    public boolean isProtectedFixturePosition(BlockPos pos, String dimension) {
        if (pos == null) return false;
        for (BaseRecord base : _bases.values()) {
            if (base == null) continue;
            if (dimension != null && !dimension.isBlank() && !dimension.equals(base.dimension)) continue;
            for (BaseModule module : base.modules) {
                if (module == null) continue;
                if (!isProtectedFixture(module)) continue;
                if (moduleContains(module, pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Optional<BaseModule> findProtectedFixture(BlockPos pos, String dimension) {
        if (pos == null) return Optional.empty();
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .flatMap(base -> base.modules.stream())
                .filter(this::isProtectedFixture)
                .filter(module -> moduleContains(module, pos))
                .findFirst();
    }

    /**
     * Remembers which phase a named build task reached for a base, so an
     * interrupted {@code @camp} / {@code @build} run can resume instead of
     * treating the base as freshly started.
     */
    public void rememberBuildPhase(BlockPos baseCenter, String dimension, String task, String phase) {
        if (baseCenter == null || phase == null || phase.isBlank()) return;
        BaseRecord base = rememberBase(baseCenter, dimension, 4, 3, 5, "building");
        base.buildPhases.put(normalize(task), phase.trim());
        base.lastUpdated = System.currentTimeMillis();
        _dirty = true;
    }

    public Optional<String> loadBuildPhase(BlockPos baseCenter, String dimension, String task) {
        if (baseCenter == null) return Optional.empty();
        return baseAt(baseCenter, dimension)
                .or(() -> nearestBase(baseCenter, dimension))
                .map(base -> base.buildPhases.get(normalize(task)))
                .filter(phase -> phase != null && !phase.isBlank());
    }

    public void clearBuildPhase(BlockPos baseCenter, String dimension, String task) {
        if (baseCenter == null) return;
        baseAt(baseCenter, dimension).ifPresent(base -> {
            if (base.buildPhases.remove(normalize(task)) != null) {
                _dirty = true;
            }
        });
    }

    /**
     * True when the position is inside one of the remembered base footprints
     * (with a small margin). Navigation inside a base should avoid breaking
     * its own walls/doors and prefer the remembered doorways and halls.
     */
    public boolean isInsideBase(BlockPos pos, String dimension, int margin) {
        if (pos == null) return false;
        int m = Math.max(0, margin);
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .anyMatch(base -> Math.abs(pos.getX() - base.x) <= base.radius + m
                        && Math.abs(pos.getZ() - base.z) <= base.radius + m
                        && Math.abs(pos.getY() - base.y) <= Math.max(6, base.wallHeight + 2) + m);
    }

    public Optional<BaseRecord> baseContaining(BlockPos pos, String dimension) {
        if (pos == null) return Optional.empty();
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .filter(base -> Math.abs(pos.getX() - base.x) <= base.radius + 2
                        && Math.abs(pos.getZ() - base.z) <= base.radius + 2)
                .min(Comparator.comparingDouble(base -> base.distanceSq(pos)));
    }

    public Optional<BaseModule> moduleContaining(BlockPos pos, String dimension) {
        if (pos == null) return Optional.empty();
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .flatMap(base -> base.modules.stream())
                .filter(module -> module != null && moduleContains(module, pos))
                .min(Comparator.comparingInt(this::moduleVolume));
    }

    private int moduleVolume(BaseModule module) {
        return Math.max(1, module.width) * Math.max(1, module.depth) * Math.max(1, module.height);
    }

    /**
     * Computes a rough waypoint route between two positions inside a base by
     * walking the remembered room graph (rooms connected through halls and
     * parent/child relationships). Returns an empty list when no base/module
     * graph applies, meaning the caller should just path directly.
     */
    public List<BlockPos> routeWaypoints(BlockPos from, BlockPos to, String dimension) {
        List<BlockPos> result = new ArrayList<>();
        if (from == null || to == null || from.equals(to)) return result;
        BaseRecord base = baseContaining(from, dimension).orElse(null);
        if (base == null) base = baseContaining(to, dimension).orElse(null);
        if (base == null) return result;

        Optional<BaseModule> start = moduleContaining(from, dimension);
        Optional<BaseModule> end = moduleContaining(to, dimension);
        if (start.isEmpty() || end.isEmpty()) return result;

        String startKey = normalize(start.get().name);
        String endKey = normalize(end.get().name);
        if (startKey.equals(endKey)) return result;

        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<BaseModule> queue = new ArrayDeque<>();
        queue.add(start.get());
        visited.add(startKey);
        BaseModule found = null;
        while (!queue.isEmpty() && found == null) {
            BaseModule current = queue.poll();
            for (BaseModule neighbor : base.modules) {
                if (neighbor == null) continue;
                String key = normalize(neighbor.name);
                if (visited.contains(key)) continue;
                if (!modulesConnected(current, neighbor)) continue;
                visited.add(key);
                parent.put(key, normalize(current.name));
                queue.add(neighbor);
                if (key.equals(endKey)) {
                    found = neighbor;
                    break;
                }
            }
        }
        if (found == null) return result;

        List<BaseModule> path = new ArrayList<>();
        String cursor = endKey;
        int guard = 0;
        while (cursor != null && !cursor.equals(startKey) && guard++ < 64) {
            final String key = cursor;
            Optional<BaseModule> node = base.modules.stream()
                    .filter(module -> module != null && normalize(module.name).equals(key))
                    .findFirst();
            if (node.isEmpty()) break;
            path.add(0, node.get());
            cursor = parent.get(key);
        }
        for (BaseModule module : path) {
            BlockPos center = module.center();
            if (!center.equals(from) && !center.equals(to)) {
                result.add(center);
            }
        }
        return result;
    }

    private boolean modulesConnected(BaseModule a, BaseModule b) {
        if (a == null || b == null) return false;
        String aName = normalize(a.name);
        String bName = normalize(b.name);
        if (!normalize(a.parent).isBlank() && normalize(a.parent).equals(bName)) return true;
        if (!normalize(b.parent).isBlank() && normalize(b.parent).equals(aName)) return true;
        if (aName.equals("core") || bName.equals("core")) {
            return boundingBoxesNear(a, b, 6);
        }
        return boundingBoxesNear(a, b, 6);
    }

    private boolean boundingBoxesNear(BaseModule a, BaseModule b, int gap) {
        int aMinX = Math.min(a.x, a.x + Math.max(1, a.width) - 1) - gap;
        int aMaxX = Math.max(a.x, a.x + Math.max(1, a.width) - 1) + gap;
        int aMinZ = Math.min(a.z, a.z + Math.max(1, a.depth) - 1) - gap;
        int aMaxZ = Math.max(a.z, a.z + Math.max(1, a.depth) - 1) + gap;
        int bMinX = Math.min(b.x, b.x + Math.max(1, b.width) - 1) - gap;
        int bMaxX = Math.max(b.x, b.x + Math.max(1, b.width) - 1) + gap;
        int bMinZ = Math.min(b.z, b.z + Math.max(1, b.depth) - 1) - gap;
        int bMaxZ = Math.max(b.z, b.z + Math.max(1, b.depth) - 1) + gap;
        return aMinX <= bMaxX && aMaxX >= bMinX && aMinZ <= bMaxZ && aMaxZ >= bMinZ;
    }

    private boolean moduleContains(BaseModule module, BlockPos pos) {
        if (module == null || pos == null) return false;
        int width = Math.max(1, module.width);
        int depth = Math.max(1, module.depth);
        int height = Math.max(1, module.height);
        return pos.getX() >= module.x && pos.getX() < module.x + width
                && pos.getY() >= module.y && pos.getY() < module.y + height
                && pos.getZ() >= module.z && pos.getZ() < module.z + depth;
    }

    private boolean isProtectedFixture(BaseModule module) {
        String name = normalize(module.name);
        String type = normalize(module.type);
        return name.equals("construction_staging")
                || name.equals("storage_wing")
                || name.equals("crafting_workshop")
                || name.equals("smelting_workshop")
                || name.endsWith("_fixture")
                || type.equals("fixture");
    }

    public void markBaseStatus(BlockPos center, String dimension, String status) {
        rememberBase(center, dimension, 4, 3, 5, status);
    }

    public Optional<BaseRecord> nearestBase(BlockPos pos, String dimension) {
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .min(Comparator.comparingDouble(base -> base.distanceSq(pos)));
    }

    public Optional<BaseRecord> baseAt(BlockPos center, String dimension) {
        if (center == null) return Optional.empty();
        String id = baseId(dimension, center);
        BaseRecord exact = _bases.get(id);
        if (exact != null) return Optional.of(exact);
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .filter(base -> base.center().equals(center))
                .findFirst();
    }

    public boolean forgetBase(BlockPos center, String dimension) {
        if (center == null) return false;
        int before = _bases.size();
        _bases.entrySet().removeIf(entry -> {
            BaseRecord base = entry.getValue();
            if (base == null) return true;
            if (dimension != null && !dimension.isBlank() && !dimension.equals(base.dimension)) return false;
            return base.center().equals(center);
        });
        boolean removed = before != _bases.size();
        if (removed) _dirty = true;
        return removed;
    }

    /** Removes every remembered base within a radius of the given position. */
    public int forgetBasesNear(BlockPos center, String dimension, double radius) {
        if (center == null) return 0;
        double radiusSq = Math.max(0, radius) * Math.max(0, radius);
        int before = _bases.size();
        _bases.entrySet().removeIf(entry -> {
            BaseRecord base = entry.getValue();
            if (base == null) return true;
            if (dimension != null && !dimension.isBlank() && !dimension.equals(base.dimension)) return false;
            return base.distanceSq(center) <= radiusSq;
        });
        int removed = before - _bases.size();
        if (removed > 0) _dirty = true;
        return removed;
    }

    public int forgetAbandonedBasesFarFrom(BlockPos center, String dimension, double minimumDistance) {
        if (center == null) return 0;
        double minimumDistanceSq = Math.max(0, minimumDistance) * Math.max(0, minimumDistance);
        int before = _bases.size();
        _bases.entrySet().removeIf(entry -> {
            BaseRecord base = entry.getValue();
            if (base == null) return true;
            if (dimension != null && !dimension.isBlank() && !dimension.equals(base.dimension)) return false;
            if (base.center().equals(center)) return false;
            if (base.distanceSq(center) < minimumDistanceSq) return false;
            return isAbandoned(base);
        });
        int removed = before - _bases.size();
        if (removed > 0) _dirty = true;
        return removed;
    }

    private boolean isAbandoned(BaseRecord base) {
        String status = normalize(base.status);
        if (status.equals("complete")
                || status.equals("full_base_complete")
                || status.equals("reachable")
                || status.endsWith("_complete")) {
            return false;
        }
        boolean hasCompletedModule = base.modules.stream().anyMatch(this::moduleComplete);
        if (hasCompletedModule) return false;
        return status.equals("planned")
                || status.equals("started")
                || status.equals("full_base_started")
                || status.startsWith("full_base_")
                || status.endsWith("_started");
    }

    public List<BaseRecord> getAllBases() {
        return new ArrayList<>(_bases.values());
    }

    public void save() {
        if (!_dirty) return;
        try {
            File dir = new File(FOLDER);
            dir.mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(dir, FILE_NAME), this);
            _dirty = false;
        } catch (Exception ignored) {
        }
    }

    private static String baseId(String dimension, BlockPos center) {
        return (dimension == null ? "unknown" : dimension)
                + ":" + center.getX() + "," + center.getY() + "," + center.getZ();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace(' ', '_');
    }
}
