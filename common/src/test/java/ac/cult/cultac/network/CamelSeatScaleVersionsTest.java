package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Camel#getBodyAnchorAnimationYOffset drops the seat by 0.375F * getScale() * getAgeScale() through 26.1.2, and by a
 * fixed 0.375 (adult) or 0.09375 (baby) from 26.2. A player's feet sit Player.DEFAULT_VEHICLE_ATTACHMENT's 0.6 below.
 */
class CamelSeatScaleVersionsTest {
    private static final int CAMEL = 100;
    private static final double CAMEL_Y = 64.0;
    private static final long GAME_TIME = 100_000L;
    private static final float ADULT_HEIGHT = 2.375F;
    private static final float OLD_BABY_SCALE = 0.45F;

    @Test
    void scaledAdultCamelsDropTheSeatWithTheirScaleBefore26_2() throws Exception {
        double scaledDrop = feet((double) (ADULT_HEIGHT * 2.0F - 0.375F * 2.0F));
        assertEquals(scaledDrop, riderFeetY(ProtocolVersion.V1_21_3, ClientVersion.V_1_21_2, false, 2.0F));
        assertEquals(scaledDrop, riderFeetY(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11, false, 2.0F));
        assertEquals(scaledDrop, riderFeetY(ProtocolVersion.V26_1, ClientVersion.V_26_1, false, 2.0F));
    }

    @Test
    void scaledAdultCamelsDropTheSeatByAFixedAmountFrom26_2() throws Exception {
        double fixedDrop = feet(ADULT_HEIGHT * 2.0F - 0.375);
        assertEquals(fixedDrop, riderFeetY(ProtocolVersion.V26_2, ClientVersion.V_26_2, false, 2.0F));
        assertEquals(fixedDrop, riderFeetY(new JavaTickFixture(), false, 2.0F));
    }

    @Test
    void scaledBabyCamelsDropTheSeatWithScaleAndAgeBefore26_2() throws Exception {
        float scale = 2.0F * OLD_BABY_SCALE;
        double expected = feet((double) (ADULT_HEIGHT * OLD_BABY_SCALE * 2.0F - 0.375F * scale));
        assertEquals(expected, riderFeetY(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11, true, 2.0F));
    }

    /** Entity#positionRider: the seat, cast to float by Camel#getPassengerAttachmentPoint, less the rider's 0.6. */
    private static double feet(double seat) {
        return CAMEL_Y + (float) seat - 0.6;
    }

    private static double riderFeetY(ProtocolVersion protocol, ClientVersion client, boolean baby, float scale)
            throws Exception {
        return riderFeetY(new JavaTickFixture(protocol, client), baby, scale);
    }

    /** The local player's feet on a standing camel, long past its last pose change. */
    private static double riderFeetY(JavaTickFixture fixture, boolean baby, float scale) throws Exception {
        try (var f = fixture) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, new Vec3(0.5, CAMEL_Y, 0.5), 0, 0, 0);
            var camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.isBaby = baby;
            camel.scale = scale;
            camel.lastPoseChangeTick = GAME_TIME - 1000L;
            var box = new SimpleCollisionBox(0.0, CAMEL_Y, 0.0, 1.0, CAMEL_Y + 1.0, 1.0);
            var self = f.player.compensatedEntities.getSelf();
            return BoundingBoxSize.getRidingOffsetFromVehicle(camel, self, f.player, box, 0, 1).y;
        }
    }
}
