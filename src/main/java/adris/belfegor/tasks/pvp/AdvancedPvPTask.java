package adris.belfegor.tasks.pvp;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.GetToEntityTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Advanced PvP task with full combat preparation, fallback mechanics,
 * healing management, and smart task prioritization.
 *
 * Phases:
 * 1. GEAR  - Collect diamond/netherite armor, best sword, shield, food, golden apples, blocks
 * 2. SEEK  - Find nearest hostile player
 * 3. ENGAGE - Approach and attack target
 * 4. FALLBACK - Retreat on low health, eat golden apples/food, heal up
 * 5. RESUME - Return to fight after healing
 */
public class AdvancedPvPTask extends Task {

    private static final double ENGAGE_RANGE = 3.5;
    private static final double RETREAT_RANGE = 40;
    private static final float FALLBACK_HEALTH = 12f;
    private static final float CRITICAL_HEALTH = 6f;
    private static final int MIN_BLOCKS = 32;
    private static final int PREFERRED_BLOCKS = 64;
    private static final int FOOD_TARGET = 32;

    private final String _targetName;
    private final boolean _getGeared;

    private Phase _phase = Phase.GEAR;
    private Task _currentSubTask;
    private TimerGame _healTimer = new TimerGame(5);
    private TimerGame _engageTimer = new TimerGame(2);
    private Vec3d _lastTargetPos;
    private Vec3d _fallbackPos;
    private int _fallbackCount = 0;

    private enum Phase {
        GEAR,
        SEEK,
        ENGAGE,
        FALLBACK,
        HEAL,
        RESUME
    }

    public AdvancedPvPTask(String targetName) {
        this(targetName, true);
    }

    public AdvancedPvPTask(String targetName, boolean getGeared) {
        _targetName = targetName;
        _getGeared = getGeared;
    }

    @Override
    protected void onStart(Belfegor mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().setForceFieldPlayers(true);
        _phase = _getGeared ? Phase.GEAR : Phase.SEEK;
        _fallbackCount = 0;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (mod.getPlayer() == null) return null;

        float health = mod.getPlayer().getHealth();
        float absorption = mod.getPlayer().getAbsorptionAmount();

        // EMERGENCY: If critically low, always fall back regardless of phase
        if (health + absorption < CRITICAL_HEALTH && _phase != Phase.FALLBACK && _phase != Phase.HEAL) {
            _phase = Phase.FALLBACK;
            _fallbackPos = findSafeFallbackPosition(mod);
            _fallbackCount++;
        }

        // Check if target is still alive/online
        Optional<PlayerEntity> target = findTarget(mod);

        return switch (_phase) {
            case GEAR -> doGearPhase(mod);
            case SEEK -> doSeekPhase(mod, target);
            case ENGAGE -> doEngagePhase(mod, target, health, absorption);
            case FALLBACK -> doFallbackPhase(mod, health, absorption);
            case HEAL -> doHealPhase(mod, health, absorption);
            case RESUME -> doResumePhase(mod, target);
        };
    }

