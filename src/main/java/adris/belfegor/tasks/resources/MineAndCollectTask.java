package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.RecentPlacedBlockMemory;
import adris.belfegor.tasks.AbstractDoToClosestObjectTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.PickupDroppedItemTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.MiningRequirement;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.progresscheck.MovementProgressChecker;
import adris.belfegor.util.slots.CursorSlot;
import adris.belfegor.util.slots.PlayerSlot;
import adris.belfegor.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MiningToolItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import adris.belfegor.ItemInfo.PickaxeMiningSpeedUtility;

import java.util.*;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);

    private final MineOrCollectTask _subtask;
    private final boolean _mineSurplusVein;

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets);
        _mineSurplusVein = Arrays.stream(_blocksToMine).anyMatch(MineAndCollectTask::isOreBlock);
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.getBlockFromItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_blocksToMine);

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        _subtask.resetSearch();
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            return new SatisfyMiningRequirementTask(_requirement);
        }

        if (_mineSurplusVein && StorageHelper.itemTargetsMetInventoryNoCursor(mod, _itemTargets)) {
            if (_subtask.hasNearbySurplusVeinBlock(mod)) {
                setDebugState("Mining nearby surplus ore from current vein");
                _subtask.enableSurplusVeinMode();
                return _subtask;
            }
            _subtask.disableSurplusVeinMode();
        }

        if (_subtask.isMining()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod) && !mod.getBlockTracker().anyFound(_blocksToMine)) {
            return getToCorrectDimensionTask(mod);
        }

        return _subtask;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        if (!StorageHelper.itemTargetsMetInventoryNoCursor(mod, _itemTargets)) {
            return false;
        }
        return !_mineSurplusVein || !_subtask.hasNearbySurplusVeinBlock(mod);
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_blocksToMine);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Mine And Collect";
    }

    private void makeSureToolIsEquipped(Belfegor mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().needsToEat()) {
            assert MinecraftClient.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                if (cursorStack.isSuitableFor(mod.getWorld().getBlockState(_subtask.miningPos()))) {
                    // Our cursor stack would help us mine our current block
                    Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (cursorStack.getItem() instanceof MiningToolItem) {
                        if (currentlyEquipped instanceof MiningToolItem currentPick) {
                            MiningToolItem swapPick = (MiningToolItem) cursorStack.getItem();
                            if (PickaxeMiningSpeedUtility.getMiningSpeed(swapPick.getName().toString().toLowerCase()) > PickaxeMiningSpeedUtility.getMiningSpeed(currentPick.getName().toString().toLowerCase())) {
                                // We can equip a better pickaxe.
                                mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                        }
                    }
                }
            }
            _cursorStackTimer.reset();
        }
    }

    private static boolean isOreBlock(Block block) {
        if (block == null) return false;
        String key = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
        return key.endsWith("_ore") || key.contains("_ore");
    }

    private static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private static final int LOCAL_SCAN_RADIUS = 12;
        private static final int LOCAL_SCAN_VERTICAL = 6;
        private static final long LOCAL_SCAN_INTERVAL_MS = 2000;
        private static final double LOCAL_SCAN_RECENTER_DISTANCE_SQ = 36;

        private final Block[] _blocks;
        private final ItemTarget[] _targets;
        private final Set<BlockPos> _blacklist = new HashSet<>();
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
        private final Task _pickupTask;
        private Task _cachedWanderTask = null;
        private BlockPos _miningPos;
        private BlockPos _lastLoggedLocalBlock;
        private long _lastLocalLogMs;
        private Vec3d _lastLocalScanOrigin;
        private long _lastLocalScanMs;
        private Optional<BlockPos> _lastLocalScanResult = Optional.empty();
        private BlockPos _surplusAnchor;
        private boolean _surplusVeinMode;
        private Vec3d _searchOrigin;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            _blocks = blocks;
            _targets = targets;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
        }

        @Override
        protected Vec3d getPos(Belfegor mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                return item.getPos();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected Optional<Object> getClosestTo(Belfegor mod, Vec3d pos) {
            if (_surplusVeinMode) {
                Optional<BlockPos> surplus = findNearbySurplusVeinBlock(mod);
                return surplus.map(Object.class::cast);
            }
            Optional<BlockPos> closestBlock = mod.getBlockTracker().getNearestTracking(pos, check -> {
                if (_blacklist.contains(check)) return false;
                if (RecentPlacedBlockMemory.wasRecentlyPlaced(check)) return false;
                if (isProtectedBaseMiningArea(check)) return false;
                if (mod.getBlockTracker().unreachable(check)) return false;
                if (!withinTravelBudget(mod, WorldHelper.toVec3d(check))) return false;
                return WorldHelper.canBreak(mod, check);
            }, _blocks);

            Optional<BlockPos> localBlock = cachedScanLoadedLocalBlocks(mod, pos);
            if (localBlock.isPresent()) {
                double trackerSq = closestBlock.isEmpty()
                        ? Double.POSITIVE_INFINITY
                        : closestBlock.get().getSquaredDistance(pos);
                double localSq = localBlock.get().getSquaredDistance(pos);
                if (localSq <= trackerSq + 16) {
                    long now = System.currentTimeMillis();
                    boolean changedLocal = _lastLoggedLocalBlock == null || !_lastLoggedLocalBlock.equals(localBlock.get());
                    if ((closestBlock.isEmpty() || !closestBlock.get().equals(localBlock.get()))
                            && (changedLocal || now - _lastLocalLogMs > 5000)) {
                        _lastLoggedLocalBlock = localBlock.get();
                        _lastLocalLogMs = now;
                        DebugLogger.getInstance().log("RESOURCE-LOCALITY",
                                "local-block-preferred target=" + Arrays.toString(_targets)
                                        + " blocks=" + Arrays.toString(_blocks)
                                        + " local=" + localBlock.get().toShortString()
                                        + " localDist=" + Math.round(Math.sqrt(localSq))
                                        + " tracker=" + closestBlock.map(BlockPos::toShortString).orElse("none")
                                        + " trackerDist=" + (trackerSq == Double.POSITIVE_INFINITY
                                        ? "inf" : String.valueOf(Math.round(Math.sqrt(trackerSq)))));
                    }
                    closestBlock = localBlock;
                }
            }

            Optional<ItemEntity> closestDrop = Optional.empty();
            if (mod.getEntityTracker().itemDropped(_targets)) {
                closestDrop = mod.getEntityTracker().getClosestItemDrop(pos, _targets);
                if (closestDrop.isPresent()
                        && !withinTravelBudget(mod, closestDrop.get().getPos())) {
                    closestDrop = Optional.empty();
                }
            }

            double blockSq = closestBlock.isEmpty() ? Double.POSITIVE_INFINITY : closestBlock.get().getSquaredDistance(pos);
            double dropSq = closestDrop.isEmpty() ? Double.POSITIVE_INFINITY : closestDrop.get().squaredDistanceTo(pos) + 10; // + 5 to make the bot stop mining a bit less

            // We can't mine right now.
            if (mod.getExtraBaritoneSettings().isInteractionPaused()) {
                return closestDrop.map(Object.class::cast);
            }

            if (dropSq <= blockSq) {
                return closestDrop.map(Object.class::cast);
            } else {
                return closestBlock.map(Object.class::cast);
            }
        }

        private Optional<BlockPos> cachedScanLoadedLocalBlocks(Belfegor mod, Vec3d origin) {
            long now = System.currentTimeMillis();
            if (_lastLocalScanOrigin != null
                    && now - _lastLocalScanMs < LOCAL_SCAN_INTERVAL_MS
                    && _lastLocalScanOrigin.squaredDistanceTo(origin) < LOCAL_SCAN_RECENTER_DISTANCE_SQ) {
                return _lastLocalScanResult;
            }
            _lastLocalScanOrigin = origin;
            _lastLocalScanMs = now;
            _lastLocalScanResult = scanLoadedLocalBlocks(mod, origin, LOCAL_SCAN_RADIUS, LOCAL_SCAN_VERTICAL);
            return _lastLocalScanResult;
        }

        private Optional<BlockPos> scanLoadedLocalBlocks(Belfegor mod, Vec3d origin, int radius, int vertical) {
            if (mod.getWorld() == null) return Optional.empty();
            BlockPos center = BlockPos.ofFloored(origin);
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius) continue;
                        BlockPos candidate = center.add(dx, dy, dz);
                        if (_blacklist.contains(candidate)) continue;
                        if (RecentPlacedBlockMemory.wasRecentlyPlaced(candidate)) continue;
                        if (isProtectedBaseMiningArea(candidate)) continue;
                        if (mod.getBlockTracker().unreachable(candidate)) continue;
                        if (!mod.getChunkTracker().isChunkLoaded(candidate)) continue;
                        if (!withinTravelBudget(mod, WorldHelper.toVec3d(candidate))) continue;
                        if (!mod.getBlockTracker().blockIsValid(candidate, _blocks)) continue;
                        if (!WorldHelper.canBreak(mod, candidate)) continue;
                        double sq = candidate.getSquaredDistance(origin);
                        if (sq < bestSq) {
                            bestSq = sq;
                            best = candidate;
                        }
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        @Override
        protected Vec3d getOriginPos(Belfegor mod) {
            return mod.getPlayer().getPos();
        }

        @Override
        protected Task onTick(Belfegor mod) {
            // Lock only once we are close enough to actually work the block.
            // Locking as soon as a far target was selected made the bot ignore a
            // much closer resource discovered while Baritone was still travelling.
            if (_miningPos != null
                    && mod.getPlayer() != null
                    && _miningPos.isWithinDistance(mod.getPlayer().getPos(), 6.0)) {
                lockTarget();
            } else {
                unlockTarget();
            }

            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                _progressChecker.reset();
            }
            if (_miningPos != null && !_progressChecker.check(mod)) {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
                mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2);
                _blacklist.add(_miningPos);
                _miningPos = null;
                _progressChecker.reset();
                unlockTarget();
            }
            return super.onTick(mod);
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.reset();
                }
                if (_surplusAnchor == null) {
                    _surplusAnchor = newPos;
                }
                _miningPos = newPos;
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity) {
                _miningPos = null;
                return _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected boolean isValid(Belfegor mod, Object obj) {
            if (obj instanceof BlockPos b) {
                if (RecentPlacedBlockMemory.wasRecentlyPlaced(b)) return false;
                if (isProtectedBaseMiningArea(b)) return false;
                return mod.getBlockTracker().blockIsValid(b, _blocks) && WorldHelper.canBreak(mod, b);
            }
            if (obj instanceof ItemEntity drop) {
                Item item = drop.getStack().getItem();
                if (_targets != null) {
                    for (ItemTarget target : _targets) {
                        if (target.matches(item)) return true;
                    }
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart(Belfegor mod) {
            _progressChecker.reset();
            _miningPos = null;
            _surplusVeinMode = false;
            _surplusAnchor = null;
            _searchOrigin = mod.getPlayer() == null ? null : mod.getPlayer().getPos();
            _cachedWanderTask = null;
        }

        @Override
        protected void onStop(Belfegor mod, Task interruptTask) {

        }

        @Override
        protected Task getWanderTask(Belfegor mod) {
            if (_searchOrigin != null && mod.getPlayer() != null
                    && !withinTravelBudget(mod, mod.getPlayer().getPos())) {
                setDebugState("Returning to resource-search radius before exploring again");
                return new GetToBlockTask(BlockPos.ofFloored(_searchOrigin));
            }
            if (_cachedWanderTask == null || _cachedWanderTask.stopped()) {
                _cachedWanderTask = new TimeoutWanderTask(true);
            }
            return _cachedWanderTask;
        }

        private boolean withinTravelBudget(Belfegor mod, Vec3d position) {
            if (_searchOrigin == null || position == null) return true;
            double maxDistance = mod.getModSettings().getMaxResourceTravelDistance();
            if (maxDistance <= 0 || Double.isInfinite(maxDistance)) return true;
            return _searchOrigin.squaredDistanceTo(position) <= maxDistance * maxDistance;
        }

        public void enableSurplusVeinMode() {
            _surplusVeinMode = true;
            if (_surplusAnchor == null && _miningPos != null) {
                _surplusAnchor = _miningPos;
            }
        }

        public void disableSurplusVeinMode() {
            _surplusVeinMode = false;
            _surplusAnchor = null;
        }

        public boolean hasNearbySurplusVeinBlock(Belfegor mod) {
            return findNearbySurplusVeinBlock(mod).isPresent();
        }

        private Optional<BlockPos> findNearbySurplusVeinBlock(Belfegor mod) {
            if (_surplusAnchor == null) {
                _surplusAnchor = _miningPos;
            }
            if (_surplusAnchor == null || mod.getWorld() == null) {
                return Optional.empty();
            }
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            int radius = 4;
            int vertical = 3;
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                        BlockPos candidate = _surplusAnchor.add(dx, dy, dz);
                        if (_blacklist.contains(candidate)) continue;
                        if (RecentPlacedBlockMemory.wasRecentlyPlaced(candidate)) continue;
                        if (isProtectedBaseMiningArea(candidate)) continue;
                        if (!mod.getChunkTracker().isChunkLoaded(candidate)) continue;
                        if (!mod.getBlockTracker().blockIsValid(candidate, _blocks)) continue;
                        if (!WorldHelper.canBreak(mod, candidate)) continue;
                        double sq = candidate.getSquaredDistance(_surplusAnchor);
                        if (sq < bestSq) {
                            bestSq = sq;
                            best = candidate;
                        }
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        private boolean isProtectedBaseMiningArea(BlockPos pos) {
            if (pos == null) return false;
            String dimension = WorldHelper.getCurrentDimension().name();
            for (BaseMemory.BaseRecord base : BaseMemory.getInstance().getAllBases()) {
                if (base == null) continue;
                if (dimension != null && !dimension.isBlank() && !dimension.equals(base.dimension)) continue;
                int protectRadius = Math.max(0, base.radius) + Math.max(0, base.exteriorClearance) + 6;
                int dx = pos.getX() - base.x;
                int dz = pos.getZ() - base.z;
                if (dx * dx + dz * dz <= protectRadius * protectRadius) {
                    return true;
                }
                // The core-radius guard does not cover connected rooms. Protect
                // every remembered module plus the configured five-block
                // exterior buffer so stockpile/preflight mining cannot consume
                // farm soil, room floors, hall supports, or their foundations.
                int clearance = Math.max(5, Math.max(0, base.exteriorClearance));
                for (BaseMemory.BaseModule module : base.modules) {
                    if (module == null) continue;
                    int minX = module.x - clearance;
                    int maxX = module.x + Math.max(1, module.width) - 1 + clearance;
                    int minZ = module.z - clearance;
                    int maxZ = module.z + Math.max(1, module.depth) - 1 + clearance;
                    if (pos.getX() >= minX && pos.getX() <= maxX
                            && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        public boolean isMining() {
            return _miningPos != null;
        }

        public BlockPos miningPos() {
            return _miningPos;
        }
    }

}
