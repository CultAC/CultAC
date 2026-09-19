package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockVehicleControl {
    private BedrockVehicleControl() { }

    public static PacketEntity controlledVehicle(CultPlayer player) {
        if (player.bedrockState == null || player.bedrockState.getProtocolVersion().protocol() < 944) return null;
        var riding = player.compensatedEntities.getSelf().getRiding();
        return isSupported(riding) && !riding.isDead
                && (!riding.isBoat() || riding.bedrockBoat != null)
                && (!(riding instanceof PacketEntityHorse vehicle) || vehicle.hasSaddle)
                && riding.passengers.indexOf(player.compensatedEntities.getSelf()) == 0 ? riding : null;
    }

    public static boolean isSupported(PacketEntity entity) {
        return entity != null && (entity instanceof PacketEntityHorse && entity.type == EntityTypesCompat.HORSE
                || entity.isBoat());
    }

    public static boolean matches(BedrockAuthInputFrame frame, PacketEntity vehicle) {
        return (!vehicle.isBoat() || vehicle.bedrockBoat != null
                    && java.util.Objects.equals(frame.getPredictedVehicleId(), vehicle.bedrockBoat.runtimeId()))
                && frame.hasMinimumStrictData()
                && Double.isFinite(frame.getPosition().x) && Double.isFinite(frame.getPosition().y)
                && Double.isFinite(frame.getPosition().z) && Float.isFinite(frame.getYaw()) && Float.isFinite(frame.getPitch())
                && frame.getPredictedVehicleJavaId() != null
                && frame.getPredictedVehicleJavaId() == vehicle.getEntityId()
                && frame.getPredictedVehicleId() != null && frame.getPredictedVehicleId() != -1L
                && frame.getVehicleRotation() != null
                && Float.isFinite(frame.getVehicleRotation().yaw())
                && Float.isFinite(frame.getVehicleRotation().pitch())
                && frame.hasRawInputFlag(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
    }
}
