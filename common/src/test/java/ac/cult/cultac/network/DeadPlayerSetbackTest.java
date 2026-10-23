package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/** A dead client sends no movement, and a respawn places a new player. */
class DeadPlayerSetbackTest {
    private static final Vec3 FALLING = new Vec3(.5, JavaTickFixture.FLOOR_Y + 3, .5);
    private static final Vec3 FALL_VELOCITY = new Vec3(0, -.5, 0);
    private static final Vec3 SPAWN = new Vec3(4.5, JavaTickFixture.FLOOR_Y, 4.5);

    @Test
    void aDeadPlayerPastTheFreezeTimeGetsNoSetback() throws Exception {
        try (var f = fallingPlayer()) {
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            f.player.compensatedEntities.getSelf().isDead = true;

            teleports.checkIfMustMove();
            assertNull(teleports.getRequiredSetBack(), "a dead client has no movement to wait for");
            assertFalse(teleports.executeTooHighLatencySetback("kb 1200ms"));
            assertNull(teleports.getRequiredSetBack());

            f.player.compensatedEntities.getSelf().isDead = false;
            teleports.checkIfMustMove();
            assertTrue(teleports.isPendingSetback(), "a living client that stops moving is still set back");
        }
    }

    @Test
    void aLegalMoveAfterRespawningFromABlockingSetbackIsNotBlocked() throws Exception {
        try (var f = fallingPlayer()) {
            CultPlayer player = f.player;
            SetbackTeleportUtil teleports = player.getSetbackTeleportUtil();
            teleports.executeForceResync("phase");
            assertTrue(teleports.blockOffsets);
            player.compensatedEntities.getSelf().isDead = true;

            respawn(f);
            assertFalse(teleports.blockOffsets, "the respawned player is not the one the setback corrected");
            int placement = placeAt(f, SPAWN);
            // The latency watchdog keeps running while the placement is in flight.
            teleports.checkIfMustMove();
            assertEquals(
                    placement, teleports.getRequiredSetBack().getTeleportData().getTeleportId());

            acceptInOrder(f);
            player.packetStateData.playerLoadedIntoLevel = true;

            assertFalse(f.move(SPAWN, true).isCancelled(), teleports.getDebugStrings());
            assertFalse(teleports.blockOffsets);
            assertTrue(teleports.getRequiredSetBack().isComplete());
        }
    }

    @Test
    void anUndeliveredLatencySetbackAfterTheRespawnPlacementDoesNotBlockMovement() throws Exception {
        try (var f = fallingPlayer()) {
            CultPlayer player = f.player;
            SetbackTeleportUtil teleports = player.getSetbackTeleportUtil();
            f.teleport(FALLING, new RelativeFlag(0));
            respawn(f);
            placeAt(f, SPAWN);
            acceptInOrder(f);
            assertTrue(teleports.getRequiredSetBack().isComplete());
            player.packetStateData.playerLoadedIntoLevel = true;

            // A latency setback from a chunk the client has not proven forces a full resync, but it is dropped:
            // CultAC-authored play packets have no level to reach.
            player.x = player.z = 40.5;
            player.user.getCultConnection().placedClientbound(ClientboundPackets.LOGIN_FINISHED);
            assertTrue(teleports.insideUnloadedChunk());
            teleports.executeTooHighLatencySetback("move, 6000ms");

            assertFalse(teleports.blockOffsets, "no tracked teleport can release it");
            assertTrue(teleports.getRequiredSetBack().isComplete());
            player.x = player.z = SPAWN.x;
            assertFalse(teleports.shouldBlockMovement());
        }
    }

