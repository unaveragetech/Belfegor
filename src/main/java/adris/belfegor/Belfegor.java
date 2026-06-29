package adris.belfegor;

import adris.belfegor.butler.Butler;
import adris.belfegor.chains.*;
import adris.belfegor.commandsystem.CommandExecutor;
import adris.belfegor.control.InputControls;
import adris.belfegor.control.PlayerExtraController;
import adris.belfegor.control.SlotHandler;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.eventbus.EventBus;
import adris.belfegor.eventbus.events.ClientRenderEvent;
import adris.belfegor.eventbus.events.ClientTickEvent;
import adris.belfegor.eventbus.events.SendChatEvent;
import adris.belfegor.eventbus.events.TitleScreenEntryEvent;
import adris.belfegor.macros.MacroRunner;
import adris.belfegor.macros.MacroStorage;
import adris.belfegor.memory.CraftingMemory;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.DecisionEngine;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.memory.ShulkerMemory;
import adris.belfegor.memory.SpatialAwareness;
import adris.belfegor.tasksystem.CraftingPathRegistry;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.tasksystem.TaskRunner;
import adris.belfegor.trackers.*;
import adris.belfegor.trackers.storage.ContainerSubTracker;
import adris.belfegor.trackers.storage.ItemStorageTracker;
import adris.belfegor.ui.BelfegorScreen;
import adris.belfegor.ui.CommandStatusOverlay;
import adris.belfegor.ui.MessagePriority;
import adris.belfegor.ui.MessageSender;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.InputHelper;
import baritone.belfegor.BelfegorSettings;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Central access point for Belfegor
 */
public class Belfegor implements ModInitializer {

    // Static access to Belfegor
    private static final Queue<Consumer<Belfegor>> _postInitQueue = new ArrayDeque<>();

