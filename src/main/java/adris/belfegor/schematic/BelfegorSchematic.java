package adris.belfegor.schematic;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent schematic blueprint used by Belfegor validation.
 *
 * This is deliberately small and deterministic: it captures the expected block
 * palette for a base module without depending on Litematica's client UI. The
 * next integration layer can import/export .litematic into this same model,
 * while validation and repair keep using one source of truth.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class BelfegorSchematic {

    public int version = 1;
    public String name = "";
    public String dimension = "";
    public int originX;
    public int originY;
    public int originZ;
    public int minX;
    public int minY;
    public int minZ;
    public int maxX;
    public int maxY;
    public int maxZ;
    public Map<String, String> blocks = new LinkedHashMap<>();
    public Map<String, String> blockStates = new LinkedHashMap<>();

    public BelfegorSchematic() {}

    public static BelfegorSchematic fromBlocks(String name, String dimension,
                                               BlockPos origin,
                                               Map<BlockPos, Block> targets) {
        BelfegorSchematic schematic = new BelfegorSchematic();
        schematic.name = name == null ? "unnamed" : name;
        schematic.dimension = dimension == null ? "" : dimension;
        schematic.originX = origin.getX();
        schematic.originY = origin.getY();
        schematic.originZ = origin.getZ();

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, Block> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            schematic.blocks.put(key(pos), Registries.BLOCK.getId(entry.getValue()).toString());
            schematic.blockStates.put(key(pos), Registries.BLOCK.getId(entry.getValue()).toString());
        }
        schematic.minX = minX == Integer.MAX_VALUE ? origin.getX() : minX;
        schematic.minY = minY == Integer.MAX_VALUE ? origin.getY() : minY;
        schematic.minZ = minZ == Integer.MAX_VALUE ? origin.getZ() : minZ;
        schematic.maxX = maxX == Integer.MIN_VALUE ? origin.getX() : maxX;
        schematic.maxY = maxY == Integer.MIN_VALUE ? origin.getY() : maxY;
        schematic.maxZ = maxZ == Integer.MIN_VALUE ? origin.getZ() : maxZ;
        return schematic;
    }

    public int countMismatches(Belfegor mod) {
        if (mod == null || mod.getWorld() == null) return 0;
        int mismatches = 0;
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            Optional<BlockPos> pos = parseKey(entry.getKey());
            if (pos.isEmpty()) {
                mismatches++;
                continue;
            }
            String expectedState = blockStates == null ? "" : blockStates.getOrDefault(entry.getKey(), "");
            String actualBlock = Registries.BLOCK.getId(mod.getWorld().getBlockState(pos.get()).getBlock()).toString();
            String actualState = mod.getWorld().getBlockState(pos.get()).toString();
            if (!expectedState.isBlank() && expectedState.contains("[") && !stateMatches(actualState, expectedState)) {
                mismatches++;
            } else if (!actualBlock.equals(entry.getValue())) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private static boolean stateMatches(String actualState, String expectedState) {
        if (actualState == null || expectedState == null) return false;
        int expectedPropsStart = expectedState.indexOf('[');
        if (expectedPropsStart < 0) return actualState.equals(expectedState);
        String expectedBlock = expectedState.substring(0, expectedPropsStart);
        if (!actualState.startsWith(expectedBlock)) return false;
        int expectedPropsEnd = expectedState.lastIndexOf(']');
        if (expectedPropsEnd <= expectedPropsStart) return true;
        String[] props = expectedState.substring(expectedPropsStart + 1, expectedPropsEnd).split(",");
        for (String prop : props) {
            String trimmed = prop.trim();
            if (!trimmed.isEmpty() && !actualState.contains(trimmed)) return false;
        }
        return true;
    }

    public int totalBlocks() {
        return blocks == null ? 0 : blocks.size();
    }

    public void putExpected(BlockPos pos, String blockId, String blockState) {
        if (blocks == null) blocks = new LinkedHashMap<>();
        if (blockStates == null) blockStates = new LinkedHashMap<>();
        String key = key(pos);
        blocks.put(key, blockId);
        blockStates.put(key, blockState == null || blockState.isBlank() ? blockId : blockState);
        if (blocks.size() == 1) {
            minX = maxX = pos.getX();
            minY = maxY = pos.getY();
            minZ = maxZ = pos.getZ();
        } else {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
    }

    public Map<BlockPos, Block[]> toBuildTargets() {
        LinkedHashMap<BlockPos, Block[]> targets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            Optional<BlockPos> pos = parseKey(entry.getKey());
            if (pos.isEmpty()) continue;
            Block block = Registries.BLOCK.get(Identifier.of(entry.getValue()));
            if (block == Blocks.AIR) continue;
            targets.put(pos.get(), new Block[]{block});
        }
        return targets;
    }

    public Map<net.minecraft.item.Item, Integer> requiredItems() {
        LinkedHashMap<net.minecraft.item.Item, Integer> result = new LinkedHashMap<>();
        if (blocks == null) return result;
        for (String blockId : blocks.values()) {
            Block block = Registries.BLOCK.get(Identifier.of(blockId));
            if (block == Blocks.AIR) continue;
            net.minecraft.item.Item item = block.asItem();
            if (item == net.minecraft.item.Items.AIR) continue;
            result.put(item, result.getOrDefault(item, 0) + 1);
        }
        return result;
    }

    public BelfegorSchematic translatedTo(BlockPos newOrigin) {
        BelfegorSchematic translated = new BelfegorSchematic();
        translated.version = version;
        translated.name = name;
        translated.dimension = dimension;
        translated.originX = newOrigin.getX();
        translated.originY = newOrigin.getY();
        translated.originZ = newOrigin.getZ();

        BlockPos oldOrigin = new BlockPos(originX, originY, originZ);
        if (blocks != null) {
            for (Map.Entry<String, String> entry : blocks.entrySet()) {
                Optional<BlockPos> oldPos = parseKey(entry.getKey());
                if (oldPos.isEmpty()) continue;
                BlockPos offset = oldPos.get().subtract(oldOrigin);
                BlockPos newPos = newOrigin.add(offset);
                String state = blockStates == null ? entry.getValue() : blockStates.getOrDefault(entry.getKey(), entry.getValue());
                translated.putExpected(newPos, entry.getValue(), state);
            }
        }
        return translated;
    }

    public void save(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, this);
            Debug.logInternal("SCHEMATIC saved " + file.getAbsolutePath() + " blocks=" + blocks.size());
        } catch (Exception e) {
            Debug.logWarning("Failed to save Belfegor schematic " + file + ": " + e.getMessage());
        }
    }

    public static Optional<BelfegorSchematic> load(File file) {
        if (file == null || !file.exists()) return Optional.empty();
        try {
            ObjectMapper mapper = new ObjectMapper();
            return Optional.of(mapper.readValue(file, BelfegorSchematic.class));
        } catch (Exception e) {
            Debug.logWarning("Failed to load Belfegor schematic " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public static File baseCoreFile(String dimension, BlockPos home) {
        String safeDim = (dimension == null || dimension.isBlank() ? "unknown" : dimension)
                .replaceAll("[^a-zA-Z0-9_.-]", "_");
        String name = "base_core_" + safeDim + "_" + home.getX() + "_" + home.getY() + "_" + home.getZ()
                + ".belfegor_schematic.json";
        return new File(schematicDir(), name);
    }

    public static File schematicDir() {
        File gameDir = MinecraftClient.getInstance() == null
                ? new File(".")
                : MinecraftClient.getInstance().runDirectory;
        return new File(new File(gameDir, "belfegor"), "schematics");
    }

    public static File importedDir() {
        return new File(schematicDir(), "imported");
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> parseKey(String key) {
        if (key == null) return Optional.empty();
        String[] parts = key.split(",");
        if (parts.length != 3) return Optional.empty();
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
