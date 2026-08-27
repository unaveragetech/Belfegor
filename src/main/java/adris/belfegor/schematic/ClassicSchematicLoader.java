package adris.belfegor.schematic;

import adris.belfegor.Debug;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal reader for the classic MCEdit/Schematica ".schematic" format.
 *
 * Classic schematics predate the 1.13 flattening: every cell is stored as a
 * legacy numeric block id (plus an optional "AddBlocks" high nibble and a
 * "Data" metadata byte), and the file usually carries a "SchematicaMapping"
 * table that maps legacy block names to those numeric ids.
 *
 * This loader converts the mapping names into modern 1.21.4 blocks (applying
 * the well-known renames and metadata variants), substitutes grass blocks with
 * dirt so survival gathering can source them in bulk, and produces Belfegor's
 * stable world-position blueprint model used by the staging/build pipeline.
 */
public class ClassicSchematicLoader {

    private static final Map<String, String> LEGACY_RENAMES = new HashMap<>();

    static {
        LEGACY_RENAMES.put("minecraft:grass", "minecraft:dirt"); // grass blocks drop dirt in survival; source in bulk
        LEGACY_RENAMES.put("minecraft:tallgrass", "minecraft:short_grass");
        LEGACY_RENAMES.put("minecraft:lit_furnace", "minecraft:furnace");
        LEGACY_RENAMES.put("minecraft:lit_redstone_ore", "minecraft:redstone_ore");
        LEGACY_RENAMES.put("minecraft:waterlily", "minecraft:lily_pad");
        LEGACY_RENAMES.put("minecraft:reeds", "minecraft:sugar_cane");
        LEGACY_RENAMES.put("minecraft:melon_block", "minecraft:melon");
        LEGACY_RENAMES.put("minecraft:nether_brick", "minecraft:nether_bricks");
        LEGACY_RENAMES.put("minecraft:end_bricks", "minecraft:end_stone_bricks");
        LEGACY_RENAMES.put("minecraft:hardened_clay", "minecraft:terracotta");
        LEGACY_RENAMES.put("minecraft:stained_hardened_clay", "minecraft:white_terracotta");
        LEGACY_RENAMES.put("minecraft:deadbush", "minecraft:dead_bush");
        LEGACY_RENAMES.put("minecraft:snow_layer", "minecraft:snow");
        LEGACY_RENAMES.put("minecraft:golden_rail", "minecraft:powered_rail");
        LEGACY_RENAMES.put("minecraft:flowing_water", "minecraft:water");
        LEGACY_RENAMES.put("minecraft:flowing_lava", "minecraft:lava");
        // Bases that get a metadata-variant table below.
        LEGACY_RENAMES.put("minecraft:stone", "minecraft:stone");
        LEGACY_RENAMES.put("minecraft:dirt", "minecraft:dirt");
        LEGACY_RENAMES.put("minecraft:planks", "minecraft:oak_planks");
        LEGACY_RENAMES.put("minecraft:log", "minecraft:oak_log");
        LEGACY_RENAMES.put("minecraft:log2", "minecraft:acacia_log");
        LEGACY_RENAMES.put("minecraft:leaves", "minecraft:oak_leaves");
        LEGACY_RENAMES.put("minecraft:leaves2", "minecraft:acacia_leaves");
        LEGACY_RENAMES.put("minecraft:sapling", "minecraft:oak_sapling");
        LEGACY_RENAMES.put("minecraft:wool", "minecraft:white_wool");
        LEGACY_RENAMES.put("minecraft:carpet", "minecraft:white_carpet");
        LEGACY_RENAMES.put("minecraft:stained_glass", "minecraft:white_stained_glass");
        LEGACY_RENAMES.put("minecraft:stained_glass_pane", "minecraft:white_stained_glass_pane");
        LEGACY_RENAMES.put("minecraft:concrete", "minecraft:white_concrete");
        LEGACY_RENAMES.put("minecraft:concrete_powder", "minecraft:white_concrete_powder");
        LEGACY_RENAMES.put("minecraft:stonebrick", "minecraft:stone_bricks");
        LEGACY_RENAMES.put("minecraft:red_flower", "minecraft:poppy");
        LEGACY_RENAMES.put("minecraft:yellow_flower", "minecraft:dandelion");
        LEGACY_RENAMES.put("minecraft:bed", "minecraft:white_bed");
        LEGACY_RENAMES.put("minecraft:quartz_block", "minecraft:quartz_block");
        LEGACY_RENAMES.put("minecraft:prismarine", "minecraft:prismarine");
        LEGACY_RENAMES.put("minecraft:sponge", "minecraft:sponge");
        LEGACY_RENAMES.put("minecraft:stone_slab", "minecraft:stone_slab");
    }

