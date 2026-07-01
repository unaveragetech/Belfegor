package adris.belfegor.chains;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.DoToClosestBlockTask;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.construction.PutOutFireTask;
import adris.belfegor.tasks.movement.EnterNetherPortalTask;
import adris.belfegor.tasks.movement.EscapeFromLavaTask;
import adris.belfegor.tasks.movement.EscapeFromWaterTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.SafeRandomShimmyTask;
import adris.belfegor.tasksystem.TaskRunner;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.LookHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Optional;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame _wasInLavaTimer = new TimerGame(1);
    private boolean _wasAvoidingDrowning;
    private TimerGame _portalStuckTimer = new TimerGame(5);

    private BlockPos _extinguishWaterPosition;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(Belfegor mod) {

    }

    @Override
    public float getPriority(Belfegor mod) {
        if (!Belfegor.inGame()) return Float.NEGATIVE_INFINITY;

        // Drowning
        handleDrowning(mod);

        // Ocean/water escape. Swimming up alone is not enough if the bot
        // starts in deep water or an ocean edge; pillar out when blocks exist.
        if (shouldPillarOutOfWater(mod)) {
            if (!(_mainTask instanceof EscapeFromWaterTask)) {
                setTask(new EscapeFromWaterTask());
            }
            return 95;
        }

        // Lava Escape
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            setTask(new EscapeFromLavaTask());
            return 100;
        }

        // Fire escape
        if (isInFire(mod)) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Extinguish with water
        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (mod.getPlayer() == null) return Float.NEGATIVE_INFINITY;
            if (!(_mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer() != null && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && mod.getWorld() != null && !mod.getWorld().getDimension().ultrawarm()) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolid(mod, targetWaterPos.down()) && WorldHelper.canPlace(mod, targetWaterPos)) {
                        Optional<Rotation> reach = LookHelper.getReach(targetWaterPos.down(), Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(targetWaterPos.down())) {
                                if (mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                                    _extinguishWaterPosition = targetWaterPos;
                                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                    setTask(null);
                                    return 90;
                                }
                            }
                        }
                    }
                }
                setTask(new DoToClosestBlockTask(GetToBlockTask::new, Blocks.WATER));
                return 90;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockTracker().blockIsValid(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                setTask(new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), Direction.UP, _extinguishWaterPosition.down(), true));
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal(mod)) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            _portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (_portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        return Float.NEGATIVE_INFINITY;
    }

    private void handleDrowning(Belfegor mod) {
        if (mod.getPlayer() == null) return;
        // Swim
        boolean avoidedDrowning = false;
        if (mod.getModSettings().shouldAvoidDrowning()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    _wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (_wasAvoidingDrowning && !avoidedDrowning) {
            _wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    private boolean shouldPillarOutOfWater(Belfegor mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        if (mod.getPlayer().isInLava()) return false;
        if (!(mod.getPlayer().isTouchingWater()
                || mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getBlock() == Blocks.WATER
                || mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().down()).getBlock() == Blocks.WATER)) {
            return false;
        }
        if (mod.getPlayer().isOnGround()
                && !mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getFluidState()
                .isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
            return false;
        }
        return mod.getItemStorage().hasItem(Items.COBBLESTONE,
                Items.COBBLED_DEEPSLATE,
                Items.DIRT,
                Items.NETHERRACK,
                Items.STONE,
                Items.OAK_PLANKS,
                Items.SPRUCE_PLANKS,
                Items.BIRCH_PLANKS);
    }

    private boolean isInLavaOhShit(Belfegor mod) {
        if (mod.getPlayer() == null) return false;
        if (mod.getPlayer().isInLava() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            _wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !_wasInLavaTimer.elapsed();
    }

    private boolean isInFire(Belfegor mod) {
        if (mod.getPlayer() == null) return false;
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer(mod)) {
                if (mod.getWorld() == null) return false;
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof AbstractFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal(Belfegor mod) {
        var currentTask = mod.getUserTaskChain().getCurrentTask();
        return WorldHelper.isInNetherPortal(mod) && currentTask != null && !currentTask.thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop(Belfegor mod) {
        super.onStop(mod);
    }
}