    private Task doGearPhase(Belfegor mod) {
        setDebugState("Gearing up for PvP");

        // Priority 1: Sword
        if (!hasBestSword(mod)) {
            setDebugState("Getting sword");
            return TaskCatalogue.getItemTask(getBestSword(), 1);
        }

        // Priority 2: Armor
        if (!isFullyArmored(mod)) {
            setDebugState("Getting armor");
            return getArmorTask(mod);
        }

        // Priority 3: Shield
        if (!mod.getItemStorage().hasItem(Items.SHIELD)) {
            setDebugState("Getting shield");
            return TaskCatalogue.getItemTask(Items.SHIELD, 1);
        }

        // Priority 4: Golden apples for healing
        int gapples = mod.getItemStorage().getItemCount(Items.GOLDEN_APPLE);
        int egapples = mod.getItemStorage().getItemCount(Items.ENCHANTED_GOLDEN_APPLE);
        if (gapples + egapples < 3) {
            setDebugState("Getting golden apples");
            return TaskCatalogue.getItemTask(Items.GOLDEN_APPLE, 4);
        }

        // Priority 5: Food
        if (StorageHelper.calculateInventoryFoodScore(mod) <= 0) {
            setDebugState("Collecting food");
            return TaskCatalogue.getItemTask(Items.COOKED_BEEF, FOOD_TARGET);
        }

        // Priority 6: Building blocks
        int blocks = getBuildingBlockCount(mod);
        if (blocks < MIN_BLOCKS) {
            setDebugState("Collecting building blocks");
            return TaskCatalogue.getItemTask(Items.OAK_PLANKS, PREFERRED_BLOCKS);
        }

        // Priority 7: Ender pearls for gap closing
        if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) < 2) {
            setDebugState("Getting ender pearls");
            return TaskCatalogue.getItemTask(Items.ENDER_PEARL, 4);
        }

        // Priority 8: Potion of healing if possible
        if (mod.getItemStorage().getItemCount(Items.EXPERIENCE_BOTTLE) < 5) {
            setDebugState("Getting experience bottles");
            return TaskCatalogue.getItemTask(Items.EXPERIENCE_BOTTLE, 9);
        }

        // All geared up, switch to seek
        Debug.logMessage("[AdvancedPvP] Fully geared, seeking target: " + _targetName);
        _phase = Phase.SEEK;
        return null;
    }

    private Task doSeekPhase(Belfegor mod, Optional<PlayerEntity> target) {
        if (target.isPresent()) {
            _lastTargetPos = target.get().getPos();
            _phase = Phase.ENGAGE;
            setDebugState("Target found: " + _targetName);
            return null;
        }

        // No target found, wander and search
        setDebugState("Searching for " + _targetName);
        if (_lastTargetPos != null) {
            return new GetToBlockTask(new BlockPos((int) _lastTargetPos.x, (int) _lastTargetPos.y, (int) _lastTargetPos.z));
        }

        // Just wander around looking
        return new adris.belfegor.tasks.movement.TimeoutWanderTask(60);
    }

    private Task doEngagePhase(Belfegor mod, Optional<PlayerEntity> target, float health, float absorption) {
        if (target.isEmpty()) {
            _phase = Phase.SEEK;
            setDebugState("Lost target, searching");
            return null;
        }

        PlayerEntity player = target.get();
        _lastTargetPos = player.getPos();
        double distance = mod.getPlayer().getPos().distanceTo(player.getPos());

        // FALLBACK TRIGGER: Low health
        if (health + absorption < FALLBACK_HEALTH) {
            _phase = Phase.FALLBACK;
            _fallbackPos = findSafeFallbackPosition(mod);
            _fallbackCount++;
            setDebugState("Low health! Falling back to heal");
            return null;
        }

        // Check if we have golden apples and should use one proactively
        if (health < 16 && (mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE))) {
            setDebugState("Eating golden apple before fight");
            return eatGoldenApple(mod);
        }

        // Engage: approach and attack
        setDebugState("Engaging " + _targetName + " (dist=" + String.format("%.1f", distance) + ")");

        if (distance > ENGAGE_RANGE + 1) {
            // Too far, move closer
            return new GetToEntityTask(player, ENGAGE_RANGE);
        }

        // Close enough to attack - just return null and let KillAura handle it
        // The mob defense chain / kill aura will automatically attack nearby hostile players
        return null;
    }

    private Task doFallbackPhase(Belfegor mod, float health, float absorption) {
        setDebugState("Falling back to safe position (#" + _fallbackCount + ")");

        // First, eat a golden apple if we have one
        if (health < 16 && mod.getItemStorage().hasItem(Items.GOLDEN_APPLE)) {
            setDebugState("Eating golden apple while retreating");
            return eatGoldenApple(mod);
        }
        if (health < 16 && mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE)) {
            setDebugState("Eating enchanted golden apple");
            return eatEnchantedApple(mod);
        }

        // Eat normal food
        if (health < 18 && StorageHelper.calculateInventoryFoodScore(mod) > 0) {
            setDebugState("Eating food to heal");
            return TaskCatalogue.getItemTask(Items.COOKED_BEEF, 1);
        }

        // Move to fallback position
        if (_fallbackPos != null) {
            double distToFallback = mod.getPlayer().getPos().distanceTo(_fallbackPos);
            if (distToFallback > 5) {
                setDebugState("Retreating to safe spot");
                return new GetToBlockTask(new BlockPos((int) _fallbackPos.x, (int) _fallbackPos.y, (int) _fallbackPos.z));
            }
        }

        // We're at fallback position and healing. Wait for health to recover.
        if (health < FALLBACK_HEALTH) {
            setDebugState("Waiting to heal... (" + String.format("%.1f", health) + " HP)");
            _healTimer.reset();
            return null;
        }

        // Health recovered enough, go back to fighting
        if (_healTimer.elapsed() || health >= 18) {
            Debug.logMessage("[AdvancedPvP] Healed up, resuming combat");
            _phase = Phase.RESUME;
            _fallbackPos = null;
        }
        return null;
    }

    private Task doHealPhase(Belfegor mod, float health, float absorption) {
        setDebugState("Healing: " + String.format("%.1f", health) + " HP");

        if (health >= 18) {
            _phase = Phase.RESUME;
            return null;
        }

        // Try golden apples first
        if (mod.getItemStorage().hasItem(Items.GOLDEN_APPLE)) {
            return eatGoldenApple(mod);
        }

        // Then regular food
        if (StorageHelper.calculateInventoryFoodScore(mod) > 0) {
            return TaskCatalogue.getItemTask(Items.COOKED_BEEF, 1);
        }

        // Nothing to heal with, wait
        return null;
    }

    private Task doResumePhase(Belfegor mod, Optional<PlayerEntity> target) {
        setDebugState("Resuming combat");

        if (target.isPresent()) {
            _phase = Phase.ENGAGE;
            _lastTargetPos = target.get().getPos();
            return null;
        }

        // Target might have moved, go to last known position
        if (_lastTargetPos != null) {
            _phase = Phase.SEEK;
            return null;
        }

        _phase = Phase.SEEK;
        return null;
    }

    private Task eatGoldenApple(Belfegor mod) {
        // Equip and eat golden apple - just get one and the auto-eat chain will handle it
        return TaskCatalogue.getItemTask(Items.GOLDEN_APPLE, 1);
    }

    private Task eatEnchantedApple(Belfegor mod) {
        return TaskCatalogue.getItemTask(Items.ENCHANTED_GOLDEN_APPLE, 1);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof AdvancedPvPTask otherTask) {
            return _targetName.equals(otherTask._targetName);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Advanced PvP [" + _targetName + "] Phase: " + _phase;
    }

    // --- Utility methods ---

    private Optional<PlayerEntity> findTarget(Belfegor mod) {
        if (_targetName == null || _targetName.isEmpty()) return Optional.empty();
        Optional<Entity> closest = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                entity -> {
                    if (entity instanceof PlayerEntity p) {
                        return p.getName().getString().equalsIgnoreCase(_targetName)
                                && !p.isDead()
                                && !p.isCreative()
                                && !p.isSpectator();
                    }
                    return false;
                }, PlayerEntity.class);
        return closest.map(e -> (PlayerEntity) e);
    }

    private boolean hasBestSword(Belfegor mod) {
        return mod.getItemStorage().hasItem(Items.NETHERITE_SWORD) ||
                mod.getItemStorage().hasItem(Items.DIAMOND_SWORD);
    }

    private Item getBestSword() {
        return Items.NETHERITE_SWORD;
    }

    private boolean isFullyArmored(Belfegor mod) {
        return StorageHelper.isArmorEquippedAll(mod,
                new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}) ||
                StorageHelper.isArmorEquippedAll(mod,
                        new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS});
    }

    private Task getArmorTask(Belfegor mod) {
        // Try netherite first, fallback to diamond
        if (mod.getItemStorage().getItemCount(Items.NETHERITE_INGOT) >= 4) {
            return TaskCatalogue.getSquashedItemTask(
                    new ItemTarget(Items.NETHERITE_HELMET, 1),
                    new ItemTarget(Items.NETHERITE_CHESTPLATE, 1),
                    new ItemTarget(Items.NETHERITE_LEGGINGS, 1),
                    new ItemTarget(Items.NETHERITE_BOOTS, 1));
        }
        return TaskCatalogue.getSquashedItemTask(
                new ItemTarget(Items.DIAMOND_HELMET, 1),
                new ItemTarget(Items.DIAMOND_CHESTPLATE, 1),
                new ItemTarget(Items.DIAMOND_LEGGINGS, 1),
                new ItemTarget(Items.DIAMOND_BOOTS, 1));
    }

    private int getBuildingBlockCount(Belfegor mod) {
        int count = 0;
        for (Item block : new Item[]{Items.OAK_PLANKS, Items.COBBLESTONE, Items.DIRT, Items.STONE}) {
            count += mod.getItemStorage().getItemCount(block);
        }
        return count;
    }

    private Vec3d findSafeFallbackPosition(Belfegor mod) {
        Vec3d playerPos = mod.getPlayer().getPos();
        Vec3d awayDir;
        if (_lastTargetPos != null) {
            awayDir = playerPos.subtract(_lastTargetPos).normalize();
        } else {
            awayDir = new Vec3d(1, 0, 0);
        }

        // Go 30 blocks away in the opposite direction, same Y level
        Vec3d fallback = playerPos.add(awayDir.multiply(RETREAT_RANGE));
        return new Vec3d(fallback.x, Math.max(64, fallback.y), fallback.z);
    }
}
