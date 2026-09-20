package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.player.CultPlayer;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.session.GeyserSession;

/** Vehicle controls do not authorize a change to the server's passenger graph or position. */
final class GeyserVehicleInput {
    private GeyserVehicleInput() { }

    static boolean acceptsInteraction(GeyserSession session, CultPlayer player, InteractPacket packet) {
        if (packet.getAction() != InteractPacket.Action.LEAVE_VEHICLE) return true;
        var vehicle = session.getPlayerEntity().getVehicle();
        return vehicle != null && vehicle.geyserId() == packet.getRuntimeEntityId()
                && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(vehicle.getEntityId());
    }

    static void translateRejectedMovement(GeyserSession session, CultPlayer player, PlayerAuthInputPacket packet) {
        if (!player.packetStateData.hasPendingRejectedBedrockTranslatedMovement()) return;
        session.getInputCache().processInputs(session.getPlayerEntity(), packet);
    }
}
