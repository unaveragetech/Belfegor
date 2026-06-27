package adris.altoclef;

import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.util.BlockRange;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.serialization.IFailableConfigFile;
import adris.altoclef.util.serialization.ItemDeserializer;
import adris.altoclef.util.serialization.ItemSerializer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.Streams;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The settings file, loaded and used across the codebase.
 * <p>
 * Each setting is documented.
 */
@SuppressWarnings("ALL")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class Settings implements IFailableConfigFile {

    public static final String SETTINGS_PATH = "belfegor_settings.json";

    // Internal only.
    // If settings failed to load, this will be set to warn the user.
    @JsonIgnore
    private transient boolean _failedToLoad = false;

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** BEGIN SETTINGS w/ COMMENTS **//////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    // ============ DISPLAY ============

    /**
     * If true, text will appear on the top left showing the current task chain.
     */
    private boolean showTaskChains = true;

    /**
     * If true, will show a timer.
     */
    private boolean showTimer = false;

    /**
     * When logging to chat, will prepend this to each log.
     */
    private String chatLogPrefix = "[Belfegor] ";

    /**
     * If true, all warning logs will be disabled.
     */
    private boolean hideAllWarningLogs = false;

    // ============ COMMANDS ============

    /**
     * The prefix for commands (ex. @gamer)
     */
    private String commandPrefix = "@";

    /**
     * If set, will run this command by default when no other commands are running.
     */
    private String idleCommand = "";

    /**
     * If set, will run this command after death. {deathmessage} is replaced with the death message.
     * You may run multiple commands by splitting with " & ".
     */
    private String deathCommand = "";

    // ============ COMBAT SETTINGS ============

    /**
     * Uses killaura to move mobs away and performs survival moves including:
     * running away from hostile mobs when health is low, running from creepers,
     * avoiding wither skeletons, dodging arrows and other projectiles.
     */
    private boolean mobDefense = true;

    /**
     * Defines how force field behaves when mobDefense is on.
     * FASTEST: All hostiles attacked every frame.
     * DELAY: Closest hostile attacked with charged sword.
     * SMART: Closest hostile attacked at max every 0.2 seconds.
     * OFF: Off.
     */
    private KillAura.Strategy forceFieldStrategy = KillAura.Strategy.SMART;

    /**
     * Only applies if mobDefense is on.
     * If enabled, will attempt to dodge all incoming projectiles.
     */
    private boolean dodgeProjectiles = true;

    /**
     * Skeletons and large groups of mobs are a huge pain.
     * With this set to true, the bot may either kill or run away from mobs that stay too close for too long.
     */
    private boolean killOrAvoidAnnoyingHostiles = true;

    /**
     * How close we must be to attack/interact with an entity.
     * 6 works well for singleplayer, 4 works better on restrictive multiplayer servers.
     */
    private float entityReachRange = 4;

    /**
     * At what health level (in half-hearts) should the bot try to retreat and eat?
     * Set to 0 to disable auto-retreat.
     */
    private float criticalHealthThreshold = 6;

    /**
     * If true, the bot will automatically equip the best available shield in the offhand.
     */
    private boolean autoEquipShield = true;

    /**
     * If true, only use swords (not axes/pickaxes) for combat.
     * Axes do more damage but are slower; disable for faster combat.
     */
    private boolean preferSwordForCombat = true;

    /**
     * If true, the bot will avoid attacking wolves (tamed or wild).
     */
    private boolean avoidAttackingWolves = true;

    /**
     * If true, the bot will avoid attacking iron golems (village guardians).
     */
    private boolean avoidAttackingIronGolems = true;

    // ============ PATHFINDING ============

    /**
     * If true, the bot will allow baritone to perform parkour jumps when pathfinding.
     */
    private boolean allowParkour = false;

    /**
     * If true, the bot will allow diagonal ascend when pathfinding.
     */
    private boolean allowDiagonalAscend = false;

    /**
     * If true, the bot will allow diagonal descend when pathfinding.
     */
    private boolean allowDiagonalDescend = false;

    /**
     * If true, the bot will sprint while pathfinding (faster but more hunger).
     */
    private boolean sprintWhilePathing = false;

    /**
     * If true, the bot will avoid placing scaffold blocks to cross gaps.
     * Disable if you want the bot to build bridges.
     */
    private boolean avoidScaffoldPlacement = true;

    // ============ RESOURCE GATHERING ============

    /**
     * Before grabbing ANYTHING, get a pickaxe.
     * Will help with navigation as sometimes dropped items will be underground.
     */
    private boolean collectPickaxeFirst = true;

    /**
     * If true, the bot will automatically craft the best tool tier available.
     * E.g., when it has cobblestone, it will craft stone tools.
     */
    private boolean autoToolProgression = true;

    /**
     * If true, always craft an axe before chopping wood.
     * Slower start but much faster wood gathering overall.
     */
    private boolean alwaysCraftAxeFirst = true;

    /**
     * If a dropped resource item is further than this from the player, don't pick it up.
     * -1 to disable.
     */
    private float resourcePickupDropRange = 32;

    /**
     * Chests are cached for their contents. If the bot finds a chest within this range
     * with the needed resource, it will grab from the chest.
     * Set to 0 to disable chest pickups.
     */
    private float resourceChestLocateRange = 100;

    /**
     * Some block resources can be found in the world within this range and may be mined first.
     * Set to 0 to disable, -1 to always mine if catalogued.
     */
    private float resourceMineRange = 100;

    /**
     * Maximum distance the bot will travel to reach a resource before giving up
     * and trying an alternative method (e.g., crafting instead of mining).
     */
    private float maxResourceTravelDistance = 500;

    /**
     * If true, the bot will prefer mining larger ore veins when multiple are available.
     */
    private boolean preferLargerOreVeins = true;

    /**
     * Automatically use carried shulker boxes as sub-inventories while idle.
     */
    private boolean autoShulkerMode = false;

    /**
     * Auto shulker sorting strategy.
     * timer: while idle, redeposit eligible items after autoShulkerTimerSeconds.
     * detection: while idle, redeposit eligible items when inventory reaches autoShulkerInventoryThreshold percent full.
     */
    private String autoShulkerSortMode = "detection";

    /**
     * Timer-mode interval before eligible items are redeposited into carried shulkers.
     */
    private int autoShulkerTimerSeconds = 60;

    /**
     * Detection-mode inventory fill percentage that triggers auto shulker sorting.
     */
    private int autoShulkerInventoryThreshold = 80;

    // ============ CRAFTING ============

    /**
     * If true, will spread items through the crafting grid for even distribution.
     */
    private boolean spreadItemsToCraft = true;

    /**
     * If true, will open inventory during 2x2 crafting.
     */
    private boolean openInvDuringCrafting = true;

    /**
     * The delay between moving items for crafting/furnace/any kind of inventory movement.
     */
    private float containerItemMoveDelay = 0.2f;

    /**
     * If true, the bot will clear the crafting grid when interrupted to prevent stale items.
     */
    private boolean autoClearCraftingGrid = true;

    /**
     * Maximum time (in seconds) the bot will spend on a single crafting attempt
     * before resetting and trying again.
     */
    private float craftingTimeoutSeconds = 30;

    /**
     * If true, the bot will retry crafting after a failure instead of moving on.
     */
    private boolean retryFailedCrafts = true;

    /**
     * Maximum number of retry attempts for a failed craft.
     */
    private int maxCraftRetries = 3;

    // ============ FOOD & SURVIVAL ============

    /**
     * If true, eat when we're hungry or in danger.
     */
    private boolean autoEat = true;

    /**
     * Minimum hunger level (half-shanks) before the bot eats.
     * 20 = full, 0 = starving. Default 14 = missing 3 shanks.
     */
    private int eatAtHungerLevel = 14;

    /**
     * If true, the bot will collect food when food drops below minimumFoodAllowed.
     */
    private boolean autoCollectFood = true;

    /**
     * Minimum amount of food to have in inventory.
     */
    private int minimumFoodAllowed = 0;

    /**
     * Amount of food to collect when below minimumFoodAllowed.
     */
    private int foodUnitsToCollect = 0;

    /**
     * If true, the bot will try to sleep at night when near a bed.
     */
    private boolean autoSleepAtNight = false;

    /**
     * If true, crops broken when collecting food will be replanted.
     */
    private boolean replantCrops = true;

    /**
     * If true, MLG/No Fall Bucket if we're knocked off course and falling.
     */
    private boolean autoMLGBucket = true;

    /**
     * If enabled, the bot will avoid going underwater if baritone isn't giving movement instructions.
     */
    private boolean avoidDrowning = true;

    /**
     * If enabled, baritone will path around water source blocks instead of swimming through them.
     */
    private boolean avoidWaterSources = true;

    /**
     * If enabled, will attempt to extinguish ourselves when on fire.
     */
    private boolean extinguishSelfWithWater = true;

    /**
     * If enabled, the bot will close screens when its look direction changes or it starts mining.
     */
    private boolean autoCloseScreenWhenLookingOrMining = true;

    /**
     * If true, will automatically reconnect to the last open server if disconnected.
     */
    private boolean autoReconnect = true;

    /**
     * If true, will automatically respawn instantly if you die.
     */
    private boolean autoRespawn = true;

    // ============ MOVEMENT & NAVIGATION ============

    /**
     * This setting configures what the bot does if it needs to go to the nether
     * but can't find a nether portal immediately.
     * BUILD_PORTAL_VANILLA: Builds a portal with obsidian or water+lava.
     * GO_TO_HOME_BASE: Travel to home coordinates assuming a portal is there.
     */
    private DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR overworldToNetherBehaviour = DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR.BUILD_PORTAL_VANILLA;

    /**
     * When fast traveling via the nether, walk to destination if closer than this range.
     */
    private int netherFastTravelWalkingRange = 600;

    /**
     * Where "home base" is for the bot.
     * Some tasks use this value, and the bot may return here when idle.
     */
    private BlockPos homeBasePosition = new BlockPos(0, 64, 0);

    /**
     * If true, the bot will return to homeBasePosition when idle (if idleCommand is empty).
     */
    private boolean returnHomeOnIdle = false;

    /**
     * If true, the bot will avoid going below this Y level when mining
     * (to prevent falling into lava in the nether or deepslate layers).
     * Set to -1 to disable.
     */
    private int avoidMiningBelowY = -1;

    // ============ BLOCK PROTECTION ============

    /**
     * When going to the nearest chest to store items, the bot may dig up dungeons constantly.
     * If true, the bot will search around each chest to make sure it's not in a dungeon.
     */
    private boolean avoidSearchingDungeonChests = true;

    /**
     * Will ignore mining/interacting with blocks that are BELOW an ocean.
     */
    private boolean avoidOceanBlocks = true;

    /**
     * These areas will not be mined. Used to prevent griefing or define spawn protection.
     */
    private List<BlockRange> areasToProtect = Collections.emptyList();

    // ============ ITEM MANAGEMENT ============

    /**
     * If true, items with custom names will be protected so they won't be thrown away.
     */
    private boolean dontThrowAwayCustomNameItems = true;

    /**
     * If true, enchanted items will be protected so they won't be thrown away.
     */
    private boolean dontThrowAwayEnchantedItems = true;

    /**
     * If we need to throw away something, throw away these items first.
     */
    @JsonSerialize(using = ItemSerializer.class)
    @JsonDeserialize(using = ItemDeserializer.class)
    private List<Item> throwawayItems = Arrays.asList(
            Items.DRIPSTONE_BLOCK, Items.ROOTED_DIRT, Items.GRAVEL, Items.SAND,
            Items.DIORITE, Items.ANDESITE, Items.GRANITE, Items.TUFF,
            Items.COBBLESTONE, Items.DIRT, Items.COBBLED_DEEPSLATE,
            Items.ACACIA_LEAVES, Items.BIRCH_LEAVES, Items.DARK_OAK_LEAVES,
            Items.OAK_LEAVES, Items.JUNGLE_LEAVES, Items.SPRUCE_LEAVES,
            Items.NETHERRACK, Items.MAGMA_BLOCK, Items.SOUL_SOIL, Items.SOUL_SAND,
            Items.NETHER_BRICKS, Items.NETHER_BRICK, Items.BASALT, Items.BLACKSTONE,
            Items.END_STONE, Items.SANDSTONE, Items.STONE_BRICKS
    );

    /**
     * How many throwaway blocks to keep as building blocks.
     */
    private int reservedBuildingBlockCount = 64;

    /**
     * If true, any item not in "importantItems" is liable to be thrown away.
     */
    private boolean throwAwayUnusedItems = true;

    /**
     * We will NEVER throw away these items.
     */
    @JsonSerialize(using = ItemSerializer.class)
    @JsonDeserialize(using = ItemDeserializer.class)
    private List<Item> importantItems = Streams.concat(
            Stream.of(
                    Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE,
                    Items.ENDER_EYE, Items.TRIDENT,
                    Items.DIAMOND, Items.DIAMOND_BLOCK,
                    Items.NETHERITE_SCRAP, Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK
            ),
            Stream.of(ItemHelper.DIAMOND_ARMORS),
            Stream.of(ItemHelper.NETHERITE_ARMORS),
            Stream.of(ItemHelper.DIAMOND_TOOLS),
            Stream.of(ItemHelper.NETHERITE_TOOLS),
            Stream.of(ItemHelper.SHULKER_BOXES)
    ).toList();

    /**
     * If true, the bot will automatically equip the best available armor.
     */
    private boolean autoEquipBestArmor = true;

    /**
     * If true, the bot will automatically deposit excess items into nearby chests.
     */
    private boolean autoDepositInChests = false;

    /**
     * Inventory fill percentage (0-100) at which the bot will try to deposit items.
     */
    private int inventoryDepositThreshold = 80;

    // ============ SMELTING ============

    /**
     * If true, a blast furnace will be used for smelting ores.
     */
    private boolean useBlastFurnace = true;

    /**
     * If true, will only accept items found in supportedFuels as fuel when smelting.
     */
    private boolean limitFuelsToSupportedFuels = true;

    /**
     * If limitFuelsToSupportedFuels is true, will use only these items as fuel.
     */
    @JsonSerialize(using = ItemSerializer.class)
    @JsonDeserialize(using = ItemDeserializer.class)
    private List<Item> supportedFuels = Streams.concat(
            Stream.of(Items.COAL, Items.CHARCOAL)
    ).toList();

    // ============ AUTONOMOUS BEHAVIOR ============

    /**
     * If true, the bot will try to use bonemeal on nearby crops when idle.
     */
    private boolean autoBonemealCrops = false;

    /**
     * If true, the bot will follow the nearest player when idle (if idleCommand is empty).
     */
    private boolean followNearestPlayerOnIdle = false;

    /**
     * Maximum distance the bot will wander from home base when performing autonomous tasks.
     * Set to 0 for unlimited.
     */
    private int maxWanderDistanceFromHome = 0;

    /**
     * If true, the bot will attack hostile mobs near the home base even when not tasked.
     */
    private boolean defendHomeBase = false;

    /**
     * Radius around homeBasePosition to defend from hostile mobs.
     */
    private int homeBaseDefenseRadius = 32;

    // ============ EMBEDDED AI ADVISOR ============

    /**
     * If true, Belfegor may call a local Ollama model for high-level advice.
     * This is disabled by default because it launches an external local process.
     */
    private boolean llmAdvisorEnabled = false;

    /**
     * If true, @player may ask the LLM advisor what Belfegor command should run next.
     */
    private boolean llmAdvisorInPlayerMode = false;

    /**
     * If true, the advisor may answer chat-facing prompts through @ai.
     */
    private boolean llmAdvisorCanChat = true;

    /**
     * Ollama executable used for local model calls.
     */
    private String llmOllamaExecutable = "ollama";

    /**
     * Ollama model used by the advisor. Default is the small local thinking model
     * expected from `ollama list`.
     */
    private String llmOllamaModel = "lfm2.5-thinking:1.2b";

    /**
     * Minimum seconds between automatic player-mode LLM requests.
     */
    private int llmAdvisorCooldownSeconds = 90;

    /**
     * Maximum seconds to wait for Ollama before falling back to normal logic.
     */
    private int llmAdvisorTimeoutSeconds = 45;

    /**
     * Requested context window for the model prompt. Ollama may clamp/ignore this
     * depending on model/runtime support.
     */
    private int llmContextSize = 8192;

    /**
     * Maximum generated tokens for the advisor response.
     */
    private int llmMaxTokens = 384;

    // ============ LOGGING & DEBUG ============

    /**
     * If true, detailed debug information will be logged (resource gathering, decisions, etc.)
     */
    private boolean verboseLogging = false;

    /**
     * If true, each crafting step will be logged to help debug crafting issues.
     */
    private boolean logCraftingSteps = false;

    /**
     * If true, pathfinding decisions will be logged.
     */
    private boolean logPathfinding = false;

    /**
     * If true, task completion/failure will be logged with timestamps.
     */
    private boolean logTaskTiming = false;

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** END SETTINGS w/ COMMENTS **////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    public static void load(Consumer<Settings> onReload) {
        ConfigHelper.loadConfig(SETTINGS_PATH, Settings::new, Settings.class, onReload);
    }

    public static void save(Settings settings) {
        ConfigHelper.saveConfig(SETTINGS_PATH, settings);
    }

    public boolean shouldShowTaskChain() {
        return showTaskChains;
    }

    public boolean shouldHideAllWarningLogs() {
        return hideAllWarningLogs;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public String getChatLogPrefix() {
        return chatLogPrefix;
    }

    public boolean shouldSpreadItemsToCraft( ) {return spreadItemsToCraft;}

    public boolean shouldOpenInvDuringCrafting() {return openInvDuringCrafting;}

    public boolean shouldShowTimer() {
        return showTimer;
    }

    public float getResourcePickupRange() {
        return resourcePickupDropRange;
    }

    public float getResourceChestLocateRange() {
        return resourceChestLocateRange;
    }

    public float getResourceMineRange() {
        return resourceMineRange;
    }

    public boolean shouldAutoShulkerMode() {
        return autoShulkerMode;
    }

    public void setAutoShulkerMode(boolean enabled) {
        autoShulkerMode = enabled;
    }

    public String getAutoShulkerSortMode() {
        if (autoShulkerSortMode == null) return "detection";
        String mode = autoShulkerSortMode.trim().toLowerCase();
        return mode.equals("timer") ? "timer" : "detection";
    }

    public void setAutoShulkerSortMode(String mode) {
        if (mode == null) {
            autoShulkerSortMode = "detection";
            return;
        }
        String normalized = mode.trim().toLowerCase();
        autoShulkerSortMode = normalized.equals("timer") ? "timer" : "detection";
    }

    public int getAutoShulkerTimerSeconds() {
        return Math.max(5, autoShulkerTimerSeconds);
    }

    public void setAutoShulkerTimerSeconds(int seconds) {
        autoShulkerTimerSeconds = Math.max(5, seconds);
    }

    public int getAutoShulkerInventoryThreshold() {
        return Math.max(1, Math.min(100, autoShulkerInventoryThreshold));
    }

    public void setAutoShulkerInventoryThreshold(int threshold) {
        autoShulkerInventoryThreshold = Math.max(1, Math.min(100, threshold));
    }

    public float getContainerItemMoveDelay() {
        return containerItemMoveDelay;
    }

    public int getFoodUnitsToCollect() {
        return foodUnitsToCollect;
    }

    public int getMinimumFoodAllowed() {
        return minimumFoodAllowed;
    }

    public boolean isMobDefense() {
        return mobDefense;
    }

    public boolean isDodgeProjectiles() {
        return dodgeProjectiles;
    }

    public boolean isAutoEat() {
        return autoEat;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public boolean isAutoRespawn() {
        return autoRespawn;
    }

    public boolean shouldReplantCrops() {
        return replantCrops;
    }

    public boolean shouldDealWithAnnoyingHostiles() {
        return killOrAvoidAnnoyingHostiles;
    }

    public KillAura.Strategy getForceFieldStrategy() {
        return forceFieldStrategy;
    }

    public String getIdleCommand() {
        return idleCommand;
    }

    public String getDeathCommand() {
        return deathCommand;
    }

    public boolean shouldRunIdleCommandWhenNotActive() {
        return idleCommand != null && !idleCommand.isBlank();
    }

    public boolean shouldAutoMLGBucket() {
        return autoMLGBucket;
    }

    public boolean shouldCollectPickaxeFirst() {
        return collectPickaxeFirst;
    }

    public boolean shouldAvoidDrowning() {
        return avoidDrowning;
    }

    public boolean shouldAvoidWaterSources() {
        return avoidWaterSources;
    }

    public boolean shouldCloseScreenWhenLookingOrMining() {
        return autoCloseScreenWhenLookingOrMining;
    }

    public boolean shouldExtinguishSelfWithWater() {
        return extinguishSelfWithWater;
    }

    public boolean shouldAvoidSearchingForDungeonChests() {
        return avoidSearchingDungeonChests;
    }

    public boolean shouldAvoidOcean() {
        return avoidOceanBlocks;
    }

    public boolean isThrowaway(Item item) {
        return throwawayItems.contains(item);
    }

    public boolean isImportant(Item item) {
        return importantItems.contains(item);
    }

    public boolean shouldThrowawayUnusedItems() {
        return this.throwAwayUnusedItems;
    }

    public int getReservedBuildingBlockCount() {
        return this.reservedBuildingBlockCount;
    }

    public boolean getDontThrowAwayCustomNameItems() {
        return this.dontThrowAwayCustomNameItems;
    }

    public boolean getDontThrowAwayEnchantedItems() {
        return this.dontThrowAwayEnchantedItems;
    }

    public float getEntityReachRange() {
        return entityReachRange;
    }

    public Item[] getThrowawayItems(AltoClef mod, boolean includeProtected) {
        return throwawayItems.stream().filter(item -> includeProtected || !mod.getBehaviour().isProtected(item)).toArray(Item[]::new);
    }

    public Item[] getThrowawayItems(AltoClef mod) {
        return getThrowawayItems(mod, false);
    }

    public boolean shouldLimitFuelsToSupportedFuels() {
        return limitFuelsToSupportedFuels;
    }

    public boolean shouldUseBlastFurnace() {
        return useBlastFurnace;
    }

    public boolean isSupportedFuel(Item item) {
        return !limitFuelsToSupportedFuels || supportedFuels.contains(item);
    }

    @JsonIgnore
    public Item[] getSupportedFuelItems() {
        return supportedFuels.toArray(Item[]::new);
    }

    public boolean isPositionExplicitlyProtected(BlockPos pos) {
        if (!areasToProtect.isEmpty()) {
            for (BlockRange protection : areasToProtect) {
                if (protection.contains(pos)) return true;
            }
        }
        return false;
    }

    public DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR getOverworldToNetherBehaviour() {
        return overworldToNetherBehaviour;
    }

    public int getNetherFastTravelWalkingRange() {
        return netherFastTravelWalkingRange;
    }

    public BlockPos getHomeBasePosition() {
        return homeBasePosition;
    }

    public void setHomeBasePosition(BlockPos pos) {
        if (pos != null) {
            homeBasePosition = pos;
        }
    }

    public void setReturnHomeOnIdle(boolean value) {
        returnHomeOnIdle = value;
    }

    public void setDefendHomeBase(boolean value) {
        defendHomeBase = value;
    }

    public void setHomeBaseDefenseRadius(int radius) {
        homeBaseDefenseRadius = Math.max(4, radius);
    }

    public boolean isLlmAdvisorEnabled() {
        return llmAdvisorEnabled;
    }

    public boolean isLlmAdvisorInPlayerMode() {
        return llmAdvisorEnabled && llmAdvisorInPlayerMode;
    }

    public boolean canLlmAdvisorChat() {
        return llmAdvisorEnabled && llmAdvisorCanChat;
    }

    public String getLlmOllamaExecutable() {
        return llmOllamaExecutable == null || llmOllamaExecutable.isBlank() ? "ollama" : llmOllamaExecutable.trim();
    }

    public String getLlmOllamaModel() {
        return llmOllamaModel == null || llmOllamaModel.isBlank() ? "lfm2.5-thinking:1.2b" : llmOllamaModel.trim();
    }

    public int getLlmAdvisorCooldownSeconds() {
        return Math.max(10, llmAdvisorCooldownSeconds);
    }

    public int getLlmAdvisorTimeoutSeconds() {
        return Math.max(5, llmAdvisorTimeoutSeconds);
    }

    public int getLlmContextSize() {
        return Math.max(2048, llmContextSize);
    }

    public int getLlmMaxTokens() {
        return Math.max(64, llmMaxTokens);
    }

    @Override
    public void onFailLoad() {
        _failedToLoad = true;
    }

    @Override
    public boolean failedToLoad() {
        return _failedToLoad;
    }

    // ============ NEW GETTERS ============

    public float getCriticalHealthThreshold() { return criticalHealthThreshold; }
    public boolean shouldAutoEquipShield() { return autoEquipShield; }
    public boolean preferSwordForCombat() { return preferSwordForCombat; }
    public boolean shouldAvoidAttackingWolves() { return avoidAttackingWolves; }
    public boolean shouldAvoidAttackingIronGolems() { return avoidAttackingIronGolems; }

    public boolean isAllowParkour() { return allowParkour; }
    public boolean isAllowDiagonalAscend() { return allowDiagonalAscend; }
    public boolean isAllowDiagonalDescend() { return allowDiagonalDescend; }
    public boolean isSprintWhilePathing() { return sprintWhilePathing; }
    public boolean isAvoidScaffoldPlacement() { return avoidScaffoldPlacement; }

    public boolean isAutoToolProgression() { return autoToolProgression; }
    public boolean isAlwaysCraftAxeFirst() { return alwaysCraftAxeFirst; }
    public float getMaxResourceTravelDistance() { return maxResourceTravelDistance; }
    public boolean isPreferLargerOreVeins() { return preferLargerOreVeins; }

    public boolean isAutoClearCraftingGrid() { return autoClearCraftingGrid; }
    public float getCraftingTimeoutSeconds() { return craftingTimeoutSeconds; }
    public boolean isRetryFailedCrafts() { return retryFailedCrafts; }
    public int getMaxCraftRetries() { return maxCraftRetries; }

    public int getEatAtHungerLevel() { return eatAtHungerLevel; }
    public boolean isAutoCollectFood() { return autoCollectFood; }
    public boolean isAutoSleepAtNight() { return autoSleepAtNight; }

    public boolean isReturnHomeOnIdle() { return returnHomeOnIdle; }
    public int getAvoidMiningBelowY() { return avoidMiningBelowY; }

    public boolean isAutoEquipBestArmor() { return autoEquipBestArmor; }
    public boolean isAutoDepositInChests() { return autoDepositInChests; }
    public int getInventoryDepositThreshold() { return inventoryDepositThreshold; }

    public boolean isAutoBonemealCrops() { return autoBonemealCrops; }
    public boolean isFollowNearestPlayerOnIdle() { return followNearestPlayerOnIdle; }
    public int getMaxWanderDistanceFromHome() { return maxWanderDistanceFromHome; }
    public boolean isDefendHomeBase() { return defendHomeBase; }
    public int getHomeBaseDefenseRadius() { return homeBaseDefenseRadius; }

    public boolean isVerboseLogging() { return verboseLogging; }
    public boolean isLogCraftingSteps() { return logCraftingSteps; }
    public boolean isLogPathfinding() { return logPathfinding; }
    public boolean isLogTaskTiming() { return logTaskTiming; }
}
