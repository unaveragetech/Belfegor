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

    public void markBaseStatus(BlockPos center, String dimension, String status) {
        rememberBase(center, dimension, 4, 3, 5, status);
    }

    public Optional<BaseRecord> nearestBase(BlockPos pos, String dimension) {
        return _bases.values().stream()
                .filter(base -> dimension == null || dimension.isBlank() || dimension.equals(base.dimension))
                .min(Comparator.comparingDouble(base -> base.distanceSq(pos)));
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
