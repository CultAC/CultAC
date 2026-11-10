package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.junit.Test;

public final class GfpEntityTeleportRebaseTest {
    // A capture of a server teleport of a ridden boat: the session origin is (20080, 20128), the rider reports
    // local (-46.5, 118.8125, 18.396803), and the boat's move reached the client as (20035.0, 118.79195, 20146.396).
    private static final Vector3i ORIGIN = Vector3i.from(20080, 0, 20128);
    private static final Vector3d WORLD = Vector3d.from(20035.0D, 118.79195D, 20146.396D);

    @Test
    public void absoluteTeleportIsMovedOntoTheSessionOrigin() {
        ClientboundTeleportEntityPacket teleport = teleport(WORLD, List.of());

        var rebased = (ClientboundTeleportEntityPacket) GfpEntityTeleportRebase.apply(teleport, teleport, ORIGIN);

        assertEquals(-45.0D, rebased.getPosition().getX(), 0.0D);
        assertEquals(118.79195D, rebased.getPosition().getY(), 0.0D);
        assertEquals(18.396D, rebased.getPosition().getZ(), 1.0E-9D);
        assertEquals(teleport.getId(), rebased.getId());
        assertEquals(teleport.getDeltaMovement(), rebased.getDeltaMovement());
        assertEquals(teleport.getYRot(), rebased.getYRot(), 0.0F);
        assertEquals(teleport.getXRot(), rebased.getXRot(), 0.0F);
        assertEquals(teleport.getRelatives(), rebased.getRelatives());
        assertEquals(teleport.isOnGround(), rebased.isOnGround());
    }

    @Test
    public void relativeElementsKeepTheirOffsets() {
        ClientboundTeleportEntityPacket teleport =
                teleport(Vector3d.from(4.0D, 118.79195D, 20146.396D), List.of(PositionElement.X, PositionElement.Y));

        var rebased = (ClientboundTeleportEntityPacket) GfpEntityTeleportRebase.apply(teleport, teleport, ORIGIN);

        assertEquals(4.0D, rebased.getPosition().getX(), 0.0D);
        assertEquals(118.79195D, rebased.getPosition().getY(), 0.0D);
        assertEquals(18.396D, rebased.getPosition().getZ(), 1.0E-9D);

        ClientboundTeleportEntityPacket relative =
                teleport(Vector3d.from(4.0D, 0.0D, 0.0D), List.of(PositionElement.X, PositionElement.Z));
        assertSame(relative, GfpEntityTeleportRebase.apply(relative, relative, ORIGIN));
    }

    @Test
    public void teleportThatGfpRewroteIsNotMovedAgain() {
        ClientboundTeleportEntityPacket teleport = teleport(WORLD, List.of());
        ClientboundTeleportEntityPacket local = teleport.withPosition(Vector3d.from(-45.0D, 118.79195D, 18.396D));

        assertSame(local, GfpEntityTeleportRebase.apply(teleport, local, ORIGIN));
    }

    @Test
    public void unshiftedSessionAndOtherPacketsPassThrough() {
        ClientboundTeleportEntityPacket teleport = teleport(WORLD, List.of());
        Packet other = new ClientboundRemoveEntitiesPacket(new int[] {3977});

        assertSame(teleport, GfpEntityTeleportRebase.apply(teleport, teleport, Vector3i.ZERO));
        assertSame(other, GfpEntityTeleportRebase.apply(other, other, ORIGIN));
        assertNull(GfpEntityTeleportRebase.apply(teleport, null, ORIGIN));
    }

    private static ClientboundTeleportEntityPacket teleport(Vector3d position, List<PositionElement> relatives) {
        return new ClientboundTeleportEntityPacket(
                3977, position, Vector3d.from(0.0D, -0.04D, 0.0D), 88.59375F, 0.0F, relatives, false);
    }
}
