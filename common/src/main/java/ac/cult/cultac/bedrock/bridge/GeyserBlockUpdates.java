package ac.cult.cultac.bedrock.bridge;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.player.BedrockBlockLayers;
import ac.cult.cultac.bedrock.player.BedrockPistonBlocks;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;

/**
 * The block updates Geyser sends on its own account, as opposed to its translations of the server's block updates,
 * which the Java listener already applies at their flush proof.
 *
 * <p>Geyser places blocks only the Bedrock client has: a virtual container's holder above the player's head, a
 * piston's intermediate and final blocks ahead of the server's, the lower half of a door. It also restores the
 * server's block where the client's own prediction or one of those replaced it. Each run of block packets is
 * compared with the newest block the server sent for the position. A packet that only repeats that block's
 * translation while the client holds nothing else is left to the Java listener. Everything else is applied to the
 * world at a latency boundary written right after the run. A {@link GeyserBlockAckTranslator#bracket} marks
 * corrections that apply regardless, because the client's own prediction may have changed the block.
 *
 * <p>The block entity data of a moving block and of a piston arm travels with those blocks: it tells where a
 * piston's carried blocks collide, and takes effect at the same boundary.
 */
final class GeyserBlockUpdates {
    private static final int RANGE = 16;

    /** The layers the client holds where they are not the translation of the server's block, as of the last send. */
    private final Map<BlockPos, BedrockBlockLayers> clientOnly = new HashMap<>();
    /** The layers written by the block packets since the last boundary. */
    private final Map<BlockPos, Written> run = new LinkedHashMap<>();
    /** The piston block entity data sent since the last boundary. */
    private final List<PistonData> pistonData = new ArrayList<>();

    private int[] sentStates = new int[0];
    private int depth;

    void begin() {
        depth++;
    }

    boolean active() {
        return depth != 0;
    }

    /** Whether block packets were captured that no boundary proves yet. */
    boolean pending() {
        return !run.isEmpty() || !pistonData.isEmpty();
    }

    static boolean supports(BedrockPacket packet) {
        return packet instanceof UpdateBlockPacket || packet instanceof UpdateSubChunkBlocksPacket;
    }

    void capture(BedrockPacket packet, BedrockCoordinateFrame coordinates, GeyserBlockStateMappings.Tables tables) {
        sentStates = tables.sentStates();
        Map<Integer, Integer> palette = tables.palette();
        if (packet instanceof UpdateBlockPacket block) {
            update(block.getBlockPosition(), block.getDataLayer(), block.getDefinition(), coordinates, palette);
        } else if (packet instanceof UpdateSubChunkBlocksPacket blocks) {
            for (var block : blocks.getStandardBlocks())
                update(block.getPosition(), 0, block.getDefinition(), coordinates, palette);
            for (var block : blocks.getExtraBlocks())
                update(block.getPosition(), 1, block.getDefinition(), coordinates, palette);
        }
    }

