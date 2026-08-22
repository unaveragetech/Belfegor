package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistent long-term game plan modeled on the classic {@code @gamer} route.
 *
 * Instead of one monolithic speedrun, the plan is a list of stages with
 * tracked status (pending / in_progress / done). The bot advances one stage at
 * a time, and the ledger survives restarts, so long-term play always has a
 * concrete next goal and visible progress.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class GamePlanMemory {

    private static GamePlanMemory INSTANCE = new GamePlanMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_gameplan.json";

    private final List<GameStage> stages = new ArrayList<>();
    private boolean active = false;
    private long startedAt = 0;
    private long completedAt = 0;
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class GameStage {
        public String id = "";
        public String name = "";
        public String description = "";
        public String status = "pending";
        public long startedAt = 0;
        public long completedAt = 0;
        public String note = "";

        public boolean isDone() {
            return "done".equals(status);
        }
    }

    public static GamePlanMemory getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(new File(gameDir, FOLDER), FILE_NAME);
        if (!file.exists()) file = new File(gameDir, FILE_NAME);
        if (!file.exists()) {
            INSTANCE = new GamePlanMemory();
            INSTANCE.ensureStages();
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            INSTANCE = mapper.readValue(file, GamePlanMemory.class);
            INSTANCE.ensureStages();
        } catch (Exception e) {
            INSTANCE = new GamePlanMemory();
            INSTANCE.ensureStages();
        }
    }

    public void ensureStages() {
        if (!stages.isEmpty()) return;
        addStage("wood_tools", "Wooden tool set",
                "Craft a complete wooden tool set (pickaxe, axe, shovel, sword, hoe).");
        addStage("stone_tools", "Stone tool set",
                "Upgrade to a complete stone tool set.");
        addStage("iron_tools", "Iron tool set",
                "Upgrade to a complete iron tool set.");
        addStage("diamond_tools", "Diamond tool set",
                "Upgrade to a complete diamond tool set - good enough for most tasks.");
        addStage("food_supply", "Food supply",
                "Keep a supply of cooked food so the bot never starves.");
        addStage("base_camp", "Home base",
                "Build the core camp at the locked home position.");
        addStage("base_expansion", "Base expansion",
                "Build storage, workshop, armory, and farmland rooms.");
        addStage("nether_resources", "Nether resources",
                "Collect blaze rods and ender pearls.");
        addStage("stronghold", "Stronghold",
                "Craft ender eyes and find the stronghold portal.");
        addStage("end_dragon", "Defeat the Ender Dragon",
                "Enter the End and defeat the Ender Dragon.");
        _dirty = true;
    }

    private void addStage(String id, String name, String description) {
        GameStage stage = new GameStage();
        stage.id = id;
        stage.name = name;
        stage.description = description;
        stages.add(stage);
    }

    public List<GameStage> getStages() {
        return new ArrayList<>(stages);
    }

    public Optional<GameStage> getStage(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return stages.stream()
                .filter(stage -> stage != null && stage.id.equalsIgnoreCase(id.trim()))
                .findFirst();
    }

    /** The stage the bot should work on next. */
    public Optional<GameStage> nextStage() {
        return stages.stream()
                .filter(stage -> stage != null && !stage.isDone())
                .findFirst();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean value) {
        active = value;
        if (value && startedAt == 0) {
            startedAt = System.currentTimeMillis();
        }
        if (!value && allDone() && completedAt == 0) {
            completedAt = System.currentTimeMillis();
        }
        _dirty = true;
    }

    public void markInProgress(String id) {
        getStage(id).ifPresent(stage -> {
            if (!"done".equals(stage.status)) {
                stage.status = "in_progress";
                if (stage.startedAt == 0) stage.startedAt = System.currentTimeMillis();
                _dirty = true;
            }
        });
    }

    public void markDone(String id, String note) {
        getStage(id).ifPresent(stage -> {
            stage.status = "done";
            stage.completedAt = System.currentTimeMillis();
            if (note != null && !note.isBlank()) stage.note = note;
            _dirty = true;
        });
        if (allDone()) {
            active = false;
            completedAt = System.currentTimeMillis();
            _dirty = true;
        }
    }

    public boolean allDone() {
        return !stages.isEmpty() && stages.stream().allMatch(stage -> stage != null && stage.isDone());
    }

    public int completedCount() {
        return (int) stages.stream().filter(stage -> stage != null && stage.isDone()).count();
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getCompletedAt() {
        return completedAt;
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
}
