package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.utils.data.TransactionVel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;

//@CheckData(name = "AntiExplosion", configName = "Explosion", setback = 4)
@BedrockSupported
public class ExplosionHandler extends PacketModHandler {

    public ExplosionHandler(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder()
            .name("AntiExplosion")
            .stableKey("cult.velocity.anti_explosion")
            .configName("Explosion")
            .description("Did not take the expected explosion knockback")
            .setback(10)
            .build()); }

    @Override
    protected String getAdvantageConfigPath() {
        return "cult.checks.explosion";
    }

    @CultPacketHandler
    public void onExplode(PacketSendEvent event, CultPlayer player, ClientboundExplodePacket packet) {
        // The player will be in a vehicle when this packet arrives, don't bother
        if (player.compensatedEntities.vehicles.serverPlayerVehicle != null) return;

        Vec3 velocity = NmsPacketUtil.readExplosionKnockback(packet);

        if (velocity.x != 0 || velocity.y != 0 || velocity.z != 0) {
            if (shouldUseBundledProof()) {
                CultPlayer.TrackedTransaction transaction = bundlePacketWithTrailingTransaction(event, packet);
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
