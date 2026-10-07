package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;

// @CheckData(name = "AntiExplosion", configName = "Explosion", setback = 4)
@BedrockSupported
public class ExplosionHandler extends PacketModHandler {

    public ExplosionHandler(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("AntiExplosion")
                        .stableKey("cult.velocity.anti_explosion")
                        .configName("Explosion")
                        .description("Did not take the expected explosion knockback")
                        .setback(10)
                        .build());
    }

    @Override
    protected String getAdvantageConfigPath() {
        return "cult.checks.explosion";
    }

    @CultPacketHandler
    public void onExplode(PacketSendEvent<ClientboundExplode> event, CultPlayer player, ClientboundExplode packet) {
        // The player will be in a vehicle when this packet arrives, don't bother
        if (player.compensatedEntities.vehicles.serverPlayerVehicle != null) return;

        Vec3 velocity = new Vec3(
                packet.knockback().x(),
                packet.knockback().y(),
                packet.knockback().z());

        if (velocity.x != 0 || velocity.y != 0 || velocity.z != 0) {
            if (shouldUseBundledProof()) {
                CultPlayer.TrackedTransaction transaction = bundlePacketWithTrailingTransaction(event, packet);
                if (transaction == null) {
                    player.sendTransaction();
                    handleEvent(velocity, false, event, TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
                    return;
                }

                handleEventAfterTransaction(
                        velocity, false, transaction.transaction(), TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
                return;
            }

            player.sendTransaction();
            handleEvent(velocity, false, event, TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
        }
    }
}
