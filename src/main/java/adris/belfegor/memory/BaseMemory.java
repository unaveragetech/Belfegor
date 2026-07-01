package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

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
                || status.endsWith("_complete")
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
                if (module.x == pos.getX() && module.y == pos.getY() && module.z == pos.getZ()) {
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
                .filter(module -> module.x == pos.getX() && module.y == pos.getY() && module.z == pos.getZ())
                .findFirst();
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
