package ac.grim.grimac.checks.impl.prediction.runner;

import ac.grim.grimac.checks.BedrockSupported;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.utils.data.TransactionVel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;

//@CheckData(name = "AntiExplosion", configName = "Explosion", setback = 4)
@BedrockSupported
public class ExplosionHandler extends PacketModHandler {

    public ExplosionHandler(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder()
            .name("AntiExplosion")
            .stableKey("grim.velocity.anti_explosion")
            .configName("Explosion")
            .description("Did not take the expected explosion knockback")
            .setback(10)
            .build()); }

    @GrimPacketHandler
    public void onExplode(PacketSendEvent event, GrimPlayer player, ClientboundExplodePacket packet) {
        // The player will be in a vehicle when this packet arrives, don't bother
        if (player.compensatedEntities.vehicles.serverPlayerVehicle != null) return;

        Vec3 velocity = NmsPacketUtil.readExplosionKnockback(packet);

        if (velocity.x != 0 || velocity.y != 0 || velocity.z != 0) {
            if (shouldUseBundledProof()) {
                GrimPlayer.TrackedTransaction transaction = bundlePacketWithTrailingTransaction(event, packet);
                if (transaction == null) {
                    player.sendTransaction();
                    handleEvent(velocity, false, event, TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
                    return;
                }

                handleEventAfterTransaction(velocity, false, transaction.transaction(), TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
                return;
            }

            player.sendTransaction();
            handleEvent(velocity, false, event, TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
        }
    }
}
