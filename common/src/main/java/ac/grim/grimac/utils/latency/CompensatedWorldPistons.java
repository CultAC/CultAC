package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PistonData;
import ac.grim.grimac.utils.data.PistonPushes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompensatedWorldPistons {
    private static final BlockState AIR_STATE = Block.stateById(0);
    private static final double MAX_PISTON_MOVEMENT_PER_TICK = 0.51D;
    private static final double PISTON_PROGRESS_PER_CLIENT_TICK = 0.5D;
    private static final double PISTON_ENTITY_PUSH_EPSILON = 0.01D;

    private final GrimPlayer player;
    private final CompensatedWorld world;
    private final Set<PistonData> activePistons = new HashSet<>();
    private final Long2ObjectOpenHashMap<MovingPistonState> movingPistonStates = new Long2ObjectOpenHashMap<>();

    CompensatedWorldPistons(GrimPlayer player, CompensatedWorld world) {
        this.player = player;
        this.world = world;
    }

    public Set<PistonData> activePistons() {
        return activePistons;
    }

    public void handleBlockEvent(BlockPos pos, Block block, int triggerType, int triggerData, int transaction) {
        if (triggerType != 0 && triggerType != 1 && triggerType != 2) {
            return;
        }

        Direction eventDirection = Direction.from3DDataValue(triggerData & 7);

        BlockState pistonState = world.getBlockStateAt(pos);
        if (!isPistonBlock(pistonState.getBlock()) || !pistonState.hasProperty(PistonBaseBlock.FACING)) {
            if (!isPistonBlock(block)) {
                return;
            }

            // MCP-Reborn ClientPacketListener#handleBlockEvent queues Level#blockEvent
            // with the packet block, but Level#blockEvent dispatches through the
            // client's current BlockState at the position. When Grim's compensated
            // state is stale around packet/transaction ordering, the only possible
            // vanilla effect is still the piston state identified by this server
            // packet and its encoded direction.
            pistonState = block.defaultBlockState();
            if (pistonState.hasProperty(PistonBaseBlock.FACING)) {
                pistonState = pistonState.setValue(PistonBaseBlock.FACING, eventDirection);
            }
            if (pistonState.hasProperty(PistonBaseBlock.EXTENDED)) {
                pistonState = pistonState.setValue(PistonBaseBlock.EXTENDED, triggerType != 0);
            }
        }
        Direction direction = pistonState.getValue(PistonBaseBlock.FACING);

        boolean extending = triggerType == 0;
        boolean sticky = pistonState.getBlock() == Blocks.STICKY_PISTON;
        PistonStructure structure = PistonStructure.unresolved();
        if (extending) {
            structure = resolvePistonStructure(pos, direction, true);
            if (!structure.resolved()) {
                return;
            }
        } else if (sticky && triggerType == 1) {
            BlockPos pullPos = pos.relative(direction, 2);
            BlockState pullState = world.getBlockStateAt(pullPos);
            if (canStickyPistonPull(pullState, pullPos, direction)) {
                structure = resolvePistonStructure(pos, direction, false);
            }
        }

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        List<BlockPos> movingPositions = new ArrayList<>();
        boolean hasSlimeBlock = false;
        boolean hasHoneyBlock = false;
        if (structure.resolved()) {
            for (BlockPos pushed : structure.toPush()) {
                BlockState pushedState = world.getBlockStateAt(pushed);
                hasSlimeBlock |= pushedState.getBlock() == Blocks.SLIME_BLOCK;
                hasHoneyBlock |= pushedState.getBlock() == Blocks.HONEY_BLOCK;
            }
        }

        applyClientPistonBlockEvent(pos, pistonState, direction, triggerType, triggerData, structure, transaction);

        BlockFace movementDirection = toBlockFace(extending ? direction : direction.getOpposite());

        List<SimpleCollisionBox> retractingSourceFixBoxes = Collections.emptyList();
        if (!extending) {
            addTrackedMovingPistonMovementBoxes(pos, boxes, movingPositions);
            retractingSourceFixBoxes = retractingSourceFixBoxes(pos, direction);
        }

        if (structure.resolved()) {
            Direction nmsMovementDirection = extending ? direction : direction.getOpposite();
            for (BlockPos pushed : structure.toPush()) {
                BlockPos movingPos = pushed.relative(nmsMovementDirection);
                addTrackedMovingPistonMovementBoxes(movingPos, boxes, movingPositions);

                if (!movingPistonStates.containsKey(movingPos.asLong())) {
                    SimpleCollisionBox pushedBox = blockBox(pushed);
                    if (extending) {
                        boxes.add(pushedBox);
                        boxes.add(blockBox(pushed.relative(direction)));
                    } else {
                        boxes.add(pushedBox.expandToCoordinate(
                                movementDirection.getModX(),
                                movementDirection.getModY(),
                                movementDirection.getModZ()));
                    }
                }
            }

            if (extending) {
                BlockPos headPos = pos.relative(direction);
                addTrackedMovingPistonMovementBoxes(headPos, boxes, movingPositions);
                if (!movingPistonStates.containsKey(headPos.asLong())) {
                    boxes.add(blockBox(headPos));
                }
            }
        }

        if (!boxes.isEmpty() || !movingPositions.isEmpty() || !retractingSourceFixBoxes.isEmpty()) {
            // MCP-Reborn ClientPacketListener#handleBlockEvent runs Level#blockEvent
            // on the client thread. PistonBaseBlock#triggerEvent creates
            // PistonMovingBlockEntity instances immediately. On 1.21.5+,
            // ServerboundClientTickEndPacket proves when Level#tickBlockEntities
            // has run after LocalPlayer#sendPosition, so the shove boxes are captured
            // after tick-end and applied to the following movement packet.
            boolean phaseWithClientTickEnd = usesClientTickEndPistonPhase();
            activePistons.add(new PistonData(
                    toBlockFace(direction),
                    movingPositions,
                    phaseWithClientTickEnd ? Collections.emptyList() : boxes,
                    retractingSourceFixBoxes,
                    transaction,
                    extending,
                    hasSlimeBlock,
                    hasHoneyBlock,
                    phaseWithClientTickEnd));
        }
    }

    private void applyClientPistonBlockEvent(BlockPos pos, BlockState sourcePistonState, Direction direction, int triggerType, int triggerData, PistonStructure structure, int transaction) {
        boolean extending = triggerType == 0;
        boolean sticky = sourcePistonState.getBlock() == Blocks.STICKY_PISTON;

        if (extending) {
            applyClientPistonMoveBlocks(pos, direction, true, structure, transaction);
            BlockState pistonState = world.getBlockStateAt(pos);
            if (isPistonBlock(pistonState.getBlock()) && pistonState.hasProperty(PistonBaseBlock.EXTENDED)) {
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), pistonState.setValue(PistonBaseBlock.EXTENDED, true));
            }
            return;
        }

        // MCP-Reborn PistonBaseBlock#triggerEvent sets the source piston to a
        // MOVING_PISTON block entity before optionally pulling a sticky block.
        // MovingPistonBlock#getCollisionShape is empty without that block entity.
        BlockState movingSourceState = Blocks.MOVING_PISTON.defaultBlockState()
                .setValue(MovingPistonBlock.FACING, direction)
                .setValue(MovingPistonBlock.TYPE, sticky ? PistonType.STICKY : PistonType.DEFAULT);
        BlockState movedSourceState = (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON)
                .defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.from3DDataValue(triggerData & 7));
        trackMovingPiston(pos, movingSourceState, movedSourceState, direction, false, true, transaction);
        world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), movingSourceState);

        if (sticky && triggerType == 1 && structure.resolved()) {
            applyClientPistonMoveBlocks(pos, direction, false, structure, transaction);
        } else {
            BlockPos headPos = pos.relative(direction);
            world.updateBlock(headPos.getX(), headPos.getY(), headPos.getZ(), AIR_STATE);
        }
    }

    private void applyClientPistonMoveBlocks(BlockPos pistonPos, Direction direction, boolean extending, PistonStructure structure, int transaction) {
        // MCP-Reborn PistonBaseBlock#moveBlocks stores each pushed block as a
        // MOVING_PISTON at its destination, with the original moved state stored
        // in the PistonMovingBlockEntity. The original positions are then set to
        // air unless they were overwritten by another moving piston destination.
        if (!extending) {
            BlockPos headPos = pistonPos.relative(direction);
            if (world.getBlockStateAt(headPos).getBlock() == Blocks.PISTON_HEAD) {
                world.updateBlock(headPos.getX(), headPos.getY(), headPos.getZ(), AIR_STATE);
            }
        }

        Map<BlockPos, BlockState> originals = new HashMap<>();
        List<BlockPos> toPush = structure.toPush();
        List<BlockState> movedStates = new ArrayList<>(toPush.size());
        for (BlockPos pushed : toPush) {
            BlockState pushedState = world.getBlockStateAt(pushed);
            movedStates.add(pushedState);
            originals.put(pushed, pushedState);
        }

        Direction movementDirection = extending ? direction : direction.getOpposite();
        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos destination = toPush.get(i).relative(movementDirection);
            originals.remove(destination);
            BlockState movingState = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, direction);
            trackMovingPiston(destination, movingState, movedStates.get(i), direction, extending, false, transaction);
            world.updateBlock(destination.getX(), destination.getY(), destination.getZ(), movingState);
        }

        if (extending) {
            PistonType pistonType = world.getBlockStateAt(pistonPos).getBlock() == Blocks.STICKY_PISTON ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState pistonHeadState = Blocks.PISTON_HEAD.defaultBlockState()
                    .setValue(PistonHeadBlock.FACING, direction)
                    .setValue(PistonHeadBlock.TYPE, pistonType);
            BlockState movingHeadState = Blocks.MOVING_PISTON.defaultBlockState()
                    .setValue(MovingPistonBlock.FACING, direction)
                    .setValue(MovingPistonBlock.TYPE, pistonType);
            BlockPos headPos = pistonPos.relative(direction);
            originals.remove(headPos);
            trackMovingPiston(headPos, movingHeadState, pistonHeadState, direction, true, true, transaction);
            world.updateBlock(headPos.getX(), headPos.getY(), headPos.getZ(), movingHeadState);
        }

        for (BlockPos original : originals.keySet()) {
            world.updateBlock(original.getX(), original.getY(), original.getZ(), AIR_STATE);
        }
    }

    private void trackMovingPiston(BlockPos pos, BlockState movingState, BlockState movedState, Direction direction, boolean extending, boolean source, int transaction) {
        MovingPistonState state = new MovingPistonState(pos, movedState, direction, extending, source);
        movingPistonStates.put(pos.asLong(), state);
        world.markRecentClientCollisionChange(pos);
    }

    public boolean hasMovingPistonCollision(SimpleCollisionBox queryBox) {
        for (long key : movingPistonStates.keySet()) {
            if (blockBox(BlockPos.of(key)).isIntersected(queryBox)) {
                return true;
            }
        }

        return false;
    }

    public List<SimpleCollisionBox> getDynamicBlockCollisionUncertaintyBoxes(SimpleCollisionBox queryBox) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();

        for (long key : movingPistonStates.keySet()) {
            BlockPos pos = BlockPos.of(key);
            MovingPistonState movingPiston = movingPistonStates.get(key);
            for (SimpleCollisionBox box : movingPiston.getCollisionUncertaintyBoxes(world, pos)) {
                if (box.isIntersected(queryBox)) {
                    boxes.add(box);
                }
            }
        }

        return boxes;
    }

    void handleBlockStateApplied(BlockPos pos, BlockState state) {
        if (state.getBlock() != Blocks.MOVING_PISTON) {
            movingPistonStates.remove(pos.asLong());
        }
    }

    void removeSentBefore(int transactionId) {
        activePistons.removeIf(data -> data.getTransaction() < transactionId);
    }

    void tickClientTickEnd() {
        advanceClientPistonTick();
    }

    private void advanceClientPistonTick() {
        captureClientTickPistonMovement();
        activePistons.removeIf(PistonData::tickIfGuaranteedFinished);
        tickMovingPistonStates();
    }

    void clear() {
        activePistons.clear();
        movingPistonStates.clear();
    }

    SimpleCollisionBox createPistonQueryBox(SimulationContext context) {
        SimpleCollisionBox playerBox = context.getFromMaximumExtent();
        if (!usesClientTickEndPistonPhase()) {
            playerBox.union(context.getToMaximumExtent());
            playerBox.expand(0.2);
        }
        return playerBox;
    }

    public boolean mayHavePushedOnLastClientTick(SimpleCollisionBox playerBox) {
        // These boxes describe the block-entity phase captured at tick-end.
        // Query before a later position correction replaces the player's box;
        // that correction preserves Entity#onGround even when it removes the shove.
        return usesClientTickEndPistonPhase()
                && !tickPlayerInPistonPushingArea(playerBox.copy()).getPistonPush().isEmpty();
    }

    PistonPushes tickPlayerInPistonPushingArea(SimpleCollisionBox playerBox) {
        Set<BlockFace> launches = new HashSet<>();
        SimpleCollisionBox pistonPushes = new SimpleCollisionBox();

        for (PistonData data : activePistons) {
            if (!data.canAffectMovement()) {
                continue;
            }

            double movementAmount = 0.0D;
            for (SimpleCollisionBox box : data.boxes) {
                if (playerBox.isIntersected(box)) {
                    movementAmount = Math.max(movementAmount, movementNeededToExit(box, data.getMovementDirection(), playerBox));
                }
            }

            BlockFace direction = data.getMovementDirection();
            boolean movementIntersects = movementAmount > 0.0D;
            boolean sourceFixIntersects = intersectsAny(playerBox, data.retractingSourceFixBoxes);

            if (!movementIntersects && !sourceFixIntersects) {
                continue;
            }

            if (movementIntersects) {
                double shove = Math.min(movementAmount, PISTON_PROGRESS_PER_CLIENT_TICK) + PISTON_ENTITY_PUSH_EPSILON;
                shove = Math.min(shove, MAX_PISTON_MOVEMENT_PER_TICK);
                playerBox.expand(
                        Math.abs(direction.getModX()) * shove,
                        Math.abs(direction.getModY()) * shove,
                        Math.abs(direction.getModZ()) * shove);
                // MCP-Reborn PistonMovingBlockEntity#moveCollidedEntities computes
                // getMovement(...) against the swept piston area, adds 0.01, and
                // Entity#limitPistonMovement caps the total axis delta to +/-0.51.
                // With ServerboundClientTickEndPacket, the start AABB is the exact
                // entity box before the client block-entity tick that can shove it.
                unionSignedAxis(pistonPushes, direction, shove);
            }

            if (movementIntersects && data.hasSlimeBlock) {
                launches.add(direction);
            }

            if (sourceFixIntersects) {
                BlockFace sourceFixDirection = direction.getOppositeFace();
                playerBox.expand(Math.abs(sourceFixDirection.getModX()), Math.abs(sourceFixDirection.getModY()), Math.abs(sourceFixDirection.getModZ()));
                unionSignedAxis(pistonPushes, sourceFixDirection, MAX_PISTON_MOVEMENT_PER_TICK);
            }
        }

        return new PistonPushes(pistonPushes, pistonPushes, new SimpleCollisionBox(), launches);
    }

    private static void unionSignedAxis(SimpleCollisionBox box, BlockFace direction, double limit) {
        if (direction.getModX() != 0) {
            box.unionX(direction.getModX() * limit);
        }
        if (direction.getModY() != 0) {
            box.unionY(direction.getModY() * limit);
        }
        if (direction.getModZ() != 0) {
            box.unionZ(direction.getModZ() * limit);
        }
    }

    private static double movementNeededToExit(SimpleCollisionBox area, BlockFace direction, SimpleCollisionBox entityBox) {
        return switch (direction) {
            case EAST -> area.maxX - entityBox.minX;
            case WEST -> entityBox.maxX - area.minX;
            case UP -> area.maxY - entityBox.minY;
            case DOWN -> entityBox.maxY - area.minY;
            case SOUTH -> area.maxZ - entityBox.minZ;
            case NORTH -> entityBox.maxZ - area.minZ;
            default -> 0.0D;
        };
    }

    private static boolean intersectsAny(SimpleCollisionBox playerBox, List<SimpleCollisionBox> boxes) {
        for (SimpleCollisionBox box : boxes) {
            if (playerBox.isIntersected(box)) {
                return true;
            }
        }
        return false;
    }

    private boolean usesClientTickEndPistonPhase() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5);
    }

    private static List<SimpleCollisionBox> retractingSourceFixBoxes(BlockPos sourcePos, Direction pistonDirection) {
        // MCP-Reborn PistonBaseBlock#triggerEvent creates a retracting source
        // PistonMovingBlockEntity, and PistonMovingBlockEntity#moveCollidedEntities
        // calls fixEntityWithinPistonBase only after the source head has collided.
        // That fix tests the source base block and moves the entity in the
        // piston-facing direction, opposite the normal retract movement.
        return Collections.singletonList(blockBox(sourcePos).expandToCoordinate(
                pistonDirection.getStepX() * MAX_PISTON_MOVEMENT_PER_TICK,
                pistonDirection.getStepY() * MAX_PISTON_MOVEMENT_PER_TICK,
                pistonDirection.getStepZ() * MAX_PISTON_MOVEMENT_PER_TICK));
    }

    private void addTrackedMovingPistonMovementBoxes(BlockPos movingPos, List<SimpleCollisionBox> boxes, List<BlockPos> movingPositions) {
        MovingPistonState movingPiston = movingPistonStates.get(movingPos.asLong());
        if (movingPiston != null) {
            movingPositions.add(movingPos.immutable());
            boxes.addAll(movingPiston.getAllMovementCollisionBoxes(world, movingPos));
        }
    }

    private List<SimpleCollisionBox> collectCurrentMovingPistonMovementBoxes(List<BlockPos> movingPositions) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        for (BlockPos movingPos : movingPositions) {
            MovingPistonState movingPiston = movingPistonStates.get(movingPos.asLong());
            if (movingPiston == null) {
                continue;
            }

            boxes.addAll(movingPiston.getCurrentMovementCollisionBoxes(world, movingPos));
        }
        return boxes;
    }

    private void tickMovingPistonStates() {
        List<MovingPistonState> finished = new ArrayList<>();
        movingPistonStates.long2ObjectEntrySet().removeIf(entry -> {
            MovingPistonState state = entry.getValue();
            if (world.getBlockStateAt(state.pos()).getBlock() != Blocks.MOVING_PISTON) {
                return true;
            }
            boolean remove = state.tickIfGuaranteedFinished();
            if (remove) {
                finished.add(state);
            }
            return remove;
        });

        for (MovingPistonState state : finished) {
            finishMovingPistonState(state);
        }
    }

    private void finishMovingPistonState(MovingPistonState state) {
        BlockPos pos = state.pos();
        if (world.getBlockStateAt(pos).getBlock() != Blocks.MOVING_PISTON) {
            return;
        }

        // MCP-Reborn PistonMovingBlockEntity#tick removes the client block entity
        // after five death ticks, then replaces MOVING_PISTON with the moved
        // state after neighbor-shape resolution, or the raw movedState when that
        // resolution turns it to air. Grim does not expose a full LevelAccessor
        // here, but it must not keep an empty MOVING_PISTON collision shape after
        // the real client has restored the moved block.
        BlockState finalState = state.movedState;
        if (finalState.hasProperty(BlockStateProperties.WATERLOGGED)
                && finalState.getValue(BlockStateProperties.WATERLOGGED)) {
            finalState = finalState.setValue(BlockStateProperties.WATERLOGGED, false);
        }

        world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), finalState);
    }

    private void captureClientTickPistonMovement() {
        for (PistonData data : activePistons) {
            if (data.shouldCaptureMovementOnClientTickEnd()) {
                data.setBoxes(collectCurrentMovingPistonMovementBoxes(data.getMovingPositions()));
            }
        }
    }

    private PistonStructure resolvePistonStructure(BlockPos pistonPos, Direction pistonDirection, boolean extending) {
        List<BlockPos> toPush = new ArrayList<>();
        List<BlockPos> toDestroy = new ArrayList<>();
        Direction pushDirection = extending ? pistonDirection : pistonDirection.getOpposite();
        BlockPos startPos = extending ? pistonPos.relative(pistonDirection) : pistonPos.relative(pistonDirection, 2);
        BlockPos retractingHeadPos = extending ? null : pistonPos.relative(pistonDirection);
        BlockState startState = getPistonStructureStateAt(startPos, retractingHeadPos);

        if (!isPistonPushable(startState, startPos, pushDirection, false, pistonDirection)) {
            if (extending && startState.getPistonPushReaction() == PushReaction.DESTROY) {
                toDestroy.add(startPos);
                return new PistonStructure(true, toPush, toDestroy);
            }
            return PistonStructure.unresolved();
        }

        if (!addPistonBlockLine(pistonPos, pushDirection, pistonDirection, startPos, pushDirection, retractingHeadPos, toPush, toDestroy)) {
            return PistonStructure.unresolved();
        }

        for (int i = 0; i < toPush.size(); i++) {
            BlockPos blockPos = toPush.get(i);
            if (isStickyPistonBlock(getPistonStructureStateAt(blockPos, retractingHeadPos))
                    && !addPistonBranchingBlocks(pistonPos, pushDirection, pistonDirection, blockPos, retractingHeadPos, toPush, toDestroy)) {
                return PistonStructure.unresolved();
            }
        }

        return new PistonStructure(true, toPush, toDestroy);
    }

    private boolean addPistonBlockLine(BlockPos pistonPos, Direction pushDirection, Direction pistonDirection,
                                       BlockPos start, Direction direction, BlockPos retractingHeadPos,
                                       List<BlockPos> toPush, List<BlockPos> toDestroy) {
        BlockState blockState = getPistonStructureStateAt(start, retractingHeadPos);
        if (blockState.isAir()) {
            return true;
        }
        if (!isPistonPushable(blockState, start, pushDirection, false, direction)) {
            return true;
        }
        if (start.equals(pistonPos) || toPush.contains(start)) {
            return true;
        }

        int blocksToAdd = 1;
        if (blocksToAdd + toPush.size() > 12) {
            return false;
        }

        while (isStickyPistonBlock(blockState)) {
            BlockPos behind = start.relative(pushDirection.getOpposite(), blocksToAdd);
            BlockState previousState = blockState;
            blockState = getPistonStructureStateAt(behind, retractingHeadPos);
            if (blockState.isAir()
                    || !canPistonStickToEachOther(previousState, blockState)
                    || !isPistonPushable(blockState, behind, pushDirection, false, pushDirection.getOpposite())
                    || behind.equals(pistonPos)) {
                break;
            }

            if (++blocksToAdd + toPush.size() > 12) {
                return false;
            }
        }

        int added = 0;
        for (int i = blocksToAdd - 1; i >= 0; i--) {
            toPush.add(start.relative(pushDirection.getOpposite(), i));
            added++;
        }

        int forward = 1;
        while (true) {
            BlockPos ahead = start.relative(pushDirection, forward);
            int collisionIndex = toPush.indexOf(ahead);
            if (collisionIndex > -1) {
                reorderPistonListAtCollision(toPush, added, collisionIndex);

                for (int i = 0; i <= collisionIndex + added; i++) {
                    BlockPos blockPos = toPush.get(i);
                    if (isStickyPistonBlock(getPistonStructureStateAt(blockPos, retractingHeadPos))
                            && !addPistonBranchingBlocks(pistonPos, pushDirection, pistonDirection, blockPos, retractingHeadPos, toPush, toDestroy)) {
                        return false;
                    }
                }

                return true;
            }

            blockState = getPistonStructureStateAt(ahead, retractingHeadPos);
            if (blockState.isAir()) {
                return true;
            }

            if (!isPistonPushable(blockState, ahead, pushDirection, true, pushDirection) || ahead.equals(pistonPos)) {
                return false;
            }

            if (blockState.getPistonPushReaction() == PushReaction.DESTROY) {
                toDestroy.add(ahead);
                return true;
            }

            if (toPush.size() >= 12) {
                return false;
            }

            toPush.add(ahead);
            added++;
            forward++;
        }
    }

    private boolean addPistonBranchingBlocks(BlockPos pistonPos, Direction pushDirection, Direction pistonDirection,
                                             BlockPos fromPos, BlockPos retractingHeadPos,
                                             List<BlockPos> toPush, List<BlockPos> toDestroy) {
        BlockState blockState = getPistonStructureStateAt(fromPos, retractingHeadPos);

        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == pushDirection.getAxis()) {
                continue;
            }

            BlockPos branch = fromPos.relative(direction);
            BlockState branchState = getPistonStructureStateAt(branch, retractingHeadPos);
            if (canPistonStickToEachOther(branchState, blockState)
                    && !addPistonBlockLine(pistonPos, pushDirection, pistonDirection, branch, direction, retractingHeadPos, toPush, toDestroy)) {
                return false;
            }
        }

        return true;
    }

    private BlockState getPistonStructureStateAt(BlockPos pos, BlockPos retractingHeadPos) {
        BlockState state = world.getBlockStateAt(pos);
        // MCP-Reborn PistonBaseBlock#moveBlocks removes the retracting piston
        // head before constructing PistonStructureResolver.
        if (retractingHeadPos != null && pos.equals(retractingHeadPos) && state.getBlock() == Blocks.PISTON_HEAD) {
            return AIR_STATE;
        }
        return state;
    }

    private boolean canStickyPistonPull(BlockState state, BlockPos pos, Direction pistonDirection) {
        // MCP-Reborn PistonBaseBlock#triggerEvent only calls moveBlocks on sticky
        // retraction when the block two ahead is non-air, pushable backward, and
        // has NORMAL piston reaction or is itself a piston block.
        return !state.isAir()
                && isPistonPushable(state, pos, pistonDirection.getOpposite(), false, pistonDirection)
                && (state.getPistonPushReaction() == PushReaction.NORMAL || state.getBlock() == Blocks.PISTON || state.getBlock() == Blocks.STICKY_PISTON);
    }

    private boolean isPistonPushable(BlockState state, BlockPos pos, Direction direction, boolean allowDestroyable, Direction connectionDirection) {
        // MCP-Reborn PistonBaseBlock#isPushable. World-border checks are server
        // world checks; for a tracked player near the arena, the dimension height
        // limit is the exact client-relevant bound Grim has locally.
        if (pos.getY() < world.getMinHeight() || pos.getY() > world.getMaxHeight()) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        if (state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == Blocks.CRYING_OBSIDIAN || state.getBlock() == Blocks.RESPAWN_ANCHOR || state.getBlock() == Blocks.REINFORCED_DEEPSLATE) {
            return false;
        }
        if (direction == Direction.DOWN && pos.getY() == world.getMinHeight()) {
            return false;
        }
        if (direction == Direction.UP && pos.getY() == world.getMaxHeight()) {
            return false;
        }

        if (state.getBlock() != Blocks.PISTON && state.getBlock() != Blocks.STICKY_PISTON) {
            if (state.getDestroySpeed(world, pos) == -1.0F) {
                return false;
            }

            return switch (state.getPistonPushReaction()) {
                case BLOCK -> false;
                case DESTROY -> allowDestroyable;
                case PUSH_ONLY -> direction == connectionDirection;
                default -> !state.hasBlockEntity();
            };
        }

        return (!state.hasProperty(PistonBaseBlock.EXTENDED) || !state.getValue(PistonBaseBlock.EXTENDED)) && !state.hasBlockEntity();
    }

    private static void reorderPistonListAtCollision(List<BlockPos> toPush, int blocksAdded, int collisionPos) {
        List<BlockPos> beforeCollision = new ArrayList<>(toPush.subList(0, collisionPos));
        List<BlockPos> justAdded = new ArrayList<>(toPush.subList(toPush.size() - blocksAdded, toPush.size()));
        List<BlockPos> collided = new ArrayList<>(toPush.subList(collisionPos, toPush.size() - blocksAdded));
        toPush.clear();
        toPush.addAll(beforeCollision);
        toPush.addAll(justAdded);
        toPush.addAll(collided);
    }

    private static boolean isPistonBlock(Block block) {
        return block == Blocks.PISTON || block == Blocks.STICKY_PISTON;
    }

    private static boolean isStickyPistonBlock(BlockState state) {
        return state.getBlock() == Blocks.SLIME_BLOCK || state.getBlock() == Blocks.HONEY_BLOCK;
    }

    private static boolean canPistonStickToEachOther(BlockState state1, BlockState state2) {
        if (state1.getBlock() == Blocks.HONEY_BLOCK && state2.getBlock() == Blocks.SLIME_BLOCK) {
            return false;
        }
        if (state1.getBlock() == Blocks.SLIME_BLOCK && state2.getBlock() == Blocks.HONEY_BLOCK) {
            return false;
        }
        return isStickyPistonBlock(state1) || isStickyPistonBlock(state2);
    }

    private static SimpleCollisionBox blockBox(BlockPos pos) {
        return new SimpleCollisionBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, true);
    }

    private static BlockFace toBlockFace(Direction direction) {
        return switch (direction) {
            case DOWN -> BlockFace.DOWN;
            case UP -> BlockFace.UP;
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case WEST -> BlockFace.WEST;
            case EAST -> BlockFace.EAST;
        };
    }

    private record PistonStructure(boolean resolved, List<BlockPos> toPush, List<BlockPos> toDestroy) {
        private static PistonStructure unresolved() {
            return new PistonStructure(false, Collections.emptyList(), Collections.emptyList());
        }
    }

    public VoxelShape getMovingPistonCollisionShape(BlockPos pos) {
        MovingPistonState movingPiston = movingPistonStates.get(pos.asLong());
        if (movingPiston == null) {
            return Shapes.empty();
        }
        return movingPiston.getCollisionShape(world, pos);
    }

    private static final class MovingPistonState {
        private static final int GUARANTEED_FINISHED_TICKS = 8;

        private final BlockPos pos;
        private final BlockState movedState;
        private final Direction direction;
        private final boolean extending;
        private final boolean source;
        private int clientBlockEntityTicks;

        private MovingPistonState(BlockPos pos, BlockState movedState, Direction direction, boolean extending, boolean source) {
            this.pos = pos.immutable();
            this.movedState = movedState;
            this.direction = direction;
            this.extending = extending;
            this.source = source;
        }

        private BlockPos pos() {
            return pos;
        }

        private boolean tickIfGuaranteedFinished() {
            return ++clientBlockEntityTicks >= GUARANTEED_FINISHED_TICKS;
        }

        private float progress() {
            return Math.min(1.0F, clientBlockEntityTicks * 0.5F);
        }

        private float extendedProgress() {
            float progress = progress();
            return extending ? progress - 1.0F : 1.0F - progress;
        }

        private float extendedProgress(float progress) {
            return extending ? progress - 1.0F : 1.0F - progress;
        }

        private Direction movementDirection() {
            return extending ? direction : direction.getOpposite();
        }

        private List<SimpleCollisionBox> getAllMovementCollisionBoxes(BlockGetter level, BlockPos pos) {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            addMovementCollisionBoxes(level, pos, 0.0F, 0.5D, boxes);
            addMovementCollisionBoxes(level, pos, 0.5F, 0.5D, boxes);
            return boxes;
        }

        private List<SimpleCollisionBox> getCurrentMovementCollisionBoxes(BlockGetter level, BlockPos pos) {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            float progress = progress();
            if (progress < 1.0F) {
                // MCP-Reborn PistonMovingBlockEntity#tick calls
                // moveCollidedEntities with progress + 0.5 before storing that
                // progress. At ServerboundClientTickEndPacket, Grim has just
                // observed the end of that client tick, so this one sweep is the
                // only shove that can appear in the next movement packet.
                addMovementCollisionBoxes(level, pos, progress, 0.5D, boxes);
            }
            return boxes;
        }

        private List<SimpleCollisionBox> getCollisionUncertaintyBoxes(BlockGetter level, BlockPos pos) {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            if (!extending && source && movedState.getBlock() instanceof PistonBaseBlock) {
                for (AABB box : movedState.setValue(PistonBaseBlock.EXTENDED, true).getCollisionShape(level, pos).toAabbs()) {
                    boxes.add(toSimpleCollisionBox(box.move(pos)));
                }
            }

            addCollisionShapeSweep(level, pos, 0.0F, boxes);
            if (source) {
                addCollisionShapeSweep(level, pos, 0.5F, boxes);
            }
            return boxes;
        }

        private void addCollisionShapeSweep(BlockGetter level, BlockPos pos, float shapeProgress, List<SimpleCollisionBox> boxes) {
            VoxelShape shape = getCollisionRelatedBlockState(shapeProgress).getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                return;
            }

            for (AABB localBox : shape.toAabbs()) {
                AABB start = moveByPositionAndProgress(pos, localBox, 0.0F);
                AABB end = moveByPositionAndProgress(pos, localBox, 1.0F);
                boxes.add(union(toSimpleCollisionBox(start), end));
            }
        }

        private void addMovementCollisionBoxes(BlockGetter level, BlockPos pos, float progress, double deltaProgress, List<SimpleCollisionBox> boxes) {
            BlockState collisionState = getCollisionRelatedBlockState(progress);
            VoxelShape shape = collisionState.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                return;
            }

            Direction movementDirection = movementDirection();
            for (AABB localBox : shape.toAabbs()) {
                AABB movedBox = moveByPositionAndProgress(pos, localBox, progress);
                boxes.add(toSimpleCollisionBox(getMovementArea(movedBox, movementDirection, deltaProgress)));
            }
        }

        private BlockState getCollisionRelatedBlockState(float progress) {
            if (!extending && source && movedState.getBlock() instanceof PistonBaseBlock) {
                return Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.SHORT, progress > 0.25F)
                        .setValue(PistonHeadBlock.TYPE, movedState.getBlock() == Blocks.STICKY_PISTON ? PistonType.STICKY : PistonType.DEFAULT)
                        .setValue(PistonHeadBlock.FACING, movedState.getValue(PistonBaseBlock.FACING));
            }
            return movedState;
        }

        private AABB moveByPositionAndProgress(BlockPos pos, AABB box, float progress) {
            float extendedProgress = extendedProgress(progress);
            return box.move(
                    pos.getX() + direction.getStepX() * extendedProgress,
                    pos.getY() + direction.getStepY() * extendedProgress,
                    pos.getZ() + direction.getStepZ() * extendedProgress);
        }

        private static AABB getMovementArea(AABB box, Direction direction, double amount) {
            double step = amount * direction.getAxisDirection().getStep();
            double min = Math.min(step, 0.0D);
            double max = Math.max(step, 0.0D);
            return switch (direction) {
                case WEST -> new AABB(box.minX + min, box.minY, box.minZ, box.minX + max, box.maxY, box.maxZ);
                case EAST -> new AABB(box.maxX + min, box.minY, box.minZ, box.maxX + max, box.maxY, box.maxZ);
                case DOWN -> new AABB(box.minX, box.minY + min, box.minZ, box.maxX, box.minY + max, box.maxZ);
                case UP -> new AABB(box.minX, box.maxY + min, box.minZ, box.maxX, box.maxY + max, box.maxZ);
                case NORTH -> new AABB(box.minX, box.minY, box.minZ + min, box.maxX, box.maxY, box.minZ + max);
                case SOUTH -> new AABB(box.minX, box.minY, box.maxZ + min, box.maxX, box.maxY, box.maxZ + max);
            };
        }

        private static SimpleCollisionBox toSimpleCollisionBox(AABB box) {
            return new SimpleCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        private static SimpleCollisionBox union(SimpleCollisionBox envelope, AABB box) {
            SimpleCollisionBox next = toSimpleCollisionBox(box);
            return envelope == null ? next : envelope.union(next);
        }

        private VoxelShape getCollisionShape(BlockGetter level, BlockPos pos) {
            return getCollisionShape(level, pos, progress());
        }

        private VoxelShape getCollisionShape(BlockGetter level, BlockPos pos, float progress) {
            // MCP-Reborn MovingPistonBlock#getCollisionShape delegates to
            // PistonMovingBlockEntity#getCollisionShape. The entity stores the
            // moved state and progress; a MOVING_PISTON block without that
            // entity is intentionally empty.
            VoxelShape sourceShape = Shapes.empty();
            if (!extending && source && movedState.getBlock() instanceof PistonBaseBlock) {
                sourceShape = movedState.setValue(PistonBaseBlock.EXTENDED, true).getCollisionShape(level, pos);
            }

            BlockState collisionState;
            if (source) {
                collisionState = Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.FACING, direction)
                        .setValue(PistonHeadBlock.SHORT, extending != 1.0F - progress < 0.25F)
                        .setValue(PistonHeadBlock.TYPE, movedState.getBlock() == Blocks.STICKY_PISTON ? PistonType.STICKY : PistonType.DEFAULT);
            } else {
                collisionState = movedState;
            }

            float extendedProgress = extendedProgress(progress);
            double x = direction.getStepX() * extendedProgress;
            double y = direction.getStepY() * extendedProgress;
            double z = direction.getStepZ() * extendedProgress;
            return Shapes.or(sourceShape, collisionState.getCollisionShape(level, pos).move(x, y, z));
        }
    }
}