    @Test
    void aPlayerThatRespawnsAfterTheFreezeTimeGetsNoLatencySetbackBeforeItMoves() throws Exception {
        try (var f = fallingPlayer()) {
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            f.teleport(FALLING, new RelativeFlag(0));
            f.player.compensatedEntities.getSelf().isDead = true;
            respawn(f);
            f.player.compensatedEntities.getSelf().isDead = false;
            placeAt(f, SPAWN);
            acceptInOrder(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            assertTrue(teleports.getRequiredSetBack().isComplete());

            assertTrue(
                    f.player
                            .checkManager
                            .getSimulationProcessor()
                            .getLastMovementTime()
                            .isEmpty(),
                    "the latency clock starts at the placed player's first move, not the last one before it died");
            teleports.checkIfMustMove();
            assertTrue(teleports.getRequiredSetBack().isComplete());
            assertFalse(f.move(SPAWN, true).isCancelled(), teleports.getDebugStrings());
        }
    }

    /**
     * ClientPacketListener#handleRespawn hands the old LocalPlayer's motion to the new one only with KEEP_ENTITY_DATA,
     * and only a placement keeping the delta keeps it for the first move after the level loads.
     */
    @ParameterizedTest(name = "keep entity data {0}, dimension change {1}, relative delta {2}")
    @CsvSource({
        "true, false, true",
        "true, true, true",
        "false, false, true",
        "false, true, true",
        "true, false, false",
        "false, false, false"
    })
    void onlyAKeptPlayerCarriesMovesSentBeforeTheRespawnIsReadIntoARelativeDeltaPlacement(
            boolean keep, boolean dimensionChange, boolean relativeDelta) throws Exception {
        try (var f = fallingPlayer()) {
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.getSetbackTeleportUtil().insideUnloadedChunk());
            String dimension = dimensionChange
                    ? "minecraft:the_end"
                    : f.player.compensatedWorld.getLastClientboundDimension().dimension();
            respawn(f, keep ? ClientboundRespawn.KEEP_ENTITY_DATA : 0, dimension);
            int respawnRead = f.player.lastTransactionSent.get();

            // The old LocalPlayer ticks on until the client reads the respawn.
            double motion = FALL_VELOCITY.y;
            Vec3 position = FALLING;
            for (int tick = 0; tick < 2; tick++) {
                position = position.add(0, motion, 0);
                assertExact(f.tick(position, false));
                motion = (motion - 0.08) * 0.98F;
            }

            f.pongThrough(respawnRead);
            Vec3 placed = new Vec3(SPAWN.x, SPAWN.y + 10, SPAWN.z);
            placeAt(f, placed, relativeDelta ? Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z) : Set.of());
            acceptInOrder(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;

            assertExact(f.tick(placed.add(0, keep && relativeDelta ? motion : 0, 0), false));
        }
    }

    @Test
    void aMoveAfterTheRespawnIsReadWaitsForThePlacement() throws Exception {
        try (var f = fallingPlayer()) {
            respawn(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            placeAt(f, SPAWN);

            assertFalse(f.player.getSetbackTeleportUtil().insideUnloadedChunk());
            assertTrue(f.move(FALLING.add(FALL_VELOCITY), false).isCancelled());
        }
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
    }

    /** A client falling past its freeze time: it last moved six seconds ago and must still be moving. */
    private static JavaTickFixture fallingPlayer() throws Exception {
        var f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(FALLING, FALL_VELOCITY);
        f.player.getSetbackTeleportUtil().lastKnownGoodPosition = new SetbackPosWithVector(FALLING, FALL_VELOCITY, 0);
        f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(System.nanoTime() - 6_000_000_000L);
        return f;
    }

    /** The server's respawn, not yet answered by the client. */
    private static void respawn(JavaTickFixture f) {
        respawn(f, 0, f.player.compensatedWorld.getLastClientboundDimension().dimension());
    }

    private static void respawn(JavaTickFixture f, int dataToKeep, String dimension) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                dimension,
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawnInfo, (byte) dataToKeep);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** The server's teleport placing the respawned player, not yet answered; its id. */
    private static int placeAt(JavaTickFixture f, Vec3 target) {
        return placeAt(f, target, Set.of());
    }

    private static int placeAt(JavaTickFixture f, Vec3 target, Set<Relative> relatives) {
        int id = 1000;
        var packet = new ClientboundPlayerPosition(
                id, new Vec3d(target.x, target.y, target.z), new Vec3d(0, 0, 0), 0, 0, relatives);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return id;
    }

    /** The vanilla client handles every sent packet in order, accepting each teleport where it puts it. */
    private static void acceptInOrder(JavaTickFixture f) {
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        TeleportData pending;
        while ((pending = teleports.pendingTeleports.peek()) != null) {
            f.pongThrough(pending.getTransaction());
            Vec3 target = pending.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    pending.getTeleportId(), new Vec3d(target.x, target.y, target.z), 0, 0);
            new PacketServerTeleport()
                    .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
            assertTrue(teleports.pendingTeleports.peek() != pending, "not accepted: " + target);
        }
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