    /**
     * Captures the data of a moving block or a piston arm; any other block entity is not part of a run.
     * {@code definitions} gives the block a moving block's data names.
     */
    boolean capture(
            BlockEntityDataPacket packet,
            BedrockCoordinateFrame coordinates,
            GeyserBlockStateMappings.Tables tables,
            Function<NbtMap, ? extends BlockDefinition> definitions) {
        NbtMap data = packet.getData();
        if (data == null) return false;
        BlockPos position = world(packet.getBlockPosition(), coordinates);
        switch (data.getString("id", "")) {
            case "MovingBlock" -> {
                NbtMap block = data.getCompound("movingBlock", null);
                BlockDefinition definition = block == null ? null : definitions.apply(block);
                Integer state =
                        definition == null ? null : tables.palette().get(definition.getRuntimeId());
                int carried = state == null ? BedrockPistonBlocks.NO_BLOCK : state;
                var named = Vector3i.from(
                        data.getInt("pistonPosX", 0), data.getInt("pistonPosY", -1), data.getInt("pistonPosZ", 0));
                // A moving block without a piston names the position (0, -1, 0).
                BlockPos piston =
                        named.getX() == 0 && named.getY() == -1 && named.getZ() == 0 ? null : world(named, coordinates);
                boolean expanding = data.getBoolean("expanding");
                pistonData.add(new PistonData(
                        position, pistons -> pistons.movingBlock(position, carried, piston, expanding)));
            }
            case "PistonArm" -> {
                // The client keeps its own progress: only the state reaches it.
                int state = data.getByte("State");
                pistonData.add(new PistonData(position, pistons -> pistons.pistonArm(position, state)));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    void end(CultPlayer player, Consumer<Consumer<CultPlayer>> trailingBoundary) {
        if (!active()) throw new IllegalStateException("Unbalanced Geyser block acknowledgement");
        if (--depth != 0) return;
        finish(player, trailingBoundary);
    }

    /** Ends the run: its updates take effect once the client has processed the boundary written here. */
    void finish(CultPlayer player, Consumer<Consumer<CultPlayer>> trailingBoundary) {
        if (!pending()) return;
        if (player == null) {
            run.clear();
            pistonData.clear();
            return;
        }
        List<Update> snapshot = new ArrayList<>(run.size());
        for (var entry : run.entrySet()) {
            Update update = resolve(player.compensatedWorld, entry.getKey(), entry.getValue());
            if (update != null) snapshot.add(update);
        }
        run.clear();
        List<PistonData> pistons = List.copyOf(pistonData);
        pistonData.clear();
        if (snapshot.isEmpty() && pistons.isEmpty()) return;
        boolean near = snapshot.stream().anyMatch(update -> near(player, update.position()))
                || pistons.stream().anyMatch(data -> near(player, data.position()));
        Consumer<CultPlayer> apply = target -> {
            snapshot.forEach(update -> target.compensatedWorld.handleBedrockClientBlockUpdate(
                    update.position(), update.state(), update.serverBlock()));
            pistons.forEach(data -> data.apply().accept(target.bedrockState.pistonBlocks));
        };
        // Preserve BasePacketWorldReader's nearby trailing proof / distant existing proof policy.
        if (near) trailingBoundary.accept(apply);
        else
            player.addBedrockTransactionTask(player.getLastClientboundBedrockTransaction(), () -> apply.accept(player));
    }

    private static boolean near(CultPlayer player, BlockPos position) {
        return Math.abs(position.getX() - player.x) < RANGE
                && Math.abs(position.getY() - player.y) < RANGE
                && Math.abs(position.getZ() - player.z) < RANGE;
    }

    /** The client replaced this column, or every column: it holds none of the earlier blocks. */
    void forgetChunk(int chunkX, int chunkZ) {
        clientOnly.keySet().removeIf(position -> position.getX() >> 4 == chunkX && position.getZ() >> 4 == chunkZ);
    }

    void clear() {
        clientOnly.clear();
        run.clear();
        pistonData.clear();
    }

    private Update resolve(CompensatedWorld world, BlockPos position, Written written) {
        int sent = world.getServerSentBlockStateIdAt(position);
        BedrockBlockLayers server = sent == CompensatedWorld.UNKNOWN_SERVER_STATE ? null : translation(sent);
        // The world holds the client's own prediction here, so it cannot tell what the server sent.
        if (written.correction && world.isBedrockClientWritten(position.getX(), position.getY(), position.getZ()))
            server = null;
        if (server == null) {
            clientOnly.remove(position);
            // An unknown server block: the column is being replaced. A correction is the server's block, as sent.
            if (!written.correction) return null;
            return new Update(
                    position, written.onto(BedrockBlockLayers.fromJava(BlockIds.AIR.defaultState())).combined(), true);
        }
        BedrockBlockLayers before = clientOnly.getOrDefault(position, server);
        BedrockBlockLayers after = written.onto(before);
        if (!written.correction && after.equals(before)) return null;
        boolean serverBlock = after.equals(server);
        if (serverBlock) clientOnly.remove(position);
        else clientOnly.put(position, after);
        return new Update(position, serverBlock ? sent : after.combined(), serverBlock);
    }

    /** The layers Geyser's translation of a server state resolves to through the session palette. */
    private BedrockBlockLayers translation(int state) {
        BedrockBlockLayers layers = BedrockBlockLayers.fromJava(state);
        int primary = layers.primary() < sentStates.length ? sentStates[layers.primary()] : -1;
        return primary < 0 ? layers : new BedrockBlockLayers(primary, layers.extra());
    }

    private void update(
            Vector3i position,
            int layer,
            BlockDefinition definition,
            BedrockCoordinateFrame coordinates,
            Map<Integer, Integer> palette) {
        Integer state;
        if (active()) {
            state = GeyserBlockStateMappings.resolve(palette, definition);
        } else {
            // A block Geyser has no server state for cannot be placed in the world.
            state = definition == null ? null : palette.get(definition.getRuntimeId());
            if (state == null) return;
        }
        Written written = run.computeIfAbsent(world(position, coordinates), ignored -> new Written());
        written.correction |= active();
        if (layer == 0) written.primary = state;
        else if (layer == 1) written.extra = state;
        else throw new IllegalArgumentException("Unknown Bedrock block layer " + layer);
    }

    private static BlockPos world(Vector3i position, BedrockCoordinateFrame coordinates) {
        return new BlockPos(
                Math.addExact(position.getX(), coordinates.originX()),
                position.getY(),
                Math.addExact(position.getZ(), coordinates.originZ()));
    }

    private static final class Written {
        private Integer primary;
        private Integer extra;
        private boolean correction;

        BedrockBlockLayers onto(BedrockBlockLayers layers) {
            if (primary != null) layers = layers.withLayer(0, primary);
            if (extra != null) layers = layers.withLayer(1, extra);
            return layers;
        }
    }

    private record Update(BlockPos position, int state, boolean serverBlock) {}

    private record PistonData(BlockPos position, Consumer<BedrockPistonBlocks> apply) {}
}
