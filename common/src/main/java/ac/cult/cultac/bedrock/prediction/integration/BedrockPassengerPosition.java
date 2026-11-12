package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;

/**
 * The client seats remote passengers from the vehicle's render position and SEAT_OFFSET. Rotation and translation
 * use floats in the client's coordinate frame. Resolve the chain lazily, keeping the own-tree positioning pass.
 */
public final class BedrockPassengerPosition {
    private BedrockPassengerPosition() {}

    public static SimpleCollisionBox box(CultPlayer player, PacketEntity passenger, SimpleCollisionBox out) {
        return box(player, passenger, out, player.compensatedEntities.entityMap.size());
    }

    private static SimpleCollisionBox box(
            CultPlayer player, PacketEntity passenger, SimpleCollisionBox out, int remaining) {
        PacketEntity vehicle = passenger.getRiding();
        if (vehicle == null || remaining < 0) {
            Vec3 feet = passenger.clientPhysicalPosition;
            return GetBoundingBox.getPacketEntityBoundingBox(out, player, feet.x, feet.y, feet.z, passenger);
        }
        // Read the position box, before a shulker's shell can move its centre or feet.
        box(player, vehicle, out, remaining - 1);
        var coordinates = player.getSetbackTeleportUtil().bedrockTeleports().getActiveBedrockCoordinateFrame();
        float x = coordinates.localX((out.minX + out.maxX) * 0.5);
        float y = (float) (out.minY + vehicle.bedrockPositionOffset);
        float z = coordinates.localZ((out.minZ + out.maxZ) * 0.5);
        Vec3 seat = passenger.bedrockSeatOffset;
        float angle = (vehicle.clientPhysicalYaw + (vehicle.isBoat() ? 90.0F : 0.0F)) * BedrockMath.DEGREES_TO_RADIANS;
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        float seatX = (float) seat.x, seatY = (float) seat.y, seatZ = (float) seat.z;
        return GetBoundingBox.getPacketEntityBoundingBox(
                out,
                player,
                coordinates.originX() + (double) (x + (seatX * cos - seatZ * sin)),
                (y + seatY) - passenger.bedrockPositionOffset,
                coordinates.originZ() + (double) (z + (seatX * sin + seatZ * cos)),
                passenger);
    }
}
