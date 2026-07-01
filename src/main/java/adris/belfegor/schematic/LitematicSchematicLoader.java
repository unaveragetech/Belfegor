package adris.belfegor.schematic;

import adris.belfegor.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal Litematica v7 reader for autonomous validation/build planning.
 *
 * Litematica stores each region as:
 * - Position: relative region offset from schematic origin
 * - Size: signed region dimensions
 * - BlockStatePalette: list of block state compounds
 * - BlockStates: packed palette indices in a long array
 *
 * This loader converts those regions into Belfegor's stable world-position
 * blueprint model. It intentionally does not depend on Litematica's renderer or
 * UI; those can be optional interop later.
 */
public class LitematicSchematicLoader {

    private static final int TAG_COMPOUND = 10;

    public static File defaultCampFile() {
        File gameDir = MinecraftClient.getInstance() == null
                ? new File(".")
                : MinecraftClient.getInstance().runDirectory;
        return new File(new File(new File(gameDir, "schematics"), "test"), "camp.litematic");
    }

    public static BelfegorSchematic load(File file, BlockPos worldOrigin, String dimension) throws Exception {
        NbtCompound root = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
        NbtCompound metadata = root.getCompound("Metadata");
        String name = metadata.getString("Name");
        if (name == null || name.isBlank()) {
            name = stripExtension(file.getName());
        }

        BelfegorSchematic schematic = new BelfegorSchematic();
        schematic.name = name;
        schematic.dimension = dimension == null ? "" : dimension;
        schematic.originX = worldOrigin.getX();
        schematic.originY = worldOrigin.getY();
        schematic.originZ = worldOrigin.getZ();

        NbtCompound regions = root.getCompound("Regions");
        List<String> regionNames = new ArrayList<>(regions.getKeys());
        regionNames.sort(Comparator.naturalOrder());
        int paletteBlocks = 0;
        int nonAirBlocks = 0;
        for (String regionName : regionNames) {
            NbtCompound region = regions.getCompound(regionName);
            RegionStats stats = importRegion(schematic, region, worldOrigin);
            paletteBlocks += stats.volume;
            nonAirBlocks += stats.nonAirBlocks;
            Debug.logInternal("LITEMATIC region " + regionName
                    + " volume=" + stats.volume
                    + " nonAir=" + stats.nonAirBlocks
                    + " palette=" + stats.paletteSize
                    + " bits=" + stats.bitsPerEntry);
        }
        Debug.logInternal("LITEMATIC loaded " + file.getAbsolutePath()
                + " name=" + schematic.name
                + " regions=" + regionNames.size()
                + " volume=" + paletteBlocks
                + " nonAir=" + nonAirBlocks
                + " stored=" + schematic.totalBlocks());
        return schematic;
    }

    public static BelfegorSchematic loadDefaultCamp(BlockPos worldOrigin, String dimension) throws Exception {
        return load(defaultCampFile(), worldOrigin, dimension);
    }

    private static RegionStats importRegion(BelfegorSchematic schematic, NbtCompound region,
                                            BlockPos worldOrigin) {
        NbtCompound pos = region.getCompound("Position");
        NbtCompound size = region.getCompound("Size");
        int regionX = pos.getInt("x");
        int regionY = pos.getInt("y");
        int regionZ = pos.getInt("z");
        int rawSizeX = size.getInt("x");
        int rawSizeY = size.getInt("y");
        int rawSizeZ = size.getInt("z");
        int sizeX = Math.abs(rawSizeX);
        int sizeY = Math.abs(rawSizeY);
        int sizeZ = Math.abs(rawSizeZ);
        int volume = sizeX * sizeY * sizeZ;

        NbtList paletteNbt = region.getList("BlockStatePalette", TAG_COMPOUND);
        List<PaletteEntry> palette = new ArrayList<>();
        for (int i = 0; i < paletteNbt.size(); i++) {
            palette.add(PaletteEntry.fromNbt(paletteNbt.getCompound(i)));
        }
        int bits = Math.max(2, ceilLog2(Math.max(1, palette.size())));
        long[] packed = region.getLongArray("BlockStates");
        int nonAir = 0;

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int linearIndex = (y * sizeZ + z) * sizeX + x;
                    int paletteIndex = unpack(packed, linearIndex, bits);
                    if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
                    PaletteEntry entry = palette.get(paletteIndex);
                    if (entry.isAir()) continue;

                    int localX = rawSizeX < 0 ? -x : x;
                    int localY = rawSizeY < 0 ? -y : y;
                    int localZ = rawSizeZ < 0 ? -z : z;
                    BlockPos worldPos = worldOrigin.add(regionX + localX, regionY + localY, regionZ + localZ);
                    schematic.putExpected(worldPos, entry.blockId, entry.stateString);
                    nonAir++;
                }
            }
        }
        return new RegionStats(volume, nonAir, palette.size(), bits);
    }

    private static int unpack(long[] data, int index, int bits) {
        if (data == null || data.length == 0) return 0;
        long mask = (1L << bits) - 1L;
        int bitIndex = index * bits;
        int longIndex = bitIndex >> 6;
        int bitOffset = bitIndex & 63;
        if (longIndex >= data.length) return 0;
        long value = data[longIndex] >>> bitOffset;
        int spill = bitOffset + bits - 64;
        if (spill > 0 && longIndex + 1 < data.length) {
            value |= data[longIndex + 1] << (bits - spill);
        }
        return (int) (value & mask);
    }

    private static int ceilLog2(int value) {
        int bits = 0;
        int check = 1;
        while (check < value) {
            check <<= 1;
            bits++;
        }
        return bits;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private record RegionStats(int volume, int nonAirBlocks, int paletteSize, int bitsPerEntry) {}

    private static class PaletteEntry {
        final String blockId;
        final String stateString;

        private PaletteEntry(String blockId, String stateString) {
            this.blockId = blockId == null || blockId.isBlank() ? "minecraft:air" : blockId;
            this.stateString = stateString == null || stateString.isBlank() ? this.blockId : stateString;
        }

        static PaletteEntry fromNbt(NbtCompound compound) {
            String name = compound.getString("Name");
            NbtCompound properties = compound.getCompound("Properties");
            return new PaletteEntry(name, formatState(name, properties));
        }

        boolean isAir() {
            return blockId.equals("minecraft:air")
                    || Registries.BLOCK.get(Identifier.of(blockId)).getTranslationKey().equals("block.minecraft.air");
        }

        private static String formatState(String blockId, NbtCompound properties) {
            StringBuilder builder = new StringBuilder("Block{").append(blockId).append("}");
            if (properties != null && !properties.getKeys().isEmpty()) {
                List<String> keys = new ArrayList<>(properties.getKeys());
                keys.sort(Comparator.naturalOrder());
                builder.append("[");
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) builder.append(",");
                    String key = keys.get(i);
                    NbtElement value = properties.get(key);
                    builder.append(key).append("=").append(value == null ? "" : value.asString());
                }
                builder.append("]");
            }
            return builder.toString();
        }
    }
}