    private static final String[] DYE_ORDER = new String[]{
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    private static final Map<String, String[]> METADATA_VARIANTS = new HashMap<>();

    static {
        METADATA_VARIANTS.put("minecraft:stone", new String[]{
                "minecraft:stone", "minecraft:granite", "minecraft:polished_granite",
                "minecraft:diorite", "minecraft:polished_diorite",
                "minecraft:andesite", "minecraft:polished_andesite"});
        METADATA_VARIANTS.put("minecraft:dirt", new String[]{
                "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:podzol"});
        METADATA_VARIANTS.put("minecraft:oak_planks", new String[]{
                "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
                "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks"});
        METADATA_VARIANTS.put("minecraft:oak_log", new String[]{
                "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
                "minecraft:jungle_log"});
        METADATA_VARIANTS.put("minecraft:acacia_log", new String[]{
                "minecraft:acacia_log", "minecraft:dark_oak_log"});
        METADATA_VARIANTS.put("minecraft:oak_leaves", new String[]{
                "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
                "minecraft:jungle_leaves"});
        METADATA_VARIANTS.put("minecraft:acacia_leaves", new String[]{
                "minecraft:acacia_leaves", "minecraft:dark_oak_leaves"});
        METADATA_VARIANTS.put("minecraft:oak_sapling", new String[]{
                "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
                "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling"});
        METADATA_VARIANTS.put("minecraft:white_wool", dyeIds("wool"));
        METADATA_VARIANTS.put("minecraft:white_carpet", dyeIds("carpet"));
        METADATA_VARIANTS.put("minecraft:white_stained_glass", dyeIds("stained_glass"));
        METADATA_VARIANTS.put("minecraft:white_stained_glass_pane", dyeIds("stained_glass_pane"));
        METADATA_VARIANTS.put("minecraft:white_concrete", dyeIds("concrete"));
        METADATA_VARIANTS.put("minecraft:white_concrete_powder", dyeIds("concrete_powder"));
        METADATA_VARIANTS.put("minecraft:white_terracotta", dyeIds("terracotta"));
        METADATA_VARIANTS.put("minecraft:white_bed", dyeIds("bed"));
        METADATA_VARIANTS.put("minecraft:sandstone", new String[]{
                "minecraft:sandstone", "minecraft:chiseled_sandstone", "minecraft:cut_sandstone"});
        METADATA_VARIANTS.put("minecraft:stone_bricks", new String[]{
                "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
                "minecraft:cracked_stone_bricks", "minecraft:chiseled_stone_bricks"});
        METADATA_VARIANTS.put("minecraft:quartz_block", new String[]{
                "minecraft:quartz_block", "minecraft:chiseled_quartz_block",
                "minecraft:quartz_pillar"});
        METADATA_VARIANTS.put("minecraft:prismarine", new String[]{
                "minecraft:prismarine", "minecraft:prismarine_bricks",
                "minecraft:dark_prismarine"});
        METADATA_VARIANTS.put("minecraft:sponge", new String[]{
                "minecraft:sponge", "minecraft:wet_sponge"});
        METADATA_VARIANTS.put("minecraft:poppy", new String[]{
                "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium",
                "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
                "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy"});
        METADATA_VARIANTS.put("minecraft:stone_slab", new String[]{
                "minecraft:stone_slab", "minecraft:sandstone_slab", "minecraft:oak_slab",
                "minecraft:cobblestone_slab", "minecraft:brick_slab",
                "minecraft:stone_brick_slab", "minecraft:nether_brick_slab",
                "minecraft:quartz_slab"});
    }

    private static String[] dyeIds(String suffix) {
        String[] result = new String[16];
        for (int i = 0; i < 16; i++) {
            result[i] = "minecraft:" + DYE_ORDER[i] + "_" + suffix;
        }
        return result;
    }

    private ClassicSchematicLoader() {}

    /**
     * Loads a classic .schematic into Belfegor's world-position blueprint at
     * the given origin. Cells are indexed y-major, then z, then x:
     * index = (y * length + z) * width + x.
     */
    public static BelfegorSchematic load(File file, BlockPos worldOrigin, String dimension) throws Exception {
        NbtCompound root = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
        int width = Math.max(0, root.getInt("Width"));
        int height = Math.max(0, root.getInt("Height"));
        int length = Math.max(0, root.getInt("Length"));
        byte[] blocks = root.getByteArray("Blocks");
        byte[] data = root.getByteArray("Data");
        boolean hasAdd = root.contains("AddBlocks");
        byte[] addBlocks = hasAdd ? root.getByteArray("AddBlocks") : null;

        NbtCompound mapping = root.getCompound("SchematicaMapping");
        Map<Integer, String> idToName = new HashMap<>();
        for (String key : mapping.getKeys()) {
            idToName.put(mapping.getInt(key), key);
        }

        BelfegorSchematic schematic = new BelfegorSchematic();
        schematic.name = stripExtension(file.getName());
        schematic.dimension = dimension == null ? "" : dimension;
        schematic.originX = worldOrigin.getX();
        schematic.originY = worldOrigin.getY();
        schematic.originZ = worldOrigin.getZ();

        int volume = width * height * length;
        int nonAir = 0;
        int unresolved = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int index = (y * length + z) * width + x;
                    if (index < 0 || index >= blocks.length) continue;
                    int id = blocks[index] & 0xFF;
                    if (hasAdd && addBlocks != null && id != 0) {
                        int addIndex = index / 2;
                        if (addIndex < addBlocks.length) {
                            int nibble = (index & 1) == 0
                                    ? (addBlocks[addIndex] & 0xF0) >>> 4
                                    : addBlocks[addIndex] & 0x0F;
                            id |= nibble << 8;
                        }
                    }
                    if (id == 0) continue; // air
                    String legacyName = idToName.get(id);
                    if (legacyName == null) {
                        unresolved++;
                        continue;
                    }
                    String modern = resolveModern(legacyName);
                    if (modern == null) {
                        unresolved++;
                        continue;
                    }
                    int meta = data != null && index < data.length ? data[index] & 0xFF : 0;
                    modern = applyMetadata(modern, meta);
                    Block block = Registries.BLOCK.get(Identifier.of(modern));
                    if (block == Blocks.AIR) {
                        unresolved++;
                        continue;
                    }
                    BlockPos pos = worldOrigin.add(x, y, z);
                    schematic.putExpected(pos, modern, "Block{" + modern + "}");
                    nonAir++;
                }
            }
        }
        Debug.logInternal("CLASSIC-SCHEMATIC loaded " + file.getAbsolutePath()
                + " name=" + schematic.name
                + " width=" + width + " height=" + height + " length=" + length
                + " volume=" + volume
                + " nonAir=" + nonAir
                + " unresolved=" + unresolved
                + " stored=" + schematic.totalBlocks());
        return schematic;
    }

    private static String resolveModern(String legacyName) {
        String name = legacyName;
        if (!name.contains(":")) name = "minecraft:" + name;
        String mapped = LEGACY_RENAMES.getOrDefault(name, name);
        if (Registries.BLOCK.containsId(Identifier.of(mapped))) {
            return mapped;
        }
        return null;
    }

    private static String applyMetadata(String base, int meta) {
        String[] variants = METADATA_VARIANTS.get(base);
        if (variants == null) return base;
        int index = meta;
        // Log/leaves metadata packs wood type in the low bits and decay/axis flags above.
        if (base.equals("minecraft:oak_log") || base.equals("minecraft:acacia_log")
                || base.equals("minecraft:oak_leaves") || base.equals("minecraft:acacia_leaves")) {
            index = meta & 3;
        }
        if (index >= variants.length) return base;
        return variants[index];
    }

    private static String stripExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot <= 0 ? (name == null ? "imported_schematic" : name) : name.substring(0, dot);
    }
}
