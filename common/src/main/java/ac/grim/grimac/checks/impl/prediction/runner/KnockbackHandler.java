package ac.grim.grimac.checks.impl.prediction.runner;

import ac.grim.grimac.checks.BedrockSupported;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.packet.PacketCodecUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

// Player velocity packets use a bundle proof when available, otherwise the Grim3.0-clean transaction sandwich.
//@CheckData(name = "AntiKB", alternativeName = "AntiKnockback", configName = "Knockback", setback = 4, decay = 0.025)
@BedrockSupported
public class KnockbackHandler extends PacketModHandler implements PostPredictionListener {
    public KnockbackHandler(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder()
            .name("AntiKB")
            .stableKey("grim.velocity.anti_knockback")
            .altName("AntiKnockback")
            .configName("Knockback")
            .description("Did not take the expected entity knockback")
            .setback(10)
            .decay(0.025)
            .build()); }

    public int handleEntityVelocity(PacketSendEvent event, ClientboundSetEntityMotionPacket velocity) {
        var motion = ac.grim.grimac.network.packet.NmsPacketUtil.readEntityMotion(velocity);
        PacketEntity vehicle = player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        int movementEntityId = vehicle != null ? vehicle.getEntityId() : player.entityID;
        if (motion.entityId() != movementEntityId) {
            return -1;
        }

        if (vehicle != null && vehicle.noAI) {
            return -1;
        }

        Vec3 playerVelocity = PacketCodecUtil.quantizeClientboundVelocity(player.getClientVersion(), motion.movement());
        if (vehicle != null) {
            return -1;
        }

        if (shouldUseBundledProof()) {
            GrimPlayer.TrackedTransaction transaction = bundlePacketWithTrailingTransaction(event, velocity);
            if (transaction != null) {
                handleEventAfterTransaction(playerVelocity, true, transaction.transaction(), motion.entityId());
                return transaction.transaction();
            }
        }

        player.sendTransaction();
        return handleEvent(playerVelocity, true, event, motion.entityId());
    }

    public void handleObservedEntityVelocity(Vec3 velocity, int entityId) {
        player.runSafely(() -> {
            // Geyser sends this marker before queueing the Bedrock motion,
            // so the first client movement affected by it sees firstBread.
            registerExternallyAcknowledgedEvent(velocity, true, entityId);
        });
    }

    public void acknowledgeObservedEntityVelocity() {
        // Geyser owns a single FIFO latency callback queue for the connection,
        // so acknowledgements arrive in the same order as their motion writes.
        player.runSafely(this::acknowledgeExternallyAcknowledgedEvent);
    }
}
