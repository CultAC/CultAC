package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ClientPacketListener#handleMovePlayer rotates the kept velocity from the rotation the client holds when it handles
 * the packet. With a second player_position sent before the first is accepted, that is the first one's rotation, not
 * the one the client had reported when the second was sent.
 */
class JavaTeleportSourceRotationTest {
    private static final Vec3 TARGET = new Vec3(2.5, 64, 2.5);
    private static final Vec3 VELOCITY = new Vec3(0.2, -0.1, 0.3);
    private static final Set<Relative> KEPT =
            EnumSet.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z, Relative.ROTATE_DELTA);

    @Test
    void aTeleportSentBeforeTheLastOneIsAcceptedRotatesFromThatOnesYaw() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            // The first player_position of a session is the spawn placement, which sets the rotation itself.
            f.playerPosition(new Vec3(0.5, 64, 0.5), Vec3.ZERO, Set.of());
            f.place(new Vec3(0.5, 64, 0.5), VELOCITY);
            setField(
                    SimulationProcessor.class,
                    f.player.checkManager.getSimulationProcessor(),
                    "profileCarry",
                    new JavaPredictionCarry(
                            f.player.compensatedEntities.getSelf(),
                            0.0,
                            List.of(new JavaPredictionCarry.State(VELOCITY, 0.0, Vec3.ZERO))));

            int first = send(f, 101, 20.0F);
            int second = send(f, 102, 40.0F);
            f.pongThrough(first);
            accept(f, 101, 20.0F);
            f.pongThrough(second);
            accept(f, 102, 40.0F);

            Vec3 turned = VELOCITY.yRot(ClientVersion.V_26_3, (float) Math.toRadians(0.0F - 20.0F))
                    .yRot(ClientVersion.V_26_3, (float) Math.toRadians(20.0F - 40.0F));
            var carry = (JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            Vec3 velocity = carry.states().getFirst().velocity();
            assertEquals(turned.x, velocity.x, 1e-12);
            assertEquals(turned.y, velocity.y, 1e-12);
            assertEquals(turned.z, velocity.z, 1e-12);
        }
    }

    /** A player_position at {@link #TARGET} and an absolute {@code yaw}, keeping velocity; its leading ping. */
    private static int send(JavaTickFixture f, int id, float yaw) {
        var packet = new ClientboundPlayerPosition(
                id, new Vec3d(TARGET.x, TARGET.y, TARGET.z), new Vec3d(0, 0, 0), yaw, 0, KEPT);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        return leading;
    }

    private static void accept(JavaTickFixture f, int id, float yaw) {
        var packet = new ServerboundAcceptTeleportation(id, new Vec3d(TARGET.x, TARGET.y, TARGET.z), yaw, 0);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the teleport acceptance was rejected");
    }
}
