package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.player.BedrockBlockLayers;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;

/** Short-lived capture of complete block corrections emitted by one Java acknowledgement. */
final class GeyserBlockUpdates {
    private final Map<BlockPos, BedrockBlockLayers> updates = new LinkedHashMap<>();
    private int depth;

    void begin() { depth++; }
    boolean active() { return depth != 0; }

    static boolean supports(BedrockPacket packet) {
        return packet instanceof UpdateBlockPacket || packet instanceof UpdateSubChunkBlocksPacket;
    }

    void capture(BedrockPacket packet, BedrockCoordinateFrame coordinates, Map<Integer, BlockState> palette) {
        if (!active()) return;
        if (packet instanceof UpdateBlockPacket block) {
            update(block.getBlockPosition(), block.getDataLayer(), block.getDefinition(), coordinates, palette);
        } else if (packet instanceof UpdateSubChunkBlocksPacket blocks) {
            for (var block : blocks.getStandardBlocks()) update(block.getPosition(), 0, block.getDefinition(), coordinates, palette);
            for (var block : blocks.getExtraBlocks()) update(block.getPosition(), 1, block.getDefinition(), coordinates, palette);
        }
    }

    void end(CultPlayer player, Consumer<Consumer<CultPlayer>> trailingBoundary) {
        if (!active()) throw new IllegalStateException("Unbalanced Geyser block acknowledgement");
        if (--depth != 0) return;
        // Geyser Block.sendBlockUpdatePacket emits both layers for each correction.
        // Keep them only for this acknowledgement; the shared world retains normal Java states.
        List<Update> snapshot = updates.entrySet().stream()
                .map(entry -> new Update(entry.getKey(), entry.getValue().combined())).toList();
        updates.clear();
        if (player == null || snapshot.isEmpty()) return;
        boolean near = snapshot.stream().anyMatch(update ->
                Math.abs(update.position().getX() - player.x) < 16
                && Math.abs(update.position().getY() - player.y) < 16
                && Math.abs(update.position().getZ() - player.z) < 16);
        Consumer<CultPlayer> apply = target -> snapshot.forEach(update -> target.compensatedWorld
                .handleServerBlockUpdate(update.position(), update.state(), target.lastTransactionReceived.get()));
        // Preserve BasePacketWorldReader's nearby trailing proof / distant existing proof policy.
        if (near) trailingBoundary.accept(apply);
        else player.addBedrockTransactionTask(player.getLastClientboundBedrockTransaction(), () -> apply.accept(player));
    }

    private void update(Vector3i position, int layer, BlockDefinition definition,
                        BedrockCoordinateFrame coordinates, Map<Integer, BlockState> palette) {
        var world = new BlockPos(Math.addExact(position.getX(), coordinates.originX()), position.getY(),
                Math.addExact(position.getZ(), coordinates.originZ()));
        var previous = updates.getOrDefault(world, BedrockBlockLayers.fromJava(Blocks.AIR.defaultBlockState()));
        updates.put(world, previous.withLayer(layer, GeyserBlockStateMappings.resolve(palette, definition)));
    }

    private record Update(BlockPos position, BlockState state) { }
}
