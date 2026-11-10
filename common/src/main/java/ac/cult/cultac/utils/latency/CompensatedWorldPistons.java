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
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonData;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
    private static final ReplayScope NO_REPLAY = new ReplayScope(null, null, null, null, null, null);
    private static final java.util.Comparator<MovingPistonState> TICKER_ORDER =
            java.util.Comparator.comparingLong(state -> state.tickerOrder);

    private final CultPlayer player;
    private final CompensatedWorld world;
    private final Set<PistonData> activePistons = new HashSet<>();
    private final Long2ObjectOpenHashMap<MovingPistonState> movingPistonStates = new Long2ObjectOpenHashMap<>();
    // A ClientBlockEntityPass replay's piston progress and NOCLIP direction; null outside a replay.
    private Long2FloatOpenHashMap replayProgress;
    private Direction replayNoClip;
    // A replay's ClientBlockEntityPass.Final entries by position, and those its ticker order has passed; null when the
    // replayed pass finalizes none.
    private Long2ObjectOpenHashMap<ClientBlockEntityPass.Final> replayFinals;
    private LongOpenHashSet replayFinalized;
    // The other positions those finals' settling writes, each with its writes in pass order; null with replayFinals.
    private Long2ObjectOpenHashMap<List<SettleWrite>> replayWrites;

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
            // Level#blockEvent dispatches through the block currently at this position. A piston
            // named by the packet cannot create a mover when that block is air, stone or MOVING_PISTON.
            if (!player.isBedrockMovement()) return;
            if (blockId < 0
                    || blockId >= REGISTRY.blocks().size()
                    || !isPistonBlock(REGISTRY.blocks().get(blockId).defaultState())) {
                return;
            }

            // Preserve Bedrock's existing packet-state fallback. Java above uses the current block,
            // matching Level#blockEvent rather than reconstructing a piston from the packet's block ID.
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
        PistonStructure structure;
        if (extending) {
            // MCP-Reborn PistonBaseBlock#moveBlocks resolves an extension before it writes anything.
            structure = resolvePistonStructure(pos, direction, true);
            if (!structure.resolved()) {
                return;
            }
            ClientPistonLevel level = ClientPistonLevel.of(world);
            applyClientPistonMoveBlocks(level, pos, direction, true, structure);
            // PistonBaseBlock#triggerEvent then extends the base with a full update.
            int base = level.stateAt(pos);
            if (isPistonBlock(base) && BlockProps.EXTENDED.has(base)) {
                level.setBlock(pos, BlockProps.EXTENDED.with(base, true), 67);
            }
        } else {
            structure = applyClientPistonRetraction(
                    ClientPistonLevel.of(world), pos, direction, sticky, triggerType, triggerData);
        }

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        List<BlockPos> movingPositions = new ArrayList<>();
        boolean hasSlimeBlock = structure.slime();

        Direction movementDirection = extending ? direction : direction.getOpposite();

        List<SimpleCollisionBox> retractingSourceFixBoxes = Collections.emptyList();
        if (!extending) {
            addTrackedMovingPistonMovementBoxes(pos, boxes, movingPositions);
            retractingSourceFixBoxes = retractingSourceFixBoxes(pos, direction);
        }

        if (structure.resolved()) {
            for (BlockPos pushed : structure.toPush()) {
                BlockPos movingPos = pushed.relative(movementDirection);
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

        // The Bedrock engine's push envelope reads these; Java movement replays the moving pistons themselves.
        if (player.isBedrockMovement()
                && (!boxes.isEmpty() || !movingPositions.isEmpty() || !retractingSourceFixBoxes.isEmpty())) {
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
                    phaseWithClientTickEnd));
        }
    }

    /**
     * MCP-Reborn PistonBaseBlock#triggerEvent's retraction as the client runs it, in its order; returns what it pulled,
     * or an unresolved structure.
     */
    private PistonStructure applyClientPistonRetraction(
            ClientPistonLevel level,
            BlockPos pos,
            Direction direction,
            boolean sticky,
            int triggerType,
            int triggerData) {
        BlockPos headPos = pos.relative(direction);
        // A block entity still moving at the head is finalTicked first.
        finalTickMovingPiston(level, headPos);

        // The source piston becomes a MOVING_PISTON whose block entity (MovingPistonBlock#getCollisionShape is empty
        // without one) is set before the base's neighbors update their shapes against it.
        int movingSourceState = BlockProps.PISTON_TYPE.with(
                BlockProps.FACING.with(BlockIds.MOVING_PISTON.defaultState(), direction.get3DDataValue()),
                sticky ? 1 : 0);
        int movedSourceState = BlockProps.FACING.with(
                (sticky ? BlockIds.STICKY_PISTON : BlockIds.PISTON).defaultState(),
                Direction.from3DDataValue(triggerData & 7).get3DDataValue());
        // 276 from 1.21.5, 20 before: UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS (256) is read only on the server.
        level.setBlock(pos, movingSourceState, 276);
        trackMovingPiston(pos, movedSourceState, direction, false, true);
        level.updateNeighbourShapes(movingSourceState, pos);

        if (!sticky) {
            level.removeBlock(headPos);
            return PistonStructure.unresolved();
        }
        BlockPos pullPos = pos.relative(direction, 2);
        int pullState = level.stateAt(pullPos);
        MovingPistonState pulling = movingPistonStates.get(pullPos.asLong());
        if (pulling != null
                && BlockIds.is(pullState, BlockIds.MOVING_PISTON)
                && pulling.direction == direction
                && pulling.extending) {
            // A block two ahead still extending this way is finalTicked in place instead of pulled.
            finalTickMovingPiston(level, pullPos);
            return PistonStructure.unresolved();
        }
        if (triggerType != 1 || !canStickyPistonPull(pullState, pullPos, direction)) {
            level.removeBlock(headPos);
            return PistonStructure.unresolved();
        }

        // PistonBaseBlock#moveBlocks removes the head before it resolves what the piston pulls.
        if (BlockIds.is(level.stateAt(headPos), BlockIds.PISTON_HEAD)) level.setBlock(headPos, AIR_STATE, 276);
        PistonStructure structure = resolvePistonStructure(pos, direction, false);
        if (structure.resolved()) applyClientPistonMoveBlocks(level, pos, direction, false, structure);
        return structure;
    }

    /**
     * PistonMovingBlockEntity#finalTick, which on the client always settles: it removes its block entity, then sets its
     * MOVING_PISTON to air for a source piece and otherwise to its moved state updated from the neighbors' shapes.
     */
    private void finalTickMovingPiston(ClientPistonLevel level, BlockPos pos) {
        MovingPistonState state = movingPistonStates.remove(pos.asLong());
        if (state == null || !BlockIds.is(level.stateAt(pos), BlockIds.MOVING_PISTON)) return;
        int settled = state.source ? AIR_STATE : level.updateFromNeighbourShapes(state.movedState, pos);
        level.setBlock(pos, settled, 3);
    }

    /**
     * The client-only PistonMovingBlockEntity#tick past its fifth death tick, after it removed its block entity:
     * returns the state it leaves at its position.
     */
    private static int settleMovingPiston(ClientPistonLevel level, MovingPistonState state) {
        BlockPos pos = state.pos();
        if (!BlockIds.is(level.stateAt(pos), BlockIds.MOVING_PISTON)) return level.stateAt(pos);
        int settled = level.updateFromNeighbourShapes(state.movedState, pos);
        if (REGISTRY.facts(settled).has(StateFacts.AIR)) {
            // 340 from 1.21.5, 84 before: neither updates shapes. Block#updateOrDestroy destroys only on the server.
            level.setBlock(pos, state.movedState, 340);
        } else {
            if (BlockProps.WATERLOGGED.has(settled) && BlockProps.WATERLOGGED.booleanValue(settled)) {
                settled = BlockProps.WATERLOGGED.with(settled, false);
            }
            level.setBlock(pos, settled, 67);
        }
        return level.stateAt(pos);
    }

    /**
     * MCP-Reborn PistonBaseBlock#moveBlocks after it resolved {@code structure}, as the client runs it. Each pushed
     * block becomes a MOVING_PISTON at its destination, whose neighbors update their shapes before its block entity
     * exists; the vacated positions turn to air without shape updates, which then run for each in HashMap order.
     */
    private void applyClientPistonMoveBlocks(
            ClientPistonLevel level,
            BlockPos pistonPos,
            Direction direction,
            boolean extending,
            PistonStructure structure) {
        List<BlockPos> toPush = structure.toPush();
        List<BlockPos> toDestroy = structure.toDestroy();
        // Maps.newHashMap with BlockPos keys, whose hash is Vec3i's: the iteration order is the client's.
        Map<BlockPos, Integer> originals = new HashMap<>();
        List<Integer> movedStates = new ArrayList<>(toPush.size());
        for (BlockPos pushed : toPush) {
            int pushedState = level.stateAt(pushed);
            movedStates.add(pushedState);
            originals.put(pushed, pushedState);
        }

        int[] destroyedStates = new int[toDestroy.size()];
        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            destroyedStates[i] = level.stateAt(toDestroy.get(i));
            level.setBlock(toDestroy.get(i), AIR_STATE, 18);
        }

        Direction movementDirection = extending ? direction : direction.getOpposite();
        int movingState = BlockProps.FACING.with(BlockIds.MOVING_PISTON.defaultState(), direction.get3DDataValue());
        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos destination = toPush.get(i).relative(movementDirection);
            originals.remove(destination);
            // 324 from 1.21.5, 68 before; Level#setBlockEntity follows the write.
            level.setBlock(destination, movingState, 324);
            trackMovingPiston(destination, movedStates.get(i), direction, extending, false);
        }

        if (extending) {
            int pistonType = BlockIds.is(level.stateAt(pistonPos), BlockIds.STICKY_PISTON) ? 1 : 0;
            int pistonHeadState = BlockProps.PISTON_TYPE.with(
                    BlockProps.FACING.with(BlockIds.PISTON_HEAD.defaultState(), direction.get3DDataValue()),
                    pistonType);
            int movingHeadState = BlockProps.PISTON_TYPE.with(
                    BlockProps.FACING.with(BlockIds.MOVING_PISTON.defaultState(), direction.get3DDataValue()),
                    pistonType);
            BlockPos headPos = pistonPos.relative(direction);
            originals.remove(headPos);
            level.setBlock(headPos, movingHeadState, 324);
            trackMovingPiston(headPos, pistonHeadState, direction, true, true);
        }

        for (BlockPos original : originals.keySet()) {
            level.setBlock(original, AIR_STATE, 82);
        }
        for (Map.Entry<BlockPos, Integer> original : originals.entrySet()) {
            level.updateIndirectNeighbourShapes(original.getValue(), original.getKey());
            level.updateNeighbourShapes(AIR_STATE, original.getKey());
            level.updateIndirectNeighbourShapes(AIR_STATE, original.getKey());
        }
        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            level.updateIndirectNeighbourShapes(destroyedStates[i], toDestroy.get(i));
        }
    }

    private void trackMovingPiston(
            BlockPos pos, int movedState, Direction direction, boolean extending, boolean source) {
        // Level#setBlockEntity -> LevelChunk#updateBlockEntityTicker appends the new entity's ticker.
        MovingPistonState state = new MovingPistonState(
                pos, movedState, direction, extending, source, world.nextBlockEntityTickerOrder());
        movingPistonStates.put(pos.asLong(), state);
    }

    void receiveBlockEntityData(WorldPackets.BlockEntityUpdate packet) {
        if (player.isBedrockMovement() || !packet.type().equals("minecraft:piston") || packet.tag() == null) return;
        var state = movingPistonStates.get(packet.position().asLong());
        // MovingPistonBlock.newBlockEntity returns null. Updates and chunk tags
        // cannot create one; ClientPacketListener only loads an existing entity.
        if (state == null || !BlockIds.is(world.getBlockStateIdAt(packet.position()), BlockIds.MOVING_PISTON)) return;
        var fields = ClientPistonFields.load(player, packet.tag());
        state.movedState = fields.movedState();
        state.direction = fields.direction();
        state.extending = fields.extending();
        state.source = fields.source();
        state.progress = fields.progress();
        // Vanilla loadAdditional does not reset deathTicks.
    }

    public void addDynamicBlockCollisionUncertaintyBoxes(SimpleCollisionBox queryBox, List<SimpleCollisionBox> boxes) {
        // Java's tick boundary fixes each moving block's progress. Travel collides with that exact shape,
        // then JavaBlockEntityPass replays its shove; a whole-stroke envelope invents extra collisions and
        // can zero the carried velocity even while the player is moving away from the piston.
        if (!player.isBedrockMovement()) return;
        if (movingPistonStates.isEmpty()) return;
        for (long key : movingPistonStates.keySet()) {
            BlockPos pos = BlockPos.of(key);
            MovingPistonState movingPiston = movingPistonStates.get(key);
            for (SimpleCollisionBox box : movingPiston.getCollisionUncertaintyBoxes(world, pos)) {
                if (box.isIntersected(queryBox)) {
                    boxes.add(box);
                }
            }
        }
    }

    void handleBlockStateApplied(BlockPos pos, int state) {
        if (!BlockIds.is(state, BlockIds.MOVING_PISTON)) {
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
        captureClientTickPistonMovement();
        activePistons.removeIf(PistonData::tickIfGuaranteedFinished);
        tickMovingPistonStates();
    }

    void clear() {
        activePistons.clear();
        movingPistonStates.clear();
    }

    /** Whether no piston can push the player, so every push this pass is the empty box. */
    boolean hasNoPushSources() {
        return activePistons.isEmpty();
    }

    SimpleCollisionBox createPistonQueryBox(SimulationContext context) {
        SimpleCollisionBox playerBox = context.getFromMaximumExtent();
        if (!usesClientTickEndPistonPhase()) {
            playerBox.union(context.getToMaximumExtent());
            playerBox.expand(0.2);
        }
        return playerBox;
    }

    /** The Bedrock engine's piston push envelope around {@code playerBox}, which this widens as pushes reach it. */
    SimpleCollisionBox tickPlayerInPistonPushingArea(SimpleCollisionBox playerBox) {
        SimpleCollisionBox pistonPushes = new SimpleCollisionBox();
        for (PistonData data : activePistons) {
            if (!data.canAffectMovement()) continue;
            Direction direction = data.getMovementDirection();
            double movementAmount = 0.0D;
            for (SimpleCollisionBox box : data.boxes) {
                if (playerBox.isIntersected(box)) {
                    movementAmount = Math.max(movementAmount, movementNeededToExit(box, direction, playerBox));
                }
            }

            boolean movementIntersects = movementAmount > 0.0D;
            boolean sourceFixIntersects =
                    !data.retractingSourceFixBoxes.isEmpty() && intersectsAny(playerBox, data.retractingSourceFixBoxes);
            if (!movementIntersects && !sourceFixIntersects) continue;

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
                unionSignedAxis(pistonPushes, direction, shove);
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
        return pistonPushes;
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
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5) || !player.isBedrockMovement();
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

    /**
     * Whether the PistonMovingBlockEntity at {@code state}'s position still ticks. LevelChunk$BoundTickingBlockEntity
     * reads its own chunk, which ClientChunkCache's view center never hides, and Level#tickBlockEntities ticks it in or
     * out of that range. A block change there ({@link #handleBlockStateApplied}) or ClientLevel#unload
     * ({@link #forgetChunk}) removes it; a view resize drops the column without unloading it, so its ticker keeps the
     * state it had.
     */
    private boolean ticks(MovingPistonState state) {
        int stored = world.storedBlockStateIdAt(state.pos());
        return stored < 0 || BlockIds.is(stored, BlockIds.MOVING_PISTON);
    }

    /**
     * Level#tickBlockEntities over the moving pistons in Level#blockEntityTickers order: each one advances, or, past its
     * fifth client death tick, removes its block entity and settles, the tickers after it seeing its writes.
     */
    private void tickMovingPistonStates() {
        if (movingPistonStates.isEmpty()) return;
        List<MovingPistonState> ticking = new ArrayList<>(movingPistonStates.values());
        ticking.sort(TICKER_ORDER);
        ClientPistonLevel level = null;
        for (MovingPistonState state : ticking) {
            long key = state.pos().asLong();
            if (movingPistonStates.get(key) != state) continue;
            if (!ticks(state)) {
                movingPistonStates.remove(key);
            } else if (state.tickIfGuaranteedFinished()) {
                movingPistonStates.remove(key);
                if (level == null) level = ClientPistonLevel.of(world);
                settleMovingPiston(level, state);
            }
        }
    }

    private boolean settlesThisPass(MovingPistonState state) {
        return state.progress() >= 1.0F && state.deathTicks >= 5 && ticks(state);
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
        List<BlockPos> toDestroy = new ArrayList<>(0);
        Direction pushDirection = extending ? pistonDirection : pistonDirection.getOpposite();
        BlockPos startPos = extending ? pistonPos.relative(pistonDirection) : pistonPos.relative(pistonDirection, 2);
        BlockPos retractingHeadPos = extending ? null : pistonPos.relative(pistonDirection);
        int startState = getPistonStructureStateAt(startPos, retractingHeadPos);

        if (!isPistonPushable(startState, startPos, pushDirection, false, pistonDirection)) {
            if (extending && REGISTRY.facts(startState).pushReaction() == StateFacts.PushReaction.POPPED) {
                toDestroy.add(startPos);
                return new PistonStructure(true, toPush, toDestroy, false);
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

        boolean slime = false;
        for (BlockPos pushed : toPush) slime |= BlockIds.is(world.getBlockStateIdAt(pushed), BlockIds.SLIME_BLOCK);
        return new PistonStructure(true, toPush, toDestroy, slime);
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

    /**
     * PistonStructureResolver#getToPush and #getToDestroy, in their resolution order, and whether a pushed block is a
     * slime block.
     */
    private record PistonStructure(boolean resolved, List<BlockPos> toPush, List<BlockPos> toDestroy, boolean slime) {
        private static PistonStructure unresolved() {
            return new PistonStructure(false, Collections.emptyList(), Collections.emptyList(), false);
        }
    }

    public VoxelShape getMovingPistonCollisionShape(BlockPos pos) {
        MovingPistonState movingPiston = movingPistonStates.get(pos.asLong());
        ClientBlockEntityPass.Final settled = replayFinals == null ? null : replayFinals.get(pos.asLong());
        if (settled != null) {
            if (replayFinalized.contains(pos.asLong())) return world.geometry().collision(settled.finalState(), pos);
            if (movingPiston == null) {
                // The tick end already settled this block entity; until its ticker replays it collides at progress 1.
                movingPiston = new MovingPistonState(
                        settled.pos(),
                        settled.movedState(),
                        settled.direction(),
                        settled.extending(),
                        settled.source(),
                        0);
                movingPiston.progress = 1.0F;
                return movingPiston.getCollisionShape(world, pos, 1.0F, replayNoClip);
            }
        }
        if (movingPiston == null) {
            return Shapes.empty();
        }
        if (replayProgress == null) return movingPiston.getCollisionShape(world, pos, movingPiston.progress(), null);
        long key = pos.asLong();
        float progress = replayProgress.containsKey(key) ? replayProgress.get(key) : movingPiston.progress();
        return movingPiston.getCollisionShape(world, pos, progress, replayNoClip);
    }

    /**
     * Adds, with each one's Level#blockEntityTickers position, the moving pistons whose next
     * PistonMovingBlockEntity#tick moves entities: those whose progress is still below 1.
     */
    void addPassMovers(List<CompensatedWorld.OrderedMover> movers) {
        for (MovingPistonState state : movingPistonStates.values()) {
            if (state.progress() >= 1.0F || !ticks(state)) continue;
            movers.add(new CompensatedWorld.OrderedMover(
                    state.tickerOrder,
                    new ClientBlockEntityPass.Piston(
                            state.pos(),
                            state.movedState,
                            state.direction,
                            state.extending,
                            state.source,
                            state.progress())));
        }
    }

    /**
     * Adds, with each one's Level#blockEntityTickers position, the moving pistons whose next PistonMovingBlockEntity#tick
     * is past its fifth client death tick and replaces their MOVING_PISTON. Each settles on a detached level in ticker
     * order, over the writes of the settles before it and the progress the tickers before it have stored, as
     * {@link #tickMovingPistonStates} then settles it.
     */
    void addPassFinals(List<CompensatedWorld.OrderedFinal> finals) {
        boolean settles = false;
        for (MovingPistonState state : movingPistonStates.values()) settles |= settlesThisPass(state);
        if (!settles) return;
        List<MovingPistonState> ticking = new ArrayList<>(movingPistonStates.size());
        for (MovingPistonState state : movingPistonStates.values()) if (ticks(state)) ticking.add(state);
        ticking.sort(TICKER_ORDER);
        ClientPistonLevel level = ClientPistonLevel.detached(world);
        float[] stored = new float[ticking.size()];
        try {
            for (int i = 0; i < ticking.size(); i++) {
                MovingPistonState state = ticking.get(i);
                stored[i] = state.progress;
                if (!settlesThisPass(state)) {
                    // PistonMovingBlockEntity#tick stores progress + 0.5 once it has moved entities.
                    if (state.progress < 1.0F) state.progress = Math.min(1.0F, state.progress + 0.5F);
                    continue;
                }
                int moving = world.getBlockStateIdAt(state.pos());
                if (!BlockIds.is(moving, BlockIds.MOVING_PISTON)) continue;
                int from = level.writes().size();
                int settled = settleMovingPiston(level, state);
                finals.add(new CompensatedWorld.OrderedFinal(
                        state.tickerOrder,
                        state.pos(),
                        moving,
                        state.movedState,
                        state.direction,
                        state.extending,
                        state.source,
                        settled,
                        List.copyOf(level.writes().subList(from, level.writes().size()))));
            }
        } finally {
            for (int i = 0; i < ticking.size(); i++) ticking.get(i).progress = stored[i];
        }
    }

    /**
     * Starts replaying {@code pass}: until the scope closes, each of its pistons collides at the progress the replay
     * gives it, the progress its PistonMovingBlockEntity had when that tick ran, rather than this model's current one.
     * Each block the pass finalizes reads as its MOVING_PISTON until {@link #advanceFinalReplay} reaches its ticker.
     */
    public ReplayScope beginReplay(ClientBlockEntityPass pass) {
        if (pass.isEmpty()) return NO_REPLAY;
        ReplayScope scope =
                new ReplayScope(this, replayProgress, replayNoClip, replayFinals, replayFinalized, replayWrites);
        Long2FloatOpenHashMap progress = new Long2FloatOpenHashMap();
        for (ClientBlockEntityPass.Mover mover : pass.movers())
            if (mover instanceof ClientBlockEntityPass.Piston piston)
                progress.put(piston.pos().asLong(), piston.progress());
        replayProgress = progress;
        replayNoClip = null;
        replayFinals = null;
        replayFinalized = null;
        replayWrites = null;
        if (!pass.finals().isEmpty()) {
            replayFinals = new Long2ObjectOpenHashMap<>(pass.finals().size());
            for (ClientBlockEntityPass.Final settled : pass.finals())
                replayFinals.put(settled.pos().asLong(), settled);
            replayFinalized = new LongOpenHashSet(pass.finals().size());
            for (ClientBlockEntityPass.Final settled : pass.finals()) {
                for (ClientBlockEntityPass.Write write : settled.writes()) {
                    long key = write.pos().asLong();
                    if (replayFinals.containsKey(key)) continue;
                    if (replayWrites == null) replayWrites = new Long2ObjectOpenHashMap<>();
                    replayWrites
                            .computeIfAbsent(key, ignored -> new ArrayList<>(1))
                            .add(new SettleWrite(settled.pos().asLong(), write.before(), write.after()));
                }
            }
        }
        return scope;
    }

    /** PistonMovingBlockEntity#tick stored {@code progress} at {@code pos}, after moving entities. */
    public void advanceReplay(BlockPos pos, float progress) {
        if (replayProgress != null) replayProgress.put(pos.asLong(), progress);
    }

    /** The replay reached {@code settled}'s ticker: its block reads as the final state from here on. */
    public void advanceFinalReplay(ClientBlockEntityPass.Final settled) {
        if (replayFinalized != null) replayFinalized.add(settled.pos().asLong());
    }

    /**
     * The state a replay reads at a block its pass finalizes or a settle there writes: before the first such ticker
     * the replay reaches, the state the pass started with, then that of the last one it reached. -1 elsewhere and
     * outside a replay.
     */
    int replayStateAt(int x, int y, int z) {
        if (replayFinals == null) return -1;
        long key = BlockPos.asLong(x, y, z);
        ClientBlockEntityPass.Final settled = replayFinals.get(key);
        if (settled != null) return replayFinalized.contains(key) ? settled.finalState() : settled.movingState();
        List<SettleWrite> writes = replayWrites == null ? null : replayWrites.get(key);
        if (writes == null) return -1;
        int state = writes.getFirst().before();
        for (SettleWrite write : writes) if (replayFinalized.contains(write.settled())) state = write.after();
        return state;
    }

    /** A settle's write at a position other than its own: the final at {@code settled} changed it to {@code after}. */
    private record SettleWrite(long settled, int before, int after) {}

    /** PistonMovingBlockEntity#NOCLIP during moveEntityByPiston, or null between moves. */
    public void setReplayNoClip(Direction noClip) {
        replayNoClip = noClip;
    }

    /** A candidate's block-entity pass restores the surrounding travel's pre-pass collision view. */
    public static final class ReplayScope implements AutoCloseable {
        private final CompensatedWorldPistons owner;
        private final Long2FloatOpenHashMap previousProgress;
        private final Direction previousNoClip;
        private final Long2ObjectOpenHashMap<ClientBlockEntityPass.Final> previousFinals;
        private final LongOpenHashSet previousFinalized;
        private final Long2ObjectOpenHashMap<List<SettleWrite>> previousWrites;

        private ReplayScope(
                CompensatedWorldPistons owner,
                Long2FloatOpenHashMap previousProgress,
                Direction previousNoClip,
                Long2ObjectOpenHashMap<ClientBlockEntityPass.Final> previousFinals,
                LongOpenHashSet previousFinalized,
                Long2ObjectOpenHashMap<List<SettleWrite>> previousWrites) {
            this.owner = owner;
            this.previousProgress = previousProgress;
            this.previousNoClip = previousNoClip;
            this.previousFinals = previousFinals;
            this.previousFinalized = previousFinalized;
            this.previousWrites = previousWrites;
        }

        @Override
        public void close() {
            if (owner == null) return;
            owner.replayProgress = previousProgress;
            owner.replayNoClip = previousNoClip;
            owner.replayFinals = previousFinals;
            owner.replayFinalized = previousFinalized;
            owner.replayWrites = previousWrites;
        }
    }

    private static final class MovingPistonState {
        private final BlockPos pos;
        private int movedState;
        private Direction direction;
        private boolean extending;
        private boolean source;
        private float progress;
        private int deathTicks;
        private final long tickerOrder;

        private MovingPistonState(
                BlockPos pos,
                int movedState,
                Direction direction,
                boolean extending,
                boolean source,
                long tickerOrder) {
            this.pos = pos.immutable();
            this.movedState = movedState;
            this.direction = direction;
            this.extending = extending;
            this.source = source;
            this.tickerOrder = tickerOrder;
        }

        private BlockPos pos() {
            return pos;
        }

        private boolean tickIfGuaranteedFinished() {
            if (progress >= 1.0F) {
                // Client piston entities wait five additional ticks.
                if (deathTicks >= 5) return true;
                deathTicks++;
            } else {
                progress += 0.5F;
                if (progress >= 1.0F) progress = 1.0F;
            }
            return false;
        }

        private float progress() {
            return progress;
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
                boxes.add(start.union(end));
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

        /** PistonMovingBlockEntity#getCollisionShape with {@code noClip} as PistonMovingBlockEntity#NOCLIP. */
        private VoxelShape getCollisionShape(CompensatedWorld level, BlockPos pos, float progress, Direction noClip) {
            return level.geometry().movingPiston(movedState, pos, direction, extending, source, progress, noClip);
        }
    }
}
