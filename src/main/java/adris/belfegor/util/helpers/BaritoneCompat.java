package adris.belfegor.util.helpers;

import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import baritone.api.behavior.IPathingBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility utilities replacing internal baritone classes
 * (MovementHelper, MineProcess, ToolSet, BlockStateInterface, CalculationContext).
 */
public class BaritoneCompat {

    public static boolean isLava(BlockState state) {
        return state.getFluidState().getFluid() == Fluids.LAVA;
    }

    public static boolean isWater(BlockState state) {
        return state.getFluidState().getFluid() == Fluids.WATER;
    }

    public static boolean canPlaceAgainst(IPlayerContext ctx, BlockPos pos) {
        World world = ctx.world();
        BlockState state = world.getBlockState(pos);
        return state.isSolidBlock(world, pos);
    }

    public static boolean avoidBreaking(int x, int y, int z, BlockState state) {
        if (state.getBlock() instanceof FallingBlock) {
            return true;
        }
        return false;
    }

    public static double calculateSpeedVsBlock(ItemStack stack, BlockState state) {
        return stack.getMiningSpeedMultiplier(state);
    }

    public static boolean plausibleToBreak(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getHardness(world, pos) >= 0;
    }

    public static boolean isSafeToCancel(IPathingBehavior behavior) {
        return !behavior.isPathing();
    }

    public static void requestPause(IPathingBehavior behavior) {
        behavior.cancelEverything();
    }

    public static List<BlockPos> searchWorld(ClientWorld world, BlockOptionalMetaLookup boml, int maxCount) {
        List<BlockPos> results = new ArrayList<>();
        if (MinecraftClient.getInstance().player == null) return results;

        int playerX = (int) MinecraftClient.getInstance().player.getX();
        int playerZ = (int) MinecraftClient.getInstance().player.getZ();

        int range = 128;
        int minY = world.getBottomY();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int radius = 0; radius <= range; radius += 16) {
            int r = radius;
            for (int x = playerX - r; x <= playerX + r; x++) {
                for (int z = playerZ - r; z <= playerZ + r; z++) {
                    if (Math.abs(x - playerX) < r - 16 && Math.abs(z - playerZ) < r - 16) continue;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    int maxY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                    for (int y = minY; y <= maxY; y++) {
                        mutable.set(x, y, z);
                        if (boml.has(world.getBlockState(mutable))) {
                            results.add(mutable.toImmutable());
                            if (results.size() >= maxCount) return results;
                        }
                    }
                }
            }
        }
        return results;
    }
}
