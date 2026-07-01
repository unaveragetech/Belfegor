package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasks.AbstractDoToClosestObjectTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
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

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets);
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
            Optional<BlockPos> closestBlock = mod.getBlockTracker().getNearestTracking(pos, check -> {
                if (_blacklist.contains(check)) return false;
                if (mod.getBlockTracker().unreachable(check)) return false;
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
                        if (mod.getBlockTracker().unreachable(candidate)) continue;
                        if (!mod.getChunkTracker().isChunkLoaded(candidate)) continue;
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
            // If we're actively mining a block, lock the target so we don't switch
            if (_miningPos != null) {
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
        }

        @Override
        protected void onStop(Belfegor mod, Task interruptTask) {

        }

        @Override
        protected Task getWanderTask(Belfegor mod) {
            if (_cachedWanderTask == null || _cachedWanderTask.stopped()) {
                _cachedWanderTask = new TimeoutWanderTask(true);
            }
            return _cachedWanderTask;
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
