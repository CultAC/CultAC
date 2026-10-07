package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.LegacyPistonCollision;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonData;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompensatedWorldPistons {
    private static final int AIR_STATE = BlockIds.AIR.defaultState();
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double MAX_PISTON_MOVEMENT_PER_TICK = 0.51D;
    private static final double PISTON_PROGRESS_PER_CLIENT_TICK = 0.5D;
    private static final double PISTON_ENTITY_PUSH_EPSILON = 0.01D;

    private final CultPlayer player;
    private final CompensatedWorld world;
    private final Set<PistonData> activePistons = new HashSet<>();
    private final Long2ObjectOpenHashMap<MovingPistonState> movingPistonStates = new Long2ObjectOpenHashMap<>();
    private final List<LegacyPistonMovement> lastLegacyPistonMovements = new ArrayList<>();

    CompensatedWorldPistons(CultPlayer player, CompensatedWorld world) {
        this.player = player;
        this.world = world;
    }

    public Set<PistonData> activePistons() {
        return activePistons;
    }

    public void handleBlockEvent(BlockPos pos, int blockId, int triggerType, int triggerData, int transaction) {
        if (triggerType != 0 && triggerType != 1 && triggerType != 2) {
            return;
        }

        Direction eventDirection = Direction.from3DDataValue(triggerData & 7);

        int pistonState = world.getBlockStateIdAt(pos);
        if (!isPistonBlock(pistonState) || !BlockProps.FACING.has(pistonState)) {
            if (blockId < 0
                    || blockId >= REGISTRY.blocks().size()
                    || !isPistonBlock(REGISTRY.blocks().get(blockId).defaultState())) {
                return;
            }

            // MCP-Reborn ClientPacketListener#handleBlockEvent queues Level#blockEvent
            // with the packet block, but Level#blockEvent dispatches through the
            // client's current block state at the position. When Cult's compensated
            // state is stale around packet/transaction ordering, the only possible
            // vanilla effect is still the piston state identified by this server
            // packet and its encoded direction.
            pistonState = REGISTRY.blocks().get(blockId).defaultState();
            if (BlockProps.FACING.has(pistonState)) {
                pistonState = BlockProps.FACING.with(pistonState, eventDirection.get3DDataValue());
            }
            if (BlockProps.EXTENDED.has(pistonState)) {
                pistonState = BlockProps.EXTENDED.with(pistonState, triggerType != 0);
            }
        }
        Direction direction = Direction.from3DDataValue(BlockProps.FACING.value(pistonState));

        boolean extending = triggerType == 0;
        boolean sticky = BlockIds.is(pistonState, BlockIds.STICKY_PISTON);
        PistonStructure structure = PistonStructure.unresolved();
        if (extending) {
            structure = resolvePistonStructure(pos, direction, true);
            if (!structure.resolved()) {
                return;
            }
        } else if (sticky && triggerType == 1) {
            BlockPos pullPos = pos.relative(direction, 2);
            int pullState = world.getBlockStateIdAt(pullPos);
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
                int pushedState = world.getBlockStateIdAt(pushed);
                hasSlimeBlock |= BlockIds.is(pushedState, BlockIds.SLIME_BLOCK);
                hasHoneyBlock |= BlockIds.is(pushedState, BlockIds.HONEY_BLOCK);
            }
        }

        applyClientPistonBlockEvent(pos, pistonState, direction, triggerType, triggerData, structure, transaction);

        Direction movementDirection = extending ? direction : direction.getOpposite();

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
                                movementDirection.getModX(), movementDirection.getModY(), movementDirection.getModZ()));
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
                    direction,
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

    private void applyClientPistonBlockEvent(
            BlockPos pos,
            int sourcePistonState,
            Direction direction,
            int triggerType,
            int triggerData,
            PistonStructure structure,
            int transaction) {
        boolean extending = triggerType == 0;
        boolean sticky = BlockIds.is(sourcePistonState, BlockIds.STICKY_PISTON);

        if (extending) {
            applyClientPistonMoveBlocks(pos, direction, true, structure, transaction);
            int pistonState = world.getBlockStateIdAt(pos);
            if (isPistonBlock(pistonState) && BlockProps.EXTENDED.has(pistonState)) {
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), BlockProps.EXTENDED.with(pistonState, true));
            }
            return;
        }

        // MCP-Reborn PistonBaseBlock#triggerEvent sets the source piston to a
        // MOVING_PISTON block entity before optionally pulling a sticky block.
        // MovingPistonBlock#getCollisionShape is empty without that block entity.
        int movingSourceState = BlockProps.PISTON_TYPE.with(
                BlockProps.FACING.with(BlockIds.MOVING_PISTON.defaultState(), direction.get3DDataValue()),
                sticky ? 1 : 0);
        int movedSourceState = BlockProps.FACING.with(
                (sticky ? BlockIds.STICKY_PISTON : BlockIds.PISTON).defaultState(),
                Direction.from3DDataValue(triggerData & 7).get3DDataValue());
        trackMovingPiston(pos, movingSourceState, movedSourceState, direction, false, true, transaction);
        world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), movingSourceState);

        if (sticky && triggerType == 1 && structure.resolved()) {
            applyClientPistonMoveBlocks(pos, direction, false, structure, transaction);
        } else {
            BlockPos headPos = pos.relative(direction);
            world.updateBlock(headPos.getX(), headPos.getY(), headPos.getZ(), AIR_STATE);
        }
    }

    private void applyClientPistonMoveBlocks(
            BlockPos pistonPos, Direction direction, boolean extending, PistonStructure structure, int transaction) {
        // MCP-Reborn PistonBaseBlock#moveBlocks stores each pushed block as a
        // MOVING_PISTON at its destination, with the original moved state stored
        // in the PistonMovingBlockEntity. The original positions are then set to
        // air unless they were overwritten by another moving piston destination.
        if (!extending) {
            BlockPos headPos = pistonPos.relative(direction);
            if (BlockIds.is(world.getBlockStateIdAt(headPos), BlockIds.PISTON_HEAD)) {
                world.updateBlock(headPos.getX(), headPos.getY(), headPos.getZ(), AIR_STATE);
            }
        }

        Map<BlockPos, Integer> originals = new HashMap<>();
        List<BlockPos> toPush = structure.toPush();
        List<Integer> movedStates = new ArrayList<>(toPush.size());
        for (BlockPos pushed : toPush) {
            int pushedState = world.getBlockStateIdAt(pushed);
            movedStates.add(pushedState);
            originals.put(pushed, pushedState);
        }

        Direction movementDirection = extending ? direction : direction.getOpposite();
        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos destination = toPush.get(i).relative(movementDirection);
            originals.remove(destination);
            int movingState = BlockProps.FACING.with(BlockIds.MOVING_PISTON.defaultState(), direction.get3DDataValue());
            trackMovingPiston(destination, movingState, movedStates.get(i), direction, extending, false, transaction);
            world.updateBlock(destination.getX(), destination.getY(), destination.getZ(), movingState);
        }

        if (extending) {
            int pistonType = BlockIds.is(world.getBlockStateIdAt(pistonPos), BlockIds.STICKY_PISTON) ? 1 : 0;
            int pistonHeadState = BlockProps.PISTON_TYPE.with(
                    BlockProps.FACING.with(BlockIds.PISTON_HEAD.defaultState(), direction.get3DDataValue()),
                    pistonType);
            int movingHeadState = BlockProps.PISTON_TYPE.with(
                    BlockProps.FACING.with(BlockIds.MOVING_PISTON.defaultState(), direction.get3DDataValue()),
                    pistonType);
            BlockPos headPos = pistonPos.relative(direction);
            originals.remove(headPos);
            trackMovingPiston(headPos, movingHeadState, pistonHeadState, direction, true, true, transaction);
            world.updateBlock(headPos.getX(), headPos.getY(), headPos.getZ(), movingHeadState);
        }

        for (BlockPos original : originals.keySet()) {
            world.updateBlock(original.getX(), original.getY(), original.getZ(), AIR_STATE);
        }
    }

    private void trackMovingPiston(
            BlockPos pos,
            int movingState,
            int movedState,
            Direction direction,
            boolean extending,
            boolean source,
            int transaction) {
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

    void handleBlockStateApplied(BlockPos pos, int state) {
        if (!ac.cult.blocksim.data.BlockIds.is(state, ac.cult.blocksim.data.BlockIds.MOVING_PISTON)) {
            movingPistonStates.remove(pos.asLong());
        }
    }

    void forgetChunk(int x, int z) {
        // ClientLevel.unload and LevelChunk.replaceWithPacketData clear live
        // block entities immediately. Completed movement passes keep their
        // existing tick owner; unloading does not undo a previous shove.
        movingPistonStates
                .values()
                .removeIf(state -> state.pos().getX() >> 4 == x && state.pos().getZ() >> 4 == z);
    }

    void removeSentBefore(int transactionId) {
        activePistons.removeIf(data -> data.getTransaction() < transactionId);
    }

    void tickClientTickEnd() {
        advanceClientPistonTick();
    }

    public boolean usesLegacyCollision() {
        return !player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    public void onLegacyMovementTick() {
        if (usesLegacyCollision()) advanceClientPistonTick();
    }

    public void onLegacyPlayerTeleport() {
        // ViaRewind resolves relative positions before sending S08 with flags=0.
        // NetHandlerPlayClient#handlePlayerPosLook then resets all motion axes.
        // Neither that teleport nor its immediate echo ticks the tile entities.
        if (usesLegacyCollision()) lastLegacyPistonMovements.clear();
    }

    private void advanceClientPistonTick() {
        if (usesLegacyCollision()) captureLegacyPistonMovement();
        captureClientTickPistonMovement();
        activePistons.removeIf(PistonData::tickIfGuaranteedFinished);
        tickMovingPistonStates();
    }

    void clear() {
        activePistons.clear();
        movingPistonStates.clear();
        lastLegacyPistonMovements.clear();
    }

    SimpleCollisionBox createPistonQueryBox(SimulationContext context) {
        SimpleCollisionBox playerBox = context.getFromMaximumExtent();
        if (reportsAfterBlockEntities()) {
            // 26.3 Minecraft#tick sends changes after travel and block entities.
            // Keep the existing envelope query, including where travel can end.
            playerBox.union(context.getToMaximumExtent());
        } else if (!usesClientTickEndPistonPhase() && !usesLegacyCollision()) {
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
                && !tickPlayerInPistonPushingArea(playerBox.copy())
                        .getPistonPush()
                        .isEmpty();
    }

    public PistonPushes tickPlayerInPistonPushingArea(SimpleCollisionBox playerBox) {
        if (usesLegacyCollision()) return legacyPistonPushes(playerBox);
        Set<Direction> launches = new HashSet<>();
        SimpleCollisionBox pistonPushes = new SimpleCollisionBox();
        boolean currentPass = reportsAfterBlockEntities();

        for (PistonData data : activePistons) {
            if (!currentPass && !data.canAffectMovement()) {
                continue;
            }

            // Read the current pass for 26.3 without advancing it. Older clients
            // report before block entities and consume the last completed pass.
            // In both cases tick-end is the sole owner of piston clock advancement.
            List<SimpleCollisionBox> movementBoxes =
                    currentPass ? collectCurrentMovingPistonMovementBoxes(data.getMovingPositions()) : data.boxes;
            if (currentPass && movementBoxes.isEmpty()) continue;
            double movementAmount = 0.0D;
            for (SimpleCollisionBox box : movementBoxes) {
                if (playerBox.isIntersected(box)) {
                    movementAmount =
                            Math.max(movementAmount, movementNeededToExit(box, data.getMovementDirection(), playerBox));
                }
            }

            Direction direction = data.getMovementDirection();
            boolean movementIntersects = movementAmount > 0.0D;
            boolean sourceFixIntersects = !data.retractingSourceFixBoxes.isEmpty()
                    && (!currentPass || hasCurrentRetractingSource(data))
                    && intersectsAny(playerBox, data.retractingSourceFixBoxes);

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
                Direction sourceFixDirection = direction.getOppositeFace();
                playerBox.expand(
                        Math.abs(sourceFixDirection.getModX()),
                        Math.abs(sourceFixDirection.getModY()),
                        Math.abs(sourceFixDirection.getModZ()));
                unionSignedAxis(pistonPushes, sourceFixDirection, MAX_PISTON_MOVEMENT_PER_TICK);
            }
        }

        return new PistonPushes(pistonPushes, pistonPushes, new SimpleCollisionBox(), launches);
    }

    private boolean hasCurrentRetractingSource(PistonData data) {
        // A surviving pulled block does not run its removed source's
        // fixEntityWithinPistonBase callback (PistonMovingBlockEntity#tick).
        for (BlockPos pos : data.getMovingPositions()) {
            MovingPistonState state = movingPistonStates.get(pos.asLong());
            if (state != null && state.source && !state.extending && state.progress() < 1.0F) return true;
        }
        return false;
    }

    private void captureLegacyPistonMovement() {
        lastLegacyPistonMovements.clear();
        // World#updateEntities runs local travel/sendPosition before tile
        // entities. Preserve this pass independently of the live tiles: the
        // third update launches/pushes once more, then removes the tile.
        for (MovingPistonState state : movingPistonStates.values()) {
            float progress = state.progress();
            if (!state.extending && progress < 1.0F) continue;
            CollisionBox collision = LegacyPistonCollision.pushing(
                    player,
                    state.movedState,
                    state.pos,
                    state.direction,
                    state.extending,
                    Math.min(1.0F, progress + 0.5F));
            if (!(collision instanceof SimpleCollisionBox box)) continue;
            boolean launch = state.extending
                    && BlockIds.is(state.movedState, BlockIds.SLIME_BLOCK)
                    && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8);
            lastLegacyPistonMovements.add(
                    new LegacyPistonMovement(box, state.direction, progress >= 1.0F ? 0.25D : 0.5625D, launch));
        }
    }

    private PistonPushes legacyPistonPushes(SimpleCollisionBox playerBox) {
        Set<Direction> launches = new HashSet<>();
        SimpleCollisionBox pushes = new SimpleCollisionBox();
        for (LegacyPistonMovement movement : lastLegacyPistonMovements) {
            if (!playerBox.isIntersected(movement.box)) continue;
            Direction direction = movement.direction;
            if (movement.launch) {
                // 1.8 assigns motion on the facing axis; this branch does not
                // call moveEntity and contributes no position-only shove.
                launches.add(direction);
            } else {
                // 1.7/1.8 moveEntity receives .5 + .0625 during extension,
                // and .25 on completion (also when retracting, still facing
                // forward). There is no modern .01 epsilon or .51 axis cap.
                double x = direction.getModX() * movement.amount;
                double y = direction.getModY() * movement.amount;
                double z = direction.getModZ() * movement.amount;
                pushes.minX += Math.min(0, x);
                pushes.maxX += Math.max(0, x);
                pushes.minY += Math.min(0, y);
                pushes.maxY += Math.max(0, y);
                pushes.minZ += Math.min(0, z);
                pushes.maxZ += Math.max(0, z);
                playerBox.expandToCoordinate(x, y, z);
            }
        }
        PistonPushes result = new PistonPushes(pushes, pushes, new SimpleCollisionBox(), launches);
        result.setPistonMovementPhased(true);
        return result;
    }

    private record LegacyPistonMovement(SimpleCollisionBox box, Direction direction, double amount, boolean launch) {}

    public boolean reportsAfterBlockEntities() {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    private static void unionSignedAxis(SimpleCollisionBox box, Direction direction, double limit) {
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

    private static double movementNeededToExit(
            SimpleCollisionBox area, Direction direction, SimpleCollisionBox entityBox) {
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
        // 1.21.2/1.21.3 ClientLevel#tickEntities ticks block entities after
        // LocalPlayer#tick sends movement, then Minecraft#tick sends tick-end.
        // Their next movement therefore reports the same completed piston sweep
        // as 1.21.5+. Preserve the existing Bedrock phase selection.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)
                || (!player.isBedrockMovement()
                        && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private static List<SimpleCollisionBox> retractingSourceFixBoxes(BlockPos sourcePos, Direction pistonDirection) {
        // MCP-Reborn PistonBaseBlock#triggerEvent creates a retracting source
        // PistonMovingBlockEntity, and PistonMovingBlockEntity#moveCollidedEntities
        // calls fixEntityWithinPistonBase only after the source head has collided.
        // That fix tests the source base block and moves the entity in the
        // piston-facing direction, opposite the normal retract movement.
        return Collections.singletonList(blockBox(sourcePos)
                .expandToCoordinate(
                        pistonDirection.getStepX() * MAX_PISTON_MOVEMENT_PER_TICK,
                        pistonDirection.getStepY() * MAX_PISTON_MOVEMENT_PER_TICK,
                        pistonDirection.getStepZ() * MAX_PISTON_MOVEMENT_PER_TICK));
    }

    private void addTrackedMovingPistonMovementBoxes(
            BlockPos movingPos, List<SimpleCollisionBox> boxes, List<BlockPos> movingPositions) {
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
            if (!BlockIds.is(world.getBlockStateIdAt(state.pos()), BlockIds.MOVING_PISTON)) {
                return true;
            }
            boolean remove = state.tickIfGuaranteedFinished(usesLegacyCollision());
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
        if (!BlockIds.is(world.getBlockStateIdAt(pos), BlockIds.MOVING_PISTON)) {
            return;
        }

        // Legacy TileEntityPiston#update restores the stored state immediately.
        // Modern PistonMovingBlockEntity#tick removes the client block entity
        // after five death ticks, then replaces MOVING_PISTON with the moved
        // state after neighbor-shape resolution, or the raw movedState when that
        // resolution turns it to air. Cult does not expose a full LevelAccessor
        // here, but it must not keep an empty MOVING_PISTON collision shape after
        // the real client has restored the moved block.
        int finalState = state.movedState;
        if (BlockProps.WATERLOGGED.has(finalState) && BlockProps.WATERLOGGED.booleanValue(finalState)) {
            finalState = BlockProps.WATERLOGGED.with(finalState, false);
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
        int startState = getPistonStructureStateAt(startPos, retractingHeadPos);

        if (!isPistonPushable(startState, startPos, pushDirection, false, pistonDirection)) {
            if (extending && REGISTRY.facts(startState).pushReaction() == StateFacts.PushReaction.POPPED) {
                toDestroy.add(startPos);
                return new PistonStructure(true, toPush, toDestroy);
            }
            return PistonStructure.unresolved();
        }

        if (!addPistonBlockLine(
                pistonPos,
                pushDirection,
                pistonDirection,
                startPos,
                pushDirection,
                retractingHeadPos,
                toPush,
                toDestroy)) {
            return PistonStructure.unresolved();
        }

        for (int i = 0; i < toPush.size(); i++) {
            BlockPos blockPos = toPush.get(i);
            if (isStickyPistonBlock(getPistonStructureStateAt(blockPos, retractingHeadPos))
                    && !addPistonBranchingBlocks(
                            pistonPos,
                            pushDirection,
                            pistonDirection,
                            blockPos,
                            retractingHeadPos,
                            toPush,
                            toDestroy)) {
                return PistonStructure.unresolved();
            }
        }

        return new PistonStructure(true, toPush, toDestroy);
    }

    private boolean addPistonBlockLine(
            BlockPos pistonPos,
            Direction pushDirection,
            Direction pistonDirection,
            BlockPos start,
            Direction direction,
            BlockPos retractingHeadPos,
            List<BlockPos> toPush,
            List<BlockPos> toDestroy) {
        int blockState = getPistonStructureStateAt(start, retractingHeadPos);
        if (REGISTRY.facts(blockState).has(StateFacts.AIR)) {
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
            int previousState = blockState;
            blockState = getPistonStructureStateAt(behind, retractingHeadPos);
            if (REGISTRY.facts(blockState).has(StateFacts.AIR)
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
                            && !addPistonBranchingBlocks(
                                    pistonPos,
                                    pushDirection,
                                    pistonDirection,
                                    blockPos,
                                    retractingHeadPos,
                                    toPush,
                                    toDestroy)) {
                        return false;
                    }
                }

                return true;
            }

            blockState = getPistonStructureStateAt(ahead, retractingHeadPos);
            if (REGISTRY.facts(blockState).has(StateFacts.AIR)) {
                return true;
            }

            if (!isPistonPushable(blockState, ahead, pushDirection, true, pushDirection) || ahead.equals(pistonPos)) {
                return false;
            }

            if (REGISTRY.facts(blockState).pushReaction() == StateFacts.PushReaction.POPPED) {
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

    private boolean addPistonBranchingBlocks(
            BlockPos pistonPos,
            Direction pushDirection,
            Direction pistonDirection,
            BlockPos fromPos,
            BlockPos retractingHeadPos,
            List<BlockPos> toPush,
            List<BlockPos> toDestroy) {
        int blockState = getPistonStructureStateAt(fromPos, retractingHeadPos);

        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == pushDirection.getAxis()) {
                continue;
            }

            BlockPos branch = fromPos.relative(direction);
            int branchState = getPistonStructureStateAt(branch, retractingHeadPos);
            if (canPistonStickToEachOther(branchState, blockState)
                    && !addPistonBlockLine(
                            pistonPos,
                            pushDirection,
                            pistonDirection,
                            branch,
                            direction,
                            retractingHeadPos,
                            toPush,
                            toDestroy)) {
                return false;
            }
        }

        return true;
    }

    private int getPistonStructureStateAt(BlockPos pos, BlockPos retractingHeadPos) {
        int state = world.getBlockStateIdAt(pos);
        // MCP-Reborn PistonBaseBlock#moveBlocks removes the retracting piston
        // head before constructing PistonStructureResolver.
        if (retractingHeadPos != null && pos.equals(retractingHeadPos) && BlockIds.is(state, BlockIds.PISTON_HEAD)) {
            return AIR_STATE;
        }
        return state;
    }

    private boolean canStickyPistonPull(int state, BlockPos pos, Direction pistonDirection) {
        // MCP-Reborn PistonBaseBlock#triggerEvent only calls moveBlocks on sticky
        // retraction when the block two ahead is non-air, pushable backward, and
        // has NORMAL piston reaction or is itself a piston block.
        return !REGISTRY.facts(state).has(StateFacts.AIR)
                && isPistonPushable(state, pos, pistonDirection.getOpposite(), false, pistonDirection)
                && (REGISTRY.facts(state).pushReaction() == StateFacts.PushReaction.PUSH_PULL
                        || BlockIds.is(state, BlockIds.PISTON)
                        || BlockIds.is(state, BlockIds.STICKY_PISTON));
    }

    private boolean isPistonPushable(
            int state, BlockPos pos, Direction direction, boolean allowDestroyable, Direction connectionDirection) {
        // MCP-Reborn PistonBaseBlock#isPushable. World-border checks are server
        // world checks; for a tracked player near the arena, the dimension height
        // limit is the exact client-relevant bound Cult has locally.
        if (pos.getY() < world.getMinHeight() || pos.getY() > world.getMaxHeight()) {
            return false;
        }
        if (REGISTRY.facts(state).has(StateFacts.AIR)) {
            return true;
        }
        if (BlockIds.is(state, BlockIds.OBSIDIAN)
                || BlockIds.is(state, BlockIds.CRYING_OBSIDIAN)
                || BlockIds.is(state, BlockIds.RESPAWN_ANCHOR)
                || BlockIds.is(state, BlockIds.REINFORCED_DEEPSLATE)) {
            return false;
        }
        if (direction == Direction.DOWN && pos.getY() == world.getMinHeight()) {
            return false;
        }
        if (direction == Direction.UP && pos.getY() == world.getMaxHeight()) {
            return false;
        }

        if (!BlockIds.is(state, BlockIds.PISTON) && !BlockIds.is(state, BlockIds.STICKY_PISTON)) {
            if (REGISTRY.facts(state).destroyTime() == -1.0F) {
                return false;
            }

            return switch (REGISTRY.facts(state).pushReaction()) {
                case IMMOVEABLE -> false;
                case POPPED -> allowDestroyable;
                case PUSH -> direction == connectionDirection;
                default -> !REGISTRY.facts(state).has(StateFacts.BLOCK_ENTITY);
            };
        }

        return (!BlockProps.EXTENDED.has(state) || !BlockProps.EXTENDED.booleanValue(state))
                && !REGISTRY.facts(state).has(StateFacts.BLOCK_ENTITY);
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

    private static boolean isPistonBlock(int state) {
        return BlockIds.is(state, BlockIds.PISTON) || BlockIds.is(state, BlockIds.STICKY_PISTON);
    }

    private static boolean isStickyPistonBlock(int state) {
        return BlockIds.is(state, BlockIds.SLIME_BLOCK) || BlockIds.is(state, BlockIds.HONEY_BLOCK);
    }

    private static boolean canPistonStickToEachOther(int state1, int state2) {
        if (BlockIds.is(state1, BlockIds.HONEY_BLOCK) && BlockIds.is(state2, BlockIds.SLIME_BLOCK)) {
            return false;
        }
        if (BlockIds.is(state1, BlockIds.SLIME_BLOCK) && BlockIds.is(state2, BlockIds.HONEY_BLOCK)) {
            return false;
        }
        return isStickyPistonBlock(state1) || isStickyPistonBlock(state2);
    }

    private static SimpleCollisionBox blockBox(BlockPos pos) {
        return new SimpleCollisionBox(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, true);
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
        if (usesLegacyCollision()) {
            CollisionBox collision = getLegacyMovingPistonCollisionBox(pos);
            if (!(collision instanceof SimpleCollisionBox box)
                    || box.maxX <= box.minX
                    || box.maxY <= box.minY
                    || box.maxZ <= box.minZ) {
                return Shapes.empty();
            }
            // Volume-only consumers use the same legacy bounds. Movement itself
            // takes the direct AABB path so a degenerate face is not discarded.
            return Shapes.create(
                    box.minX - pos.getX(),
                    box.minY - pos.getY(),
                    box.minZ - pos.getZ(),
                    box.maxX - pos.getX(),
                    box.maxY - pos.getY(),
                    box.maxZ - pos.getZ());
        }
        return movingPiston.getCollisionShape(world, pos);
    }

    public CollisionBox getLegacyMovingPistonCollisionBox(BlockPos pos) {
        MovingPistonState state = movingPistonStates.get(pos.asLong());
        return state == null
                ? NoCollisionBox.INSTANCE
                : LegacyPistonCollision.movement(
                        player, state.movedState, pos, state.direction, state.extending, state.previousProgress());
    }

    private static final class MovingPistonState {
        private static final int GUARANTEED_FINISHED_TICKS = 8;

        private final BlockPos pos;
        private final int movedState;
        private final Direction direction;
        private final boolean extending;
        private final boolean source;
        private int clientBlockEntityTicks;

        private MovingPistonState(
                BlockPos pos, int movedState, Direction direction, boolean extending, boolean source) {
            this.pos = pos.immutable();
            this.movedState = movedState;
            this.direction = direction;
            this.extending = extending;
            this.source = source;
        }

        private BlockPos pos() {
            return pos;
        }

        private boolean tickIfGuaranteedFinished(boolean legacy) {
            // 1.8 TileEntityPiston#update restores the block on the third pass:
            // (previous,current) = (0,.5), (.5,1), then completion. It has no death ticks.
            return ++clientBlockEntityTicks >= (legacy ? 3 : GUARANTEED_FINISHED_TICKS);
        }

        private float progress() {
            return Math.min(1.0F, clientBlockEntityTicks * 0.5F);
        }

        private float previousProgress() {
            return Math.min(1.0F, Math.max(0, clientBlockEntityTicks - 1) * 0.5F);
        }

        private float extendedProgress(float progress) {
            return extending ? progress - 1.0F : 1.0F - progress;
        }

        private Direction movementDirection() {
            return extending ? direction : direction.getOpposite();
        }

        private List<SimpleCollisionBox> getAllMovementCollisionBoxes(CompensatedWorld level, BlockPos pos) {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            addMovementCollisionBoxes(level, pos, 0.0F, 0.5D, boxes);
            addMovementCollisionBoxes(level, pos, 0.5F, 0.5D, boxes);
            return boxes;
        }

        private List<SimpleCollisionBox> getCurrentMovementCollisionBoxes(CompensatedWorld level, BlockPos pos) {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            float progress = progress();
            if (progress < 1.0F) {
                // MCP-Reborn PistonMovingBlockEntity#tick calls
                // moveCollidedEntities with progress + 0.5 before storing that
                // progress. At ServerboundClientTickEndPacket, Cult has just
                // observed the end of that client tick, so this one sweep is the
                // only shove that can appear in the next movement packet.
                addMovementCollisionBoxes(level, pos, progress, 0.5D, boxes);
            }
            return boxes;
        }

        private List<SimpleCollisionBox> getCollisionUncertaintyBoxes(CompensatedWorld level, BlockPos pos) {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            if (!extending && source && BlockFamilies.PISTON_BASE.test(movedState)) {
                for (SimpleCollisionBox box : NativeBlockCollisionHelper.toBoxes(
                        level.geometry().collision(BlockProps.EXTENDED.with(movedState, true), pos))) {
                    boxes.add(box.move(pos.getX(), pos.getY(), pos.getZ()));
                }
            }

            addCollisionShapeSweep(level, pos, 0.0F, boxes);
            if (source) {
                addCollisionShapeSweep(level, pos, 0.5F, boxes);
            }
            return boxes;
        }

        private void addCollisionShapeSweep(
                CompensatedWorld level, BlockPos pos, float shapeProgress, List<SimpleCollisionBox> boxes) {
            VoxelShape shape = level.geometry().collision(getCollisionRelatedBlockState(shapeProgress), pos);
            if (shape.isEmpty()) {
                return;
            }

            for (SimpleCollisionBox localBox : NativeBlockCollisionHelper.toBoxes(shape)) {
                SimpleCollisionBox start = moveByPositionAndProgress(pos, localBox, 0.0F);
                SimpleCollisionBox end = moveByPositionAndProgress(pos, localBox, 1.0F);
                boxes.add(union(start, end));
            }
        }

        private void addMovementCollisionBoxes(
                CompensatedWorld level,
                BlockPos pos,
                float progress,
                double deltaProgress,
                List<SimpleCollisionBox> boxes) {
            int collisionState = getCollisionRelatedBlockState(progress);
            VoxelShape shape = level.geometry().collision(collisionState, pos);
            if (shape.isEmpty()) {
                return;
            }

            Direction movementDirection = movementDirection();
            for (SimpleCollisionBox localBox : NativeBlockCollisionHelper.toBoxes(shape)) {
                SimpleCollisionBox movedBox = moveByPositionAndProgress(pos, localBox, progress);
                boxes.add(getMovementArea(movedBox, movementDirection, deltaProgress));
            }
        }

        private int getCollisionRelatedBlockState(float progress) {
            if (!extending && source && BlockFamilies.PISTON_BASE.test(movedState)) {
                return BlockProps.FACING.with(
                        BlockProps.PISTON_TYPE.with(
                                BlockProps.SHORT.with(BlockIds.PISTON_HEAD.defaultState(), progress > 0.25F),
                                BlockIds.is(movedState, BlockIds.STICKY_PISTON) ? 1 : 0),
                        BlockProps.FACING.value(movedState));
            }
            return movedState;
        }

        private SimpleCollisionBox moveByPositionAndProgress(BlockPos pos, SimpleCollisionBox box, float progress) {
            float extendedProgress = extendedProgress(progress);
            return box.move(
                    pos.getX() + direction.getStepX() * extendedProgress,
                    pos.getY() + direction.getStepY() * extendedProgress,
                    pos.getZ() + direction.getStepZ() * extendedProgress);
        }

        private static SimpleCollisionBox getMovementArea(SimpleCollisionBox box, Direction direction, double amount) {
            double step = amount * direction.getAxisDirection().getStep();
            double min = Math.min(step, 0.0D);
            double max = Math.max(step, 0.0D);
            return switch (direction) {
                case WEST ->
                    SimpleCollisionBox.between(box.minX + min, box.minY, box.minZ, box.minX + max, box.maxY, box.maxZ);
                case EAST ->
                    SimpleCollisionBox.between(box.maxX + min, box.minY, box.minZ, box.maxX + max, box.maxY, box.maxZ);
                case DOWN ->
                    SimpleCollisionBox.between(box.minX, box.minY + min, box.minZ, box.maxX, box.minY + max, box.maxZ);
                case UP ->
                    SimpleCollisionBox.between(box.minX, box.maxY + min, box.minZ, box.maxX, box.maxY + max, box.maxZ);
                case NORTH ->
                    SimpleCollisionBox.between(box.minX, box.minY, box.minZ + min, box.maxX, box.maxY, box.minZ + max);
                case SOUTH ->
                    SimpleCollisionBox.between(box.minX, box.minY, box.maxZ + min, box.maxX, box.maxY, box.maxZ + max);
            };
        }

        private static SimpleCollisionBox union(SimpleCollisionBox envelope, SimpleCollisionBox box) {
            return envelope == null ? box.copy() : envelope.union(box);
        }

        private VoxelShape getCollisionShape(CompensatedWorld level, BlockPos pos) {
            return getCollisionShape(level, pos, progress());
        }

        private VoxelShape getCollisionShape(CompensatedWorld level, BlockPos pos, float progress) {
            return level.geometry().movingPiston(movedState, pos, direction, extending, source, progress);
        }
    }
}