    // Central Managers
    private static CommandExecutor _commandExecutor;
    private TaskRunner _taskRunner;
    private TrackerManager _trackerManager;
    private BotBehaviour _botBehaviour;
    private PlayerExtraController _extraController;
    // Task chains
    private UserTaskChain _userTaskChain;
    private FoodChain _foodChain;
    private MobDefenseChain _mobDefenseChain;
    private MLGBucketFallChain _mlgBucketChain;
    // Trackers
    private ItemStorageTracker _storageTracker;
    private ContainerSubTracker _containerSubTracker;
    private EntityTracker _entityTracker;
    private BlockTracker _blockTracker;
    private SimpleChunkTracker _chunkTracker;
    private MiscBlockTracker _miscBlockTracker;
    // Renderers
    private CommandStatusOverlay _commandStatusOverlay;
    // Settings
    private adris.belfegor.Settings _settings;
    // Misc managers/input
    private MessageSender _messageSender;
    private InputControls _inputControls;
    private SlotHandler _slotHandler;
    // Butler
    private Butler _butler;
    // Macro system
    private MacroRunner _macroRunner;
    private BelfegorScreen _BelfegorScreen;
    // Tick counter for periodic saves
    private int _tickCount = 0;
    private boolean _abortKeyWasDown = false;
    private long _lastAbortHotkeyMs = 0;
    private long _lastAutoShulkerSortMs = 0;
    private String _lastAutoShulkerFingerprint = "";
    private boolean _hasInitializedRuntime = false;

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    /**
     * Executes commands (ex. `@get`/`@gamer`)
     */
    public static CommandExecutor getCommandExecutor() {
        return _commandExecutor;
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // The title-screen hook is the preferred runtime entry point, but some
        // launchers/world shortcuts can jump directly into a save and skip the
        // title screen entirely. Keep a first-client-tick fallback so commands,
        // logging, settings migration, and trackers always come online.
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());
        EventBus.subscribe(ClientTickEvent.class, evt -> onInitializeLoad());
    }

    public void onInitializeLoad() {
        if (_hasInitializedRuntime) {
            return;
        }
        _hasInitializedRuntime = true;

        // This code should be run after Minecraft loads everything else in.
        // This is the actual start point, controlled by a mixin.

        initializeBaritoneSettings();

        // Central Managers
        _commandExecutor = new CommandExecutor(this);
        _taskRunner = new TaskRunner(this);
        _trackerManager = new TrackerManager(this);
        _botBehaviour = new BotBehaviour(this);
        _extraController = new PlayerExtraController(this);

        // Task chains
        _userTaskChain = new UserTaskChain(_taskRunner);
        _mobDefenseChain = new MobDefenseChain(_taskRunner);
        new DeathMenuChain(_taskRunner);
        new PlayerInteractionFixChain(_taskRunner);
        _mlgBucketChain = new MLGBucketFallChain(_taskRunner);
        new WorldSurvivalChain(_taskRunner);
        _foodChain = new FoodChain(_taskRunner);
        new ToolRequirementChain(_taskRunner);

        // Trackers
        _storageTracker = new ItemStorageTracker(this, _trackerManager, container -> _containerSubTracker = container);
        _entityTracker = new EntityTracker(_trackerManager);
        _blockTracker = new BlockTracker(this, _trackerManager);
        _chunkTracker = new SimpleChunkTracker(this);
        _miscBlockTracker = new MiscBlockTracker(this);

        // Renderers
        _commandStatusOverlay = new CommandStatusOverlay();

        // Misc managers
        _messageSender = new MessageSender();
        _inputControls = new InputControls();
        _slotHandler = new SlotHandler(this);

        _butler = new Butler(this);

        // Macro system
        _macroRunner = new MacroRunner(this);
        _BelfegorScreen = new BelfegorScreen(this);
        try {
            MacroStorage.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize macro storage: " + e.getMessage());
        }
        try {
            LocationMemory.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize location memory: " + e.getMessage());
        }
        try {
            BaseMemory.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize base memory: " + e.getMessage());
        }
        try {
            SpatialAwareness.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize spatial awareness: " + e.getMessage());
        }
        try {
            CraftingMemory.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize crafting memory: " + e.getMessage());
        }
        try {
            ShulkerMemory.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize shulker memory: " + e.getMessage());
        }
        try {
            CraftingPathRegistry.init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize crafting path registry: " + e.getMessage());
        }
        // DecisionEngine uses CraftingMemory, no separate init needed

        // Initialize debug logger
        try {
            DebugLogger.getInstance().init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize debug logger: " + e.getMessage());
        }
        try {
            adris.belfegor.llm.LlmAdvisor.getInstance().init(new java.io.File("."));
        } catch (Exception e) {
            Debug.logWarning("Failed to initialize LLM advisor: " + e.getMessage());
        }

        // Load comprehensive recipe database from PrismarineJS data
        try {
            adris.belfegor.util.RecipeRegistry.getInstance().load();
        } catch (Exception e) {
            Debug.logWarning("Failed to load recipe registry: " + e.getMessage());
        }

        initializeCommands();

        // Load settings
        adris.belfegor.Settings.load(newSettings -> {
            _settings = newSettings;
            // Baritone's `acceptableThrowawayItems` should match our own.
            List<Item> baritoneCanPlace = Arrays.stream(_settings.getThrowawayItems(this, true))
                    .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND && item
                            != Items.GRAVEL).toList();
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(baritoneCanPlace);
            // If we should run an idle command...
            if ((!getUserTaskChain().isActive() || getUserTaskChain().isRunningIdleTask()) && getModSettings().shouldRunIdleCommandWhenNotActive()) {
                getUserTaskChain().signalNextTaskToBeIdleTask();
                getCommandExecutor().executeWithPrefix(getModSettings().getIdleCommand());
            }
            // Don't break blocks or place blocks where we are explicitly protected.
            getExtraBaritoneSettings().avoidBlockBreak(blockPos -> _settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().avoidBlockPlace(blockPos -> _settings.isPositionExplicitlyProtected(blockPos));
            adris.belfegor.llm.LlmAdvisor.getInstance().exportCommandCatalogue(this);
        });

        // Receive + cancel chat
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (getCommandExecutor().isClientCommand(line)) {
                evt.cancel();
                getCommandExecutor().execute(line);
            }
        });

        // Debug jank/hookup
        Debug.jankModInstance = this;

        // Tick with the client
        EventBus.subscribe(ClientTickEvent.class, evt -> onClientTick());
        // Render
        EventBus.subscribe(ClientRenderEvent.class, evt -> onClientRenderOverlay(evt.context));

        // Track placed blocks so we never mine them
        EventBus.subscribe(adris.belfegor.eventbus.events.BlockPlaceEvent.class, evt -> {
            _botBehaviour.avoidBlockBreaking(evt.blockPos);
            _botBehaviour.addPlacedBlock(evt.blockPos);
        });

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // External mod initialization
        runEnqueuedPostInits();
    }

    // Client tick
    private void onClientTick() {
        runEnqueuedPostInits();

        _inputControls.onTickPre();

        // Global abort: keypad '+' or Shift+'='. This polling runs regardless
        // of which Minecraft screen currently owns keyboard focus.
        boolean abortKeyDown = InputHelper.isKeyPressed(GLFW.GLFW_KEY_KP_ADD)
                || ((InputHelper.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputHelper.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT))
                && InputHelper.isKeyPressed(GLFW.GLFW_KEY_EQUAL));
        if (abortKeyDown && !_abortKeyWasDown) {
            abortAllAutomation();
        }
        _abortKeyWasDown = abortKeyDown;

        // Legacy cancel shortcut
        if (InputHelper.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) && InputHelper.isKeyPressed(GLFW.GLFW_KEY_K)) {
            abortAllAutomation();
        }

        // Open Belfegor menu with 'C' key (only when no screen is open)
        if (InputHelper.isKeyPressed(GLFW.GLFW_KEY_C) && MinecraftClient.getInstance().currentScreen == null && inGame()) {
            MinecraftClient.getInstance().setScreen(_BelfegorScreen);
        }

        // Macro runner tick
        if (_macroRunner != null) {
            _macroRunner.tick();
        }

        // Auto-sort only while no explicit command or macro owns the user lane.
        if (_tickCount % 20 == 0
                && inGame()
                && _settings != null
                && _settings.shouldAutoShulkerMode()
                && !_userTaskChain.isActive()
                && (_macroRunner == null || !_macroRunner.isRunning())
                && adris.belfegor.tasks.container.ShulkerInteractionTask.hasCarriedShulker(this)) {
            var targets = adris.belfegor.tasks.container.ShulkerInteractionTask.getAutoStoreTargets(this);
            if (targets.length > 0 && shouldRunAutoShulkerSort(targets)) {
                _lastAutoShulkerSortMs = System.currentTimeMillis();
                _lastAutoShulkerFingerprint = autoShulkerFingerprint(targets);
                runUserTask(new adris.belfegor.tasks.container.ShulkerInteractionTask(
                        adris.belfegor.tasks.container.ShulkerInteractionTask.Mode.STORE, targets));
            }
        }

        // TODO: should this go here?
        _storageTracker.setDirty();
        if (_containerSubTracker != null) {
            _containerSubTracker.onServerTick();
        }
        _miscBlockTracker.tick();

        _trackerManager.tick();
        _blockTracker.preTickTask();
        _taskRunner.tick();
        _blockTracker.postTickTask();

        // Scan nearby blocks for notable ores and record in LocationMemory
        if (_tickCount % 100 == 0 && getPlayer() != null && getWorld() != null) {
            scanNotableBlocks();
            SpatialAwareness.getInstance().scan(this, 8);
            adris.belfegor.tasks.container.ShulkerInteractionTask.syncCarriedShulkerMemory(this);
        }

        _butler.tick();
        _messageSender.tick();

        // Save location memory periodically (every 30 seconds)
        if (_tickCount % 600 == 0) {
            LocationMemory.getInstance().save();
            BaseMemory.getInstance().save();
            SpatialAwareness.getInstance().save();
            CraftingMemory.getInstance().save();
            CraftingPathRegistry.getInstance().save();
        }
        _tickCount++;

        // Flush debug log to disk
        DebugLogger.getInstance().flush();

        _inputControls.onTickPost();
    }

    private boolean shouldRunAutoShulkerSort(ItemTarget[] targets) {
        String mode = _settings.getAutoShulkerSortMode();
        long now = System.currentTimeMillis();
        if ("timer".equals(mode)) {
            return now - _lastAutoShulkerSortMs >= _settings.getAutoShulkerTimerSeconds() * 1000L;
        }

        int occupied = getOccupiedMainInventorySlots();
        int threshold = _settings.getAutoShulkerInventoryThreshold();
        int occupiedPercent = (int) Math.ceil(occupied * 100.0 / 36.0);
        if (occupiedPercent < threshold) {
            return false;
        }
        String fingerprint = autoShulkerFingerprint(targets);
        return !fingerprint.equals(_lastAutoShulkerFingerprint)
                || now - _lastAutoShulkerSortMs >= 10_000L;
    }

    private int getOccupiedMainInventorySlots() {
        if (getPlayer() == null) return 0;
        int occupied = 0;
        for (var stack : getPlayer().getInventory().main) {
            if (!stack.isEmpty()) occupied++;
        }
        return occupied;
    }

    private String autoShulkerFingerprint(ItemTarget[] targets) {
        return Arrays.stream(targets)
                .map(target -> Arrays.toString(target.getMatches()) + "x" + target.getTargetCount())
                .sorted()
                .reduce("", (a, b) -> a + "|" + b);
    }

    public void abortAllAutomation() {
        long now = System.currentTimeMillis();
        if (now - _lastAbortHotkeyMs < 300) {
            return;
        }
        _lastAbortHotkeyMs = now;
        DebugLogger.getInstance().logImmediate("GLOBAL-ABORT", "Emergency abort key pressed");
        if (_macroRunner != null && _macroRunner.isRunning()) {
            _macroRunner.stop();
        }
        if (_userTaskChain != null) {
            _userTaskChain.cancel(this);
        }
        if (_taskRunner != null) {
            _taskRunner.disable();
        }
        getClientBaritone().getPathingBehavior().cancelEverything();
        getClientBaritone().getInputOverrideHandler().clearAllKeys();
        if (_inputControls != null) {
            _inputControls.releaseAll();
        }
        if (getPlayer() != null
                && !adris.belfegor.util.helpers.StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            var cursor = adris.belfegor.util.helpers.StorageHelper.getItemStackInCursorSlot();
            getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, true)
                    .ifPresent(slot -> getSlotHandler().clickSlotForce(
                            slot, 0, SlotActionType.PICKUP));
        }
        adris.belfegor.util.helpers.StorageHelper.closeScreen();
        log("Automation aborted with +", MessagePriority.TIMELY);
    }

    public void reloadModSettings() {
        adris.belfegor.Settings.load(settings -> _settings = settings);
    }

    /**
     * Scans nearby blocks for notable ores and structures, recording them in LocationMemory
     * so the bot can find them later without wandering.
     */
    private void scanNotableBlocks() {
        var player = getPlayer();
        if (player == null) return;
        var world = getWorld();
        if (world == null) return;

        int cx = player.getBlockX();
        int cy = player.getBlockY();
        int cz = player.getBlockZ();
        int radius = 16;
        String dim = adris.belfegor.util.helpers.WorldHelper.getCurrentDimension().name();

        for (int x = cx - radius; x <= cx + radius; x += 4) {
            for (int y = cy - 8; y <= cy + 8; y += 4) {
                for (int z = cz - radius; z <= cz + radius; z += 4) {
                    var state = world.getBlockState(net.minecraft.util.math.BlockPos.ORIGIN.add(x, y, z));
                    var block = state.getBlock();
                    String category = getNotableBlockCategory(block);
                    if (category != null) {
                        LocationMemory.getInstance().remember(category, x, y, z, dim, "");
                    }
                }
            }
        }
    }

    private static String getNotableBlockCategory(net.minecraft.block.Block block) {
        var id = net.minecraft.registry.Registries.BLOCK.getId(block);
        String name = id.getPath();
        if (name.contains("iron_ore") || name.contains("gold_ore") || name.contains("diamond_ore")
                || name.contains("coal_ore") || name.contains("redstone_ore") || name.contains("lapis_ore")
                || name.contains("emerald_ore") || name.contains("copper_ore")) return "ore";
        if (name.contains("deepslate") && name.contains("ore")) return "ore";
        if (block == net.minecraft.block.Blocks.CRAFTING_TABLE) return "crafting_table";
        if (block == net.minecraft.block.Blocks.FURNACE) return "furnace";
        if (block == net.minecraft.block.Blocks.BLAST_FURNACE) return "blast_furnace";
        if (block == net.minecraft.block.Blocks.SMOKER) return "smoker";
        if (block == net.minecraft.block.Blocks.CHEST || block == net.minecraft.block.Blocks.BARREL) return "chest";
        if (block == net.minecraft.block.Blocks.ENDER_CHEST) return "ender_chest";
        return null;
    }

    /// GETTERS AND SETTERS

    private void onClientRenderOverlay(net.minecraft.client.gui.DrawContext drawContext) {
        _commandStatusOverlay.render(this, drawContext);
    }

    /**
     * Reapply baritone settings from current Belfegor settings.
     * Call this after changing settings from the GUI.
     */
    public void reapplyBaritoneSettings() {
        if (_settings != null) {
            getClientBaritoneSettings().allowParkour.value = _settings.isAllowParkour();
            getClientBaritoneSettings().allowParkourAscend.value = _settings.isAllowParkour();
            getClientBaritoneSettings().allowParkourPlace.value = _settings.isAllowParkour();
            getClientBaritoneSettings().allowDiagonalDescend.value = _settings.isAllowDiagonalDescend();
            getClientBaritoneSettings().allowDiagonalAscend.value = _settings.isAllowDiagonalAscend();
        }
    }

    private void initializeBaritoneSettings() {
        getExtraBaritoneSettings().canWalkOnEndPortal(false);
        getClientBaritoneSettings().freeLook.value = false;
        getClientBaritoneSettings().overshootTraverse.value = false;
        getClientBaritoneSettings().allowOvershootDiagonalDescend.value = true;
        getClientBaritoneSettings().allowInventory.value = true;
        // Allow breaking blocks for pathing (escaping holes, etc.)
        getClientBaritoneSettings().allowBreak.value = true;

        // Avoid water: add water source blocks to blocksToAvoid so baritone
        // paths around them instead of swimming through them. This prevents the bot
        // from getting stuck in water, drowning, or taking unnecessary swim paths.
        if (_settings == null || _settings.shouldAvoidWaterSources()) {
            getExtraBaritoneSettings().getForceAvoidWalkThroughPredicates().add(pos -> {
                if (MinecraftClient.getInstance().world == null) return false;
                return isFluidHazardForWalking(pos)
                        || isFluidHazardForWalking(pos.up())
                        || isFluidHazardForWalking(pos.down());
            });
        }
        // Apply user pathfinding settings
        if (_settings != null) {
            getClientBaritoneSettings().allowParkour.value = _settings.isAllowParkour();
            getClientBaritoneSettings().allowParkourAscend.value = _settings.isAllowParkour();
            getClientBaritoneSettings().allowParkourPlace.value = _settings.isAllowParkour();
            getClientBaritoneSettings().allowDiagonalDescend.value = _settings.isAllowDiagonalDescend();
            getClientBaritoneSettings().allowDiagonalAscend.value = _settings.isAllowDiagonalAscend();
        } else {
            getClientBaritoneSettings().allowParkour.value = false;
            getClientBaritoneSettings().allowParkourAscend.value = false;
            getClientBaritoneSettings().allowParkourPlace.value = false;
            getClientBaritoneSettings().allowDiagonalDescend.value = false;
            getClientBaritoneSettings().allowDiagonalAscend.value = false;
        }
        getClientBaritoneSettings().blocksToAvoid.value = List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.GRASS_BLOCK, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SUNFLOWER, Blocks.LILAC,
                Blocks.ROSE_BUSH, Blocks.PEONY);
        // Let baritone move items to hotbar to use them
        // Reduces a bit of far rendering to save FPS
        getClientBaritoneSettings().fadePath.value = true;
        // Don't let baritone scan dropped items, we handle that ourselves.
        getClientBaritoneSettings().mineScanDroppedItems.value = false;
        // Don't let baritone wait for drops, we handle that ourselves.
        getClientBaritoneSettings().mineDropLoiterDurationMSThanksLouca.value = 0L;

        // Water bucket placement will be handled by us exclusively
        getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);

        // Avoid water: don't let baritone path through water source blocks.
        // The bot should go around water rather than swimming through it.
        getExtraBaritoneSettings().setFlowingWaterPass(false);

        // For render smoothing
        getClientBaritoneSettings().randomLooking.value = 0.0;
        getClientBaritoneSettings().randomLooking113.value = 0.0;

        // Give baritone more time to calculate paths. Sometimes they can be really far away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.reset();
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.reset();
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.reset();
    }

    private static boolean isFluidHazardForWalking(net.minecraft.util.math.BlockPos pos) {
        var world = MinecraftClient.getInstance().world;
        if (world == null || pos == null) return false;
        var fluidState = world.getBlockState(pos).getFluidState();
        if (fluidState.isEmpty()) return false;
        return fluidState.isIn(net.minecraft.registry.tag.FluidTags.WATER)
                || fluidState.isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                || fluidState.getFluid() == net.minecraft.fluid.Fluids.WATER
                || fluidState.getFluid() == net.minecraft.fluid.Fluids.FLOWING_WATER
                || fluidState.getFluid() == net.minecraft.fluid.Fluids.LAVA
                || fluidState.getFluid() == net.minecraft.fluid.Fluids.FLOWING_LAVA;
    }

    // List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to initialize new command lists.
            new BelfegorCommands();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return _taskRunner;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return _userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks or items
     */
    public BotBehaviour getBehaviour() {
        return _botBehaviour;
    }

    /**
     * Tracks items in your inventory and in storage containers.
     */
    public ItemStorageTracker getItemStorage() {
        return _storageTracker;
    }

    /**
     * Tracks loaded entities
     */
    public EntityTracker getEntityTracker() {
        return _entityTracker;
    }

    /**
     * Tracks blocks and their positions
     */
    public BlockTracker getBlockTracker() {
        return _blockTracker;
    }

    /**
     * Tracks of whether a chunk is loaded/visible or not
     */
    public SimpleChunkTracker getChunkTracker() {
        return _chunkTracker;
    }

    /**
     * Tracks random block things, like the last nether portal we used
     */
    public MiscBlockTracker getMiscBlockTracker() {
        return _miscBlockTracker;
    }

    /**
     * Baritone access (could just be static honestly)
     */
    public IBaritone getClientBaritone() {
        if (getPlayer() == null) {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        ClientPlayerEntity player = getPlayer();
        if (player == null) {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        return BaritoneAPI.getProvider().getBaritoneForPlayer(player);
    }

    /**
     * Baritone settings access (could just be static honestly)
     */
    public Settings getClientBaritoneSettings() {
        return BaritoneAPI.getSettings();
    }

    /**
     * Baritone settings special to Belfegor (could just be static honestly)
     */
    public BelfegorSettings getExtraBaritoneSettings() {
        return BelfegorSettings.getInstance();
    }

    /**
     * Belfegor Settings
     */
    public adris.belfegor.Settings getModSettings() {
        return _settings;
    }

    /**
     * Butler controller. Keeps track of users and lets you receive user messages
     */
    public Butler getButler() {
        return _butler;
    }

    /**
     * Sends chat messages (avoids auto-kicking)
     */
    public MessageSender getMessageSender() {
        return _messageSender;
    }

    /**
     * Does Inventory/container slot actions
     */
    public SlotHandler getSlotHandler() {
        return _slotHandler;
    }

    /**
     * Minecraft player client access (could just be static honestly)
     */
    public ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    /**
     * Minecraft world access (could just be static honestly)
     */
    public ClientWorld getWorld() {
        return MinecraftClient.getInstance().world;
    }

    /**
     * Minecraft client interaction controller access (could just be static honestly)
     */
    public ClientPlayerInteractionManager getController() {
        return MinecraftClient.getInstance().interactionManager;
    }

    /**
     * Extra controls not present in ClientPlayerInteractionManager. This REALLY should be made static or combined with something else.
     */
    public PlayerExtraController getControllerExtras() {
        return _extraController;
    }

    /**
     * Manual control over input actions (ex. jumping, attacking)
     */
    public InputControls getInputControls() {
        return _inputControls;
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task) {
        runUserTask(task, () -> {
        });
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task, Runnable onFinish) {
        _userTaskChain.runTask(this, task, onFinish);
    }

    /**
     * Cancel currently running user task
     */
    public void cancelUserTask() {
        _userTaskChain.cancel(this);
    }

    /**
     * Takes control away to eat food
     */
    public FoodChain getFoodChain() {
        return _foodChain;
    }

    /**
     * Takes control away to defend against mobs
     */
    public MobDefenseChain getMobDefenseChain() {
        return _mobDefenseChain;
    }

    /**
     * Takes control away to perform bucket saves
     */
    public MLGBucketFallChain getMLGBucketChain() {
        return _mlgBucketChain;
    }

    /**
     * Macro runner for executing reusable command chains
     */
    public MacroRunner getMacroRunner() {
        return _macroRunner;
    }

    /**
     * Belfegor control panel screen
     */
    public BelfegorScreen getBelfegorScreen() {
        return _BelfegorScreen;
    }

    /**
     * Location memory - remembers significant locations
     */
    public LocationMemory getMemory() {
        return LocationMemory.getInstance();
    }

    /**
     * Open the Belfegor control panel
     */
    public void openScreen() {
        if (inGame()) {
            MinecraftClient.getInstance().setScreen(_BelfegorScreen);
        }
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    /**
     * Logs to the console and also messages any player using the bot as a butler.
     */
    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
        if (_butler != null) {
            _butler.onLog(message, priority);
        }
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    /**
     * Logs a warning to the console and also alerts any player using the bot as a butler.
     */
    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
        if (_butler != null) {
            _butler.onLogWarning(message, priority);
        }
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

}
