package adris.belfegor.util;

import adris.belfegor.Debug;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public enum MiningRequirement implements Comparable<MiningRequirement> {
    HAND(Items.AIR.getDefaultStack()), WOOD(Items.WOODEN_PICKAXE.getDefaultStack()), STONE(Items.STONE_PICKAXE.getDefaultStack()), IRON(Items.IRON_PICKAXE.getDefaultStack()), DIAMOND(Items.DIAMOND_PICKAXE.getDefaultStack()), NETHERITE(Items.NETHERITE_PICKAXE.getDefaultStack());

    private final ItemStack _minPickaxe;

    MiningRequirement(ItemStack minPickaxe) {
        _minPickaxe = minPickaxe;
    }

    public static MiningRequirement getMinimumRequirementForBlock(Block block) {
        if (block.getDefaultState().isToolRequired()) {
            for (MiningRequirement req : MiningRequirement.values()) {
                if (req == MiningRequirement.HAND) continue;
                ItemStack pick = req.getMinimumPickaxe();
                if (pick.isSuitableFor(block.getDefaultState())) {
                    return req;
                }
            }
            // No pickaxe is suitable for this block (logs, planks, dirt, etc.).
            // It is still breakable by hand; axe/shovel speed bonuses are
            // enforced by their own requirement tasks, not a pickaxe tier.
            return MiningRequirement.HAND;
        }
        return MiningRequirement.HAND;
    }

    public ItemStack getMinimumPickaxe() {
        return _minPickaxe;
    }

}
