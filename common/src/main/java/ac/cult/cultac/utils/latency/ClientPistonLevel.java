package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * The client Level a piston's block changes go through. ClientPacketListener#handleBlockEvent runs
 * PistonBaseBlock#triggerEvent and #moveBlocks on the client's own world, and PistonMovingBlockEntity settles in its
 * Level#tickBlockEntities. Both call Level#setBlock and BlockStateBase#updateNeighbourShapes, which no client guard
 * skips, so a fence beside a pulled block disconnects as the client handles the event, a server tick before
 * ServerLevel#tick broadcasts the same change. A Java world runs these writes through the client simulator's SimLevel,
 * with its CollectingNeighborUpdater order and the connection's received tags; Bedrock's world keeps raw writes.
 *
 * <p>States crossing this boundary are compensated world IDs. A committing level applies each call's writes before it
 * returns, so world reads between calls see them; a detached one only records them.
 */
final class ClientPistonLevel {
    private static final int AIR = BlockIds.AIR.defaultState();
    // MCP-Reborn BlockStateBase#updateNeighbourShapes(level, pos, 2) callers in PistonBaseBlock.
    private static final int UPDATE_CLIENTS = 2;

    private final CompensatedWorld world;
    // Null for Bedrock's raw writes.
    private final SimLevel level;
    private final ModelBlockStates states;
    private final boolean commits;
    private final List<ClientBlockEntityPass.Write> writes = new ArrayList<>(0);
    // A detached raw level's overlay; a SimLevel keeps its own.
    private final Long2IntOpenHashMap rawOverlay;
    private int recorded;

    private ClientPistonLevel(CompensatedWorld world, SimLevel level, ModelBlockStates states, boolean commits) {
        this.world = world;
        this.level = level;
        this.states = states;
        this.commits = commits;
        rawOverlay = level == null && !commits ? new Long2IntOpenHashMap() : null;
    }

    /** The client Level whose writes land in {@code world} as each call returns. */
    static ClientPistonLevel of(CompensatedWorld world) {
        return create(world, true);
    }

    /** An overlay over {@code world} that records its writes and never applies them. */
    static ClientPistonLevel detached(CompensatedWorld world) {
        return create(world, false);
    }

    private static ClientPistonLevel create(CompensatedWorld world, boolean commits) {
        CultPlayer player = world.player;
        if (player.isBedrockMovement()) return new ClientPistonLevel(world, null, null, commits);
        var binding = ClientBlockActions.binding(player);
        var view = new BlockSimulatorWorldView(
                world, player.checkManager.getListener(PacketWorldBorder.class), binding.states());
        SimLevel level = binding.simulator()
                .level(
                        view,
                        binding.tags(),
                        BlockSimClientVersion.of(binding.client().protocol()));
        return new ClientPistonLevel(world, level, binding.states(), commits);
    }

    int stateAt(BlockPos pos) {
        if (level != null) return states.toHost(level.stateAt(position(pos)));
        if (rawOverlay != null && rawOverlay.containsKey(pos.asLong())) return rawOverlay.get(pos.asLong());
        return world.getBlockStateIdAt(pos);
    }

    /** Level#setBlock with {@code flags}, including the neighbor shape updates a flag without 16 runs. */
    void setBlock(BlockPos pos, int state, int flags) {
        if (level == null) {
            raw(pos, state);
            return;
        }
        level.setBlock(position(pos), states.toModel(state), flags);
        record();
    }

    /** Level#removeBlock(pos, false). */
    void removeBlock(BlockPos pos) {
        if (level == null) {
            raw(pos, AIR);
            return;
        }
        level.removeBlock(position(pos), false);
        record();
    }

    /** BlockStateBase#updateNeighbourShapes(level, pos, 2) of {@code state} at {@code pos}. */
    void updateNeighbourShapes(int state, BlockPos pos) {
        if (level == null) return;
        level.updateNeighborShapes(states.toModel(state), position(pos), UPDATE_CLIENTS);
        record();
    }

    /** BlockStateBase#updateIndirectNeighbourShapes(level, pos, 2) of {@code state} at {@code pos}. */
    void updateIndirectNeighbourShapes(int state, BlockPos pos) {
        if (level == null) return;
        level.updateIndirectNeighborShapes(states.toModel(state), position(pos), UPDATE_CLIENTS);
        record();
    }

    /** Block#updateFromNeighbourShapes of {@code state} placed at {@code pos}. */
    int updateFromNeighbourShapes(int state, BlockPos pos) {
        if (level == null) return state;
        return states.toHost(level.updateFromNeighborShapes(states.toModel(state), position(pos)));
    }

    /** Every write this level has made, in Level#setBlock call order. */
    List<ClientBlockEntityPass.Write> writes() {
        return writes;
    }

    private void raw(BlockPos pos, int state) {
        int before = stateAt(pos);
        if (before != state) writes.add(new ClientBlockEntityPass.Write(pos, before, state));
        if (commits) world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), state);
        else rawOverlay.put(pos.asLong(), state);
    }

    private void record() {
        var made = level.writes();
        for (int i = recorded; i < made.size(); i++) {
            var write = made.get(i);
            var pos = new BlockPos(write.pos().x(), write.pos().y(), write.pos().z());
            int after = states.toHost(write.newState());
            writes.add(new ClientBlockEntityPass.Write(pos, states.toHost(write.oldState()), after));
            if (commits) world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), after);
        }
        recorded = made.size();
    }

    private static ac.cult.blocksim.engine.BlockPos position(BlockPos pos) {
        return new ac.cult.blocksim.engine.BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }
}
