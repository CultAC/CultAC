package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.TransactionVel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

// Player velocity packets use a bundle proof when available, otherwise the Cult3.0-clean transaction sandwich.
//@CheckData(name = "AntiKB", alternativeName = "AntiKnockback", configName = "Knockback", setback = 4, decay = 0.025)
@BedrockSupported
public class KnockbackHandler extends PacketModHandler implements PostPredictionListener {
    public KnockbackHandler(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder()
            .name("AntiKB")
            .stableKey("cult.velocity.anti_knockback")
            .altName("AntiKnockback")
            .configName("Knockback")
            .description("Did not take the expected entity knockback")
            .setback(10)
            .decay(0.025)
            .build()); }

    public int handleEntityVelocity(PacketSendEvent event, ClientboundSetEntityMotionPacket velocity) {
        if (player.isBedrockMovement()) {
            // The Geyser wire observer owns Bedrock motion and its receipt proof.
            return -1;
        }
        var motion = ac.cult.cultac.network.packet.NmsPacketUtil.readEntityMotion(velocity);
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
            CultPlayer.TrackedTransaction transaction = bundlePacketWithTrailingTransaction(event, velocity);
            if (transaction != null) {
                handleEventAfterTransaction(playerVelocity, true, transaction.transaction(), motion.entityId());
                return transaction.transaction();
            }
        }

        player.sendTransaction();
        return handleEvent(playerVelocity, true, event, motion.entityId());
    }

    public void handleObservedEntityVelocity(Vec3 velocity, int entityId, long teleportRevision,
                                             CultPlayer.BedrockTransaction before, CultPlayer.BedrockTransaction receipt) {
        var entry = new TransactionVel.Bedrock(velocity, receipt.transaction(), isSetbackVal, entityId, teleportRevision);
        player.runSafely(() -> {
            lastSent = entry;
            player.addBedrockTransactionTask(before, () -> makeVelocityPossible(entry, velocity));
            player.addBedrockTransactionTask(receipt, () -> confirmVelocity(entry, velocity));
        });
    }
}
