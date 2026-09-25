package ac.cult.cultac.bedrock.bridge;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;

/** Wire confirmations only: reported poses never replace the movement engine's predicted state. */
final class GeyserPoseConfirmations {
    static final Vector3f COLLISION_DEFINITION = Vector3f.from(.6F, 1.8F, 0F);
    private Pose pose;
    private Confirmation lastSent;
    private final Map<SetEntityDataPacket, Long> pending = new IdentityHashMap<>();

    void begin(GeyserSession session, PlayerAuthInputPacket input) {
        var entity = session.getPlayerEntity();
        pose = Pose.read(entity).actions(input.getInputData());
    }

    void confirm(GeyserSession session, PlayerAuthInputPacket input, GeyserSprintAttributes.Boundary boundary) {
        var entity = session.getPlayerEntity();
        // Geyser handles touch/scaffolding/flying sneak inputs; retain those rules.
        pose = new Pose(session.isSneaking(), pose.gliding(), pose.swimming(), pose.crawling(), pose.spinning());
        pose.apply(entity);
        var desired = entity.getDesiredPose();
        session.setPose(desired);
        // Keep Geyser's collision manager in sync, but let Bedrock resize its own box.
        // TODO: Does this alternative box trigger a rewind? Why does geyser care about this.
        entity.setDimensionsFromPose(desired);
        if (desired == org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose.SNEAKING) {
            entity.setBoundingBoxHeight(1.49F);
        }
        var confirmation = new Confirmation(pose, boundary.generation());
        if (confirmation.equals(lastSent) && !hasPoseAction(input.getInputData())) return;
        var flags = new EnumMap<EntityFlag, Boolean>(EntityFlag.class);
        for (var flag : EntityFlag.values()) if (entity.getFlag(flag)) flags.put(flag, true);
        flags.put(EntityFlag.SPRINTING, boundary.sprinting());
        var packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(entity.geyserId());
        packet.setTick(input.getTick());
        packet.getMetadata().putFlags(flags);
        send(packet, boundary.generation(), session::sendUpstreamPacket);
        lastSent = confirmation;
    }

    void send(SetEntityDataPacket packet, long generation, Consumer<SetEntityDataPacket> send) {
        pending.put(packet, generation);
        try { send.accept(packet); }
        catch (RuntimeException | Error failure) { pending.remove(packet); throw failure; }
    }

    /** Null means an ordinary Geyser packet, false means a stale queued confirmation. */
    Boolean confirmation(SetEntityDataPacket packet, long generation) {
        Long sent = pending.remove(packet);
        return sent == null ? null : sent == generation;
    }

    void rewriteOrdinary(EntityDataMap metadata) {
        metadata.remove(EntityDataTypes.WIDTH);
        metadata.remove(EntityDataTypes.HEIGHT);
        metadata.remove(EntityDataTypes.COLLISION_BOX);
        if (pose == null) return;
        if (metadata.get(EntityDataTypes.FLAGS) != null) {
            metadata.putFlags(metadata.getFlags().clone());
            pose.apply(metadata);
        }
    }

    void clear() { pose = null; lastSent = null; pending.clear(); }

    SetEntityDataPacket collisionDefinition(long runtimeId, long generation) {
        var packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(runtimeId);
        packet.getMetadata().put(EntityDataTypes.COLLISION_BOX, COLLISION_DEFINITION);
        pending.put(packet, generation);
        return packet;
    }

    private static boolean hasPoseAction(Set<PlayerAuthInputData> input) {
        return input.stream().anyMatch(flag -> switch (flag) {
            case START_SNEAKING, STOP_SNEAKING, START_GLIDING, STOP_GLIDING,
                    START_SWIMMING, STOP_SWIMMING, START_CRAWLING, STOP_CRAWLING,
                    START_SPIN_ATTACK, STOP_SPIN_ATTACK -> true;
            default -> false;
        });
    }

    record Pose(boolean sneaking, boolean gliding, boolean swimming, boolean crawling, boolean spinning) {
        static Pose read(SessionPlayerEntity entity) {
            return new Pose(entity.getFlag(EntityFlag.SNEAKING), entity.getFlag(EntityFlag.GLIDING),
                    entity.getFlag(EntityFlag.SWIMMING), entity.getFlag(EntityFlag.CRAWLING), entity.getFlag(EntityFlag.DAMAGE_NEARBY_MOBS));
        }

        Pose actions(Set<PlayerAuthInputData> input) {
            boolean sneak = sneaking, glide = gliding, swim = swimming, crawl = crawling, spin = spinning;
            // Preserve the same ordered action iteration used by Geyser's auth-input translator.
            for (var flag : input) switch (flag) {
                case START_SNEAKING -> sneak = true;
                case STOP_SNEAKING -> sneak = false;
                case START_GLIDING -> glide = true;
                case STOP_GLIDING -> glide = false;
                case START_SWIMMING -> swim = true;
                case STOP_SWIMMING -> swim = false;
                case START_CRAWLING -> crawl = true;
                case STOP_CRAWLING -> crawl = false;
                case START_SPIN_ATTACK -> spin = true;
                case STOP_SPIN_ATTACK -> spin = false;
                default -> { }
            }
            return new Pose(sneak, glide, swim, crawl, spin);
        }

        void apply(SessionPlayerEntity entity) {
            entity.setFlag(EntityFlag.SNEAKING, sneaking);
            entity.setFlag(EntityFlag.GLIDING, gliding);
            entity.setFlag(EntityFlag.SWIMMING, swimming);
            entity.setFlag(EntityFlag.CRAWLING, crawling);
            entity.setFlag(EntityFlag.DAMAGE_NEARBY_MOBS, spinning);
        }

        void apply(EntityDataMap metadata) {
            metadata.setFlag(EntityFlag.SNEAKING, sneaking);
            metadata.setFlag(EntityFlag.GLIDING, gliding);
            metadata.setFlag(EntityFlag.SWIMMING, swimming);
            metadata.setFlag(EntityFlag.CRAWLING, crawling);
            metadata.setFlag(EntityFlag.DAMAGE_NEARBY_MOBS, spinning);
        }
    }

    private record Confirmation(Pose pose, long generation) { }
}
