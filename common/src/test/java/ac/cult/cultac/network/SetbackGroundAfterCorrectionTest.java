package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A movement a setback rejects must not leave its ground behind. ClientPacketListener#handleMovePlayer moves the player
 * without touching onGround, so a correction also sets the client's ground: two teleport_entity packets for the player
 * itself, a hop away and back, each farther than #setValuesFromPositionPacket interpolates, whose
 * #handleTeleportEntity calls Entity#setOnGround. The client's next tick starts from exactly the ground and supporting
 * block the correction sent, never from the rejected movement's.
 */
class SetbackGroundAfterCorrectionTest {
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98F;
    private static final double JUMP = 0.41999998688697815D;
    private static final Vec3 AGAINST_THE_WALL = new Vec3(1.6999, FLOOR_Y, 0.5);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 ON_THE_ICE = new Vec3(6.5, FLOOR_Y, 0.5);
    private static final Vec3 ACROSS_THE_FLOOR = new Vec3(4.5, FLOOR_Y, 0.5);
    private static final int SERVER_TELEPORT = 77;
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMPING =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final Object DELIMITER = new Object();

    /**
     * A client that jumps against a stone block topped with a bottom slab, sends a glide start it cannot make and a
     * floor position claiming ground at the apex, then answers the correction, starts its next tick in the air and
     * cannot step onto the slab. Positions come from the live bypass of 2026-11-06, moved onto the fixture floor.
     */
    @Test
    void aRejectedGroundClaimDoesNotOutliveTheCorrection() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            int slab = DataTables.defaults()
                    .registry()
                    .block("minecraft:stone_slab")
                    .defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y + 1, 0), slab);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            settle(f, AGAINST_THE_WALL);

            // An ordinary jump up to its apex.
            f.input(JUMPING);
            Vec3 at = AGAINST_THE_WALL;
            double motion = JUMP;
            for (int tick = 0; tick < 6; tick++) {
                at = at.add(0, motion, 0);
                PredictionResult rising = f.tick(at, false);
                assertTrue(exact(rising), "jump tick " + tick + ": " + describe(rising));
                motion = (motion - GRAVITY) * VERTICAL_DRAG;
                if (tick == 0) f.input(NONE);
            }
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            written(f);

            // A glide start without a glider, then a floor position claiming ground.
            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            assertTrue(teleports.isPendingSetback(), "the glide start without a glider was not set back");
            f.move(AGAINST_THE_WALL, true);
            f.tickEnd();
            SetBackData correction = teleports.getRequiredSetBack();
            assertFalse(correction.isExpectedOnGround(), "the correction tick at the apex cannot land");
            assertSetsGround(f, written(f), correction, false);

            accept(f, correction);
            assertFalse(teleports.isPendingSetback());
            assertEquals(
                    DesyncStatus.FALSE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround(),
                    "the rejected floor claim's ground outlived the correction");
        }
    }

    /**
     * A server teleport sent before the client answers the correction replaces the setback the client must accept, but
     * not the ground the correction's bundle set. The client handles that bundle, then the server's player_position,
     * which ClientPacketListener#handleMovePlayer applies without touching onGround, so it stays in the air even on the
     * floor.
     */
    @Test
    void aServerTeleportBeforeTheAnswerKeepsTheCorrectionsGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            settle(f, STANDING);

            // An ordinary jump up to its apex.
            f.input(JUMPING);
            Vec3 at = STANDING;
            double motion = JUMP;
            for (int tick = 0; tick < 6; tick++) {
                at = at.add(0, motion, 0);
                PredictionResult rising = f.tick(at, false);
                assertTrue(exact(rising), "jump tick " + tick + ": " + describe(rising));
                motion = (motion - GRAVITY) * VERTICAL_DRAG;
                if (tick == 0) f.input(NONE);
            }
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            written(f);

            // A glide start without a glider, then a floor position claiming ground.
            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            assertTrue(teleports.isPendingSetback(), "the glide start without a glider was not set back");
            f.move(STANDING, true);
            f.tickEnd();
            SetBackData correction = teleports.getRequiredSetBack();
            assertSetsGround(f, written(f), correction, false);
            assertEquals(
                    DesyncStatus.TRUE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround(),
                    "the rejected floor claim left no ground for the correction to replace");

            // The server teleports the player onto the floor before the correction's answer arrives.
            serverTeleport(f, SERVER_TELEPORT, ACROSS_THE_FLOOR);
            SetBackData server = teleports.getRequiredSetBack();
            assertNotSame(correction, server, "the server teleport did not replace the required correction");

            accept(f, correction);
            accept(f, server);
            assertFalse(teleports.javaTeleports().hasPendingPlayerPositionTeleport());
            assertEquals(
                    DesyncStatus.FALSE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround(),
                    "the server teleport discarded the ground the correction's bundle set");
        }
    }

    /**
     * A correction onto the floor leaves the client on the ground, on the block under the correction: its next tick
     * jumps, and it starts from the stone there, not from the ice six blocks away that the rejected movement claimed.
     */
    @Test
    void aCorrectionOntoTheFloorSetsItsGroundAndSupportingBlock() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            int ice = DataTables.defaults().registry().block("minecraft:ice").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(6, FLOOR_Y - 1, 0), ice);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            settle(f, STANDING);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            written(f);

            // A glide start without a glider, then a move onto the ice claiming ground.
            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            assertTrue(teleports.isPendingSetback(), "the glide start without a glider was not set back");
            f.move(ON_THE_ICE, true);
            f.tickEnd();
            SetBackData correction = teleports.getRequiredSetBack();
            Vec3 target = correction.getTeleportData().getLocation();
            assertEquals(STANDING.x, target.x);
            assertEquals(STANDING.y, target.y);
            assertEquals(STANDING.z, target.z);
            assertSetsGround(f, written(f), correction, true);

            accept(f, correction);
            assertEquals(
                    DesyncStatus.TRUE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());

            f.input(JUMPING);
            PredictionResult jump = f.tick(STANDING.add(0, JUMP, 0), false);
            assertTrue(exact(jump), "the jump from the correction's ground: " + describe(jump));
            assertEquals(
                    new BlockPos(0, FLOOR_Y - 1, 0),
                    jump.getSimulationContext()
                            .getLastTickMainSupportingBlockData()
                            .getBlockPos(),
                    "the tick after the correction did not start on the block under it");
        }
    }

    /** Places the player at {@code at} on the floor and settles it with two exact idle ticks. */
    private static void settle(JavaTickFixture f, Vec3 at) {
        f.place(at, GROUNDED);
        f.playerPosition(at, Vec3.ZERO, Set.of());
        f.pongThrough(f.player.sendTrailingTransaction());
        f.input(NONE);
        f.tick(at, true);
        PredictionResult settled = f.tick(at, true);
        assertTrue(exact(settled), describe(settled));
    }

    /**
     * The correction's player_position is followed in the same client bundle by the hop away, not on the ground, and
     * the hop back to its exact target with {@code onGround}. Both keep the rotation and delta the correction left.
     */
    private static void assertSetsGround(
            JavaTickFixture f, List<Object> written, SetBackData correction, boolean onGround) {
        int at = -1;
        for (int i = 0; i < written.size(); i++) {
            if (written.get(i) instanceof ClientboundPlayerPosition position
                    && position.teleportId() == correction.getTeleportData().getTeleportId()) {
                at = i;
            }
        }
        assertTrue(at >= 0, "the correction was not written: " + written);
        assertTrue(at + 2 < written.size(), "the correction set no ground: " + written);
        long delimiters =
                written.subList(0, at).stream().filter(w -> w == DELIMITER).count();
        assertEquals(1, delimiters % 2, "the correction is outside a client bundle: " + written);
        var away = assertInstanceOf(ClientboundTeleportEntity.class, written.get(at + 1), written.toString());
        var back = assertInstanceOf(ClientboundTeleportEntity.class, written.get(at + 2), written.toString());
        Set<Relative> unchanged =
                EnumSet.of(Relative.Y_ROT, Relative.X_ROT, Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
        Vec3 target = correction.getTeleportData().getLocation();
        for (ClientboundTeleportEntity hop : List.of(away, back)) {
            assertEquals(f.player.entityID, hop.entityId());
            assertEquals(unchanged, hop.relatives());
            assertEquals(Vec3d.ZERO, hop.delta());
            assertEquals(0.0F, hop.yaw());
            assertEquals(0.0F, hop.pitch());
        }
        // ClientPacketListener#setValuesFromPositionPacket interpolates a move with distanceToSqr <= 4096.
        double dx = away.position().x() - target.x;
        double dy = away.position().y() - target.y;
        double dz = away.position().z() - target.z;
        assertTrue(dx * dx + dy * dy + dz * dz > 4096.0D, "the hop away interpolates: " + away);
        assertFalse(away.onGround());
        assertEquals(new Vec3d(target.x, target.y, target.z), back.position());
        assertEquals(onGround, back.onGround());
    }

    /** Every packet the encoder wrote since the last call, in order, with {@link #DELIMITER} for bundle delimiters. */
    private static List<Object> written(JavaTickFixture f) {
        f.fixture.channel.runPendingTasks();
        f.fixture.channel.flushOutbound();
        List<Object> written = new ArrayList<>();
        Object message;
        while ((message = f.fixture.channel.readOutbound()) != null) {
            if (!(message instanceof ByteBuf frame)) {
                ReferenceCountUtil.release(message);
                continue;
            }
            try {
                int id = Wire.readVarInt(frame);
                if (f.fixture.codec.typeOf(PacketDirection.CLIENTBOUND, id) == ClientboundPackets.BUNDLE_DELIMITER) {
                    written.add(DELIMITER);
                } else {
                    written.add(f.fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame));
                }
            } finally {
                frame.release();
            }
        }
        return written;
    }

    /** The server's absolute player_position {@code id} to {@code target}, sent but not yet answered. */
    private static void serverTeleport(JavaTickFixture f, int id, Vec3 target) {
        var packet = new ClientboundPlayerPosition(id, JavaTickFixture.vec3d(target), Vec3d.ZERO, 0, 0, Set.of());
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** The client handles the teleport {@code setback}: it answers the teleport's ping and accepts its exact target. */
    private static void accept(JavaTickFixture f, SetBackData setback) {
        var teleport = setback.getTeleportData();
        f.pongThrough(teleport.getTransaction());
        var at = teleport.getLocation();
        var packet = new ServerboundAcceptTeleportation(
                teleport.getTeleportId(), JavaTickFixture.vec3d(at), f.player.xRot, f.player.yRot);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the teleport's acceptance was rejected");
    }
}
