package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import java.util.ArrayList;
import java.util.List;

/**
 * The client Level a piston's block changes go through. ClientPacketListener#handleBlockEvent runs
 * PistonBaseBlock#triggerEvent and #moveBlocks on the client's own world, and PistonMovingBlockEntity settles in its
 * Level#tickBlockEntities. Both call Level#setBlock and BlockStateBase#updateNeighbourShapes, which no client guard
 * skips, so a fence beside a pulled block disconnects as the client handles the event, a server tick before
 * ServerLevel#tick broadcasts the same change. The writes run through the client simulator's SimLevel, with its
 * CollectingNeighborUpdater order and the connection's received tags.
 *
 * <p>States crossing this boundary are compensated world IDs. A committing level applies each call's writes before it
 * returns, so world reads between calls see them; a detached one only records them.
 */
final class ClientPistonLevel {
    // MCP-Reborn BlockStateBase#updateNeighbourShapes(level, pos, 2) callers in PistonBaseBlock.
    private static final int UPDATE_CLIENTS = 2;

    private final CompensatedWorld world;
    private final SimLevel level;
    private final ModelBlockStates states;
    private final boolean commits;
    private final List<ClientBlockEntityPass.Write> writes = new ArrayList<>(0);
    private int recorded;

    private ClientPistonLevel(CompensatedWorld world, SimLevel level, ModelBlockStates states, boolean commits) {
        this.world = world;
        this.level = level;
        this.states = states;
        this.commits = commits;
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
        return states.toHost(level.stateAt(position(pos)));
    }

    /** Level#setBlock with {@code flags}, including the neighbor shape updates a flag without 16 runs. */
    void setBlock(BlockPos pos, int state, int flags) {
        level.setBlock(position(pos), states.toModel(state), flags);
        record();
    }

    /** Level#removeBlock(pos, false). */
    void removeBlock(BlockPos pos) {
        level.removeBlock(position(pos), false);
        record();
    }

    /** BlockStateBase#updateNeighbourShapes(level, pos, 2) of {@code state} at {@code pos}. */
    void updateNeighbourShapes(int state, BlockPos pos) {
        level.updateNeighborShapes(states.toModel(state), position(pos), UPDATE_CLIENTS);
        record();
    }

    /** BlockStateBase#updateIndirectNeighbourShapes(level, pos, 2) of {@code state} at {@code pos}. */
    void updateIndirectNeighbourShapes(int state, BlockPos pos) {
        level.updateIndirectNeighborShapes(states.toModel(state), position(pos), UPDATE_CLIENTS);
        record();
    }

    /** Block#updateFromNeighbourShapes of {@code state} placed at {@code pos}. */
    int updateFromNeighbourShapes(int state, BlockPos pos) {
        return states.toHost(level.updateFromNeighborShapes(states.toModel(state), position(pos)));
    }

    /** Every write this level has made, in Level#setBlock call order. */
    List<ClientBlockEntityPass.Write> writes() {
        return writes;
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
