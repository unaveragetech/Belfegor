package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers significant locations the bot has discovered.
 * Persists to JSON so the bot learns across sessions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class LocationMemory {

    private static LocationMemory INSTANCE = new LocationMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_locations.json";

    private final Map<String, List<RememberedLocation>> _locations = new ConcurrentHashMap<>();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class RememberedLocation {
        public double x;
        public double y;
        public double z;
        public String dimension;
        public long lastSeen;
        public int visitCount;
        public String note;

        public RememberedLocation() {}

        public RememberedLocation(double x, double y, double z, String dimension, String note) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.note = note != null ? note : "";
            this.lastSeen = System.currentTimeMillis();
            this.visitCount = 1;
        }

        public BlockPos toBlockPos() {
            return new BlockPos((int) x, (int) y, (int) z);
        }

        public double distanceTo(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public static LocationMemory getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(gameDir, FILE_NAME);
        if (file.exists()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                INSTANCE = mapper.readValue(file, LocationMemory.class);
            } catch (Exception e) {
                INSTANCE = new LocationMemory();
            }
        }
    }

    public void remember(String category, double x, double y, double z, String dimension, String note) {
        RememberedLocation loc = new RememberedLocation(x, y, z, dimension, note);
        List<RememberedLocation> list = _locations.computeIfAbsent(category, k -> new ArrayList<>());

        for (RememberedLocation existing : list) {
            if (existing.distanceTo(x, y, z) < 256) {
                existing.lastSeen = System.currentTimeMillis();
                existing.visitCount++;
                if (note != null && !note.isEmpty()) {
                    existing.note = note;
                }
                _dirty = true;
                return;
            }
        }

        list.add(loc);
        _dirty = true;
    }

    public void forget(String category, double x, double y, double z) {
        List<RememberedLocation> list = _locations.get(category);
        if (list == null) return;
        list.removeIf(loc -> loc.distanceTo(x, y, z) < 256);
        _dirty = true;
    }

    public Optional<RememberedLocation> getNearest(String category, double px, double py, double pz, String dimension) {
        List<RememberedLocation> list = _locations.get(category);
        if (list == null || list.isEmpty()) return Optional.empty();

        RememberedLocation nearest = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (RememberedLocation loc : list) {
            if (dimension != null && !dimension.isEmpty() && !dimension.equals(loc.dimension)) continue;
            double dist = loc.distanceTo(px, py, pz);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = loc;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public List<RememberedLocation> getAll(String category) {
        return _locations.getOrDefault(category, Collections.emptyList());
    }

    public List<RememberedLocation> getAllInRange(String category, double px, double py, double pz, double rangeSq) {
        List<RememberedLocation> list = _locations.get(category);
        if (list == null) return Collections.emptyList();
        List<RememberedLocation> result = new ArrayList<>();
        for (RememberedLocation loc : list) {
            if (loc.distanceTo(px, py, pz) <= rangeSq) {
                result.add(loc);
            }
        }
        result.sort(Comparator.comparingDouble(l -> l.distanceTo(px, py, pz)));
        return result;
    }

    public void save() {
        if (!_dirty) return;
        try {
            java.io.File dir = new java.io.File(FOLDER);
            dir.mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(dir, FILE_NAME), this);
            _dirty = false;
        } catch (Exception e) {
            // silently fail
        }
    }

    public Set<String> getCategories() {
        return _locations.keySet();
    }

    public void cleanup(int maxAgeMs) {
        long now = System.currentTimeMillis();
        for (List<RememberedLocation> list : _locations.values()) {
            list.removeIf(loc -> (now - loc.lastSeen) > maxAgeMs && loc.visitCount < 3);
        }
        _dirty = true;
    }
}
