package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.player.CultPlayer;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.session.GeyserSession;

/** Vehicle controls do not authorize a change to the server's passenger graph or position. */
final class GeyserVehicleInput {
    private GeyserVehicleInput() { }

    static boolean acceptsMovement(GeyserSession session, CultPlayer player) {
        var vehicle = session.getPlayerEntity().getVehicle();
        return vehicle != null && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(vehicle.getEntityId())
                && !player.getSetbackTeleportUtil().shouldBlockVehicleMovement(true) && !player.isInBed;
    }

    static void observe(GeyserSession session, CultPlayer player, PlayerAuthInputPacket packet) {
        var flags = packet.getInputData();
        var motion = packet.getMotion();
        player.packetStateData.knownInput = new ac.cult.cultac.utils.data.KnownInput(
                motion.getY() > 0, motion.getY() < 0, motion.getX() > 0, motion.getX() < 0,
                flags.contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.JUMP_CURRENT_RAW)
                        || flags.contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.JUMP_DOWN)
                        || flags.contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.AUTO_JUMPING_IN_WATER),
                session.isSneaking() || session.isShouldSendSneak(), session.isSprinting());
        player.isSneaking = session.isSneaking();
    }

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
