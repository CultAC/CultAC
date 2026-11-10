package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.player.BedrockClientActors;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.session.GeyserSession;

/**
 * Reads the actors Geyser sends without a server entity behind them, which the shared entity tracker never sees:
 * the two invisible collidable armor stands of its world border, spawned at the player and moved to the border on
 * every input.
 *
 * <p>Updates written since the last latency marker take effect together when the client answers the next one: an
 * actor spawned around the player and moved in the same batch starts its lerp to the border from the player.
 */
final class GeyserClientActors {
    /** The last position written for each actor without a server entity, which a partial move refers to. */
    private final Long2ObjectOpenHashMap<Vector3f> written = new Long2ObjectOpenHashMap<>();
    // Updates written since the last latency marker; the next marker proves them.
    private ArrayList<Consumer<BedrockClientActors>> pending = new ArrayList<>();

    void capture(GeyserSession session, BedrockPacket packet, BedrockCoordinateFrame frame) {
        if (packet instanceof AddEntityPacket add) {
            long id = add.getRuntimeEntityId();
            if (id == session.getPlayerEntity().geyserId()
                    || session.getEntityCache().getEntityByGeyserId(id) != null) return;
            Vector3f position = add.getPosition();
            Shape shape = Shape.read(add.getMetadata());
            written.put(id, position);
            pending.add(actors -> {
                actors.remove(id);
                actors.place(id, position.getX(), position.getY(), position.getZ(), frame);
                if (shape != null) shape.apply(actors, id);
            });
        } else if (packet instanceof SetEntityDataPacket data) {
            long id = data.getRuntimeEntityId();
            Shape shape = written.containsKey(id) ? Shape.read(data.getMetadata()) : null;
            if (shape != null) pending.add(actors -> shape.apply(actors, id));
        } else if (packet instanceof MoveEntityAbsolutePacket move) {
            moved(move.getRuntimeEntityId(), move.getPosition(), frame);
        } else if (packet instanceof MoveEntityDeltaPacket move) {
            Vector3f previous = written.get(move.getRuntimeEntityId());
            if (previous == null) return;
            var flags = move.getFlags();
            moved(
                    move.getRuntimeEntityId(),
                    Vector3f.from(
                            flags.contains(MoveEntityDeltaPacket.Flag.HAS_X) ? move.getX() : previous.getX(),
                            flags.contains(MoveEntityDeltaPacket.Flag.HAS_Y) ? move.getY() : previous.getY(),
                            flags.contains(MoveEntityDeltaPacket.Flag.HAS_Z) ? move.getZ() : previous.getZ()),
                    frame);
        } else if (packet instanceof RemoveEntityPacket remove) {
            long id = remove.getUniqueEntityId();
            if (written.remove(id) != null) pending.add(actors -> actors.remove(id));
        }
    }

    private void moved(long id, Vector3f position, BedrockCoordinateFrame frame) {
        if (written.replace(id, position) == null) return;
        pending.add(actors -> actors.move(id, position.getX(), position.getY(), position.getZ(), frame));
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    void boundary(CultPlayer player, CultPlayer.BedrockTransaction transaction) {
        if (transaction == null || pending.isEmpty()) return;
        var batch = pending;
        pending = new ArrayList<>();
        player.addBedrockTransactionTask(
                transaction, () -> batch.forEach(update -> update.accept(player.bedrockState.clientActors)));
    }

    /** The client entered another dimension, which holds none of the earlier actors. */
    void clear() {
        written.clear();
        pending.clear();
        pending.add(BedrockClientActors::clear);
    }

    /** The size and collidable flag in an actor's entity data; null when it carries neither. */
    private record Shape(Float width, Float height, Boolean collidable) {
        static Shape read(EntityDataMap data) {
            if (data == null) return null;
            Float width = data.get(EntityDataTypes.WIDTH);
            Float height = data.get(EntityDataTypes.HEIGHT);
            Boolean collidable = data.get(EntityDataTypes.FLAGS) == null ? null : data.getFlag(EntityFlag.COLLIDABLE);
            return width == null && height == null && collidable == null
                    ? null
                    : new Shape(width, height, collidable);
        }

        void apply(BedrockClientActors actors, long id) {
            actors.shape(id, width, height, collidable);
        }
    }
}
