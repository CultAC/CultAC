package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsR;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#sendChanges sends a passenger's Rot every tick, changed or not, and a ServerboundMoveVehiclePacket only
 * when the root vehicle is Entity#isLocalInstanceAuthoritative, which needs a controlling passenger that is the local
 * player. A pig is controlled only by a rider holding a carrot on a stick, so an idle rider sends nothing else.
 */
class VehiclePassengerChecksTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 1, 0.5);
    private static final List<String> FACINGS = List.of("north", "south", "west", "east");

    @Test
    void anInventoryOpenedWhileRidingIsNotRotatedByThePassengersUnchangedRot() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            MultiActionsO check = enabled(f, MultiActionsO.class);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            for (int tick = 0; tick < 5; tick++) f.rideTick();

            assertEquals(0, check.violations);
        }
    }

    @Test
    void rotatingTheLookInAnInventoryWhileRidingIsStillFlagged() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            MultiActionsO check = enabled(f, MultiActionsO.class);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.rideTick();
            f.rideTick();
            rot(f, f.player.xRot + 20, f.player.yRot);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void anIdleRiderOfAPigItDoesNotControlStillSendsItsRot() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            ((PacketEntityRideable) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            BadPacketsR check = enabled(f, BadPacketsR.class);
            for (int tick = 0; tick < 20; tick++) passengerTick(f, check, false);
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aBoatPassengerThatStopsSendingTheVehicleIsStillFlagged() throws Exception {
        try (var f = ride(EntityTypeIds.OAK_BOAT)) {
            BadPacketsR check = enabled(f, BadPacketsR.class);
            // The Rot alone cannot stand for a vehicle the rider controls.
            for (int tick = 0; tick < 20; tick++) passengerTick(f, check, false);
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aBoatPassengerSteeringItIsNotFlagged() throws Exception {
        try (var f = ride(EntityTypeIds.OAK_BOAT)) {
            BadPacketsR check = enabled(f, BadPacketsR.class);
            for (int tick = 0; tick < 20; tick++) passengerTick(f, check, true);
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aPlayerOnFootThatStopsSendingPositionsIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aStillPlayerAtTickRateOneIsNotFlaggedBetweenItsPositionReminders() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            tickRate(f, 1F);
            pong(f, check);
            pongAfter(f, check, 11_645_000_000L);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aPlayerAtTickRateOneThatStopsSendingPositionsIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            tickRate(f, 1F);
            pong(f, check);
            pongAfter(f, check, 41_000_000_000L);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void theTicksBeforeTheTickRateReturnsToTwentyAreNotJudgedAtTwentyTicksPerSecond() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            tickRate(f, 1F);
            pong(f, check);
            position(f, check);
            pongAfter(f, check, 41_000_000_000L);
            tickRate(f, 20F);
            pongAfter(f, check, 5_342_000_000L);
            assertEquals(0, check.violations);

            pongAfterTwoSeconds(f, check);
            assertEquals(1, check.violations);
        }
    }

    @Test
    void aFrozenPlayerAtTickRateOneThatStopsSendingPositionsIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            tickRate(f, 1F);
            f.player.packetStateData.serverTicksFrozen = true;
            pong(f, check);
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aRespawnedPlayerThatHasNotLoadedTheLevelIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            respawn(f);
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aTeleportedClientAnsweringOnlyWithAcceptancesIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            var accept = acceptance(f, teleport(f, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z)));
            assertFalse(accept.isCancelled(), "the acceptance did not answer the teleport");
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void anAcceptanceAnsweringNoTeleportIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            assertTrue(acceptance(f, 7).isCancelled(), "an acceptance without a teleport was taken");
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aRepeatedAcceptanceIsNotAnotherPosition() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            int id = teleport(f, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z));
            assertFalse(acceptance(f, id).isCancelled(), "the acceptance did not answer the teleport");
            pongAfterTwoSeconds(f, check);
            assertTrue(acceptance(f, id).isCancelled(), "a repeated acceptance answered the teleport again");
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aRepeatedAcceptanceThroughTheProductionRouteIsNotAnotherPosition() throws Exception {
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        dispatcher.register(PacketManager::registerListeners);
        var route = dispatcher.get(ServerboundPackets.ACCEPT_TELEPORTATION).receive();
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            int id = teleport(f, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z));
            var first = acceptanceEvent(f, id, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z));
            route.dispatch(first, f.player);
            assertFalse(first.isCancelled(), "the acceptance did not answer the teleport");
            pongAfterTwoSeconds(f, check);
            var repeated = acceptanceEvent(f, id, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z));
            route.dispatch(repeated, f.player);
            assertTrue(repeated.isCancelled(), "a repeated acceptance answered the teleport again");
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aSleeperRestingOnTheBedsTopStillSendsItsPosition() throws Exception {
        try (var f = sleeping()) {
            var route = moveRoute();
            BadPacketsR check = enabled(f, BadPacketsR.class);
            var rest = positionEvent(f, FLOOR_Y + 0.5625);
            route.dispatch(rest, f.player);
            assertFalse(rest.isCancelled(), "the resting sleeper's position was cancelled");
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aSleeperBelowTheBedsTopIsStillCancelled() throws Exception {
        try (var f = sleeping()) {
            var route = moveRoute();
            BadPacketsR check = enabled(f, BadPacketsR.class);
            var below = positionEvent(f, FLOOR_Y + 0.5);
            route.dispatch(below, f.player);
            assertTrue(below.isCancelled(), "a sleeper below the bed was taken");
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aSleeperRestingOnAStrawBedsPillowStillSendsItsPosition() throws Exception {
        for (String facing : FACINGS) {
            try (var f = sleepingOnStraw(facing)) {
                var route = moveRoute();
                var rest = positionEvent(f, FLOOR_Y + 0.3125);
                route.dispatch(rest, f.player);
                assertFalse(rest.isCancelled(), "the resting sleeper's position was cancelled, facing " + facing);
            }
        }
    }

    @Test
    void aSleeperBelowAStrawBedsPillowIsStillCancelled() throws Exception {
        for (String facing : FACINGS) {
            try (var f = sleepingOnStraw(facing)) {
                var route = moveRoute();
                var below = positionEvent(f, FLOOR_Y + 0.28125);
                route.dispatch(below, f.player);
                assertTrue(below.isCancelled(), "a sleeper below the pillow was taken, facing " + facing);
            }
        }
    }

    @Test
    void aBoatPassengerAnsweringATeleportStillOwesTheVehicle() throws Exception {
        try (var f = ride(EntityTypeIds.OAK_BOAT)) {
            BadPacketsR check = enabled(f, BadPacketsR.class);
            var accept = acceptance(f, teleport(f, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z)), SEAT);
            assertFalse(accept.isCancelled(), "the acceptance did not answer the teleport");
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    /** The server's respawn, whose new LocalPlayer ticks only once ClientPacketListener#hasClientLoaded. */
    private static void respawn(JavaTickFixture f) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(f.player.packetStateData.playerLoadedIntoLevel, "the respawn did not reach the client");
    }

    /** A sleeper in a red bed's head on the floor, at LivingEntity#setPosToBed's position. */
    private static JavaTickFixture sleeping() throws Exception {
        var registry = DataTables.defaults().registry();
        int head = registry.with(registry.block("minecraft:red_bed").defaultState(), "part", "head");
        return sleepingIn(head, FLOOR_Y + 0.6875);
    }

    /** A 26.3 sleeper in a straw bed's head on the floor, at LivingEntity#setPosToBed's position. */
    private static JavaTickFixture sleepingOnStraw(String facing) throws Exception {
        var registry = DataTables.defaults().registry();
        int head = registry.with(registry.with(BlockIds.STRAW_BED.defaultState(), "part", "head"), "facing", facing);
        return sleepingIn(head, FLOOR_Y + 0.375);
    }

    private static JavaTickFixture sleepingIn(int head, double y) throws Exception {
        var f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), head);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 bed = new Vec3(SEAT.x, y, SEAT.z);
        f.place(bed, Vec3.ZERO);
        f.player.isInBed = true;
        f.player.bedPosition = bed;
        return f;
    }

    private static PacketDispatcher.ReceiveRoute<ServerboundPacket> moveRoute() {
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        dispatcher.register(PacketManager::registerListeners);
        return dispatcher.get(ServerboundPackets.MOVE_PLAYER).receive();
    }

    private static PacketReceiveEvent<ServerboundMovePlayer> positionEvent(JavaTickFixture f, double y) {
        var packet =
                new ServerboundMovePlayer(SEAT.x, y, SEAT.z, f.player.xRot, f.player.yRot, true, false, true, false);
        return f.event(ServerboundPackets.MOVE_PLAYER, packet);
    }

    private static JavaTickFixture ride(int type) throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(VEHICLE, type, SEAT, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    private static <T extends ac.cult.cultac.checks.Check> T enabled(JavaTickFixture f, Class<T> type) {
        f.player.setExperimentalChecks(true);
        T check = f.player.checkManager.getCheck(type);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    /** One ridden client tick as BadPacketsR sees it: the passenger Rot, and the vehicle's move when it controls it. */
    private static void passengerTick(JavaTickFixture f, BadPacketsR check, boolean echoVehicle) {
        var rot =
                new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, f.player.onGround, false, false, true);
        check.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        if (!echoVehicle) return;
        var move = new ServerboundMoveVehicle(
                new Vec3d(SEAT.x, SEAT.y, SEAT.z), f.player.xRot, f.player.yRot, f.player.onGround, true);
        check.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
    }

    private static void rot(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    /** A server teleport to {@code target} whose ping the client has answered; returns its ID. */
    private static int teleport(JavaTickFixture f, Vec3 target) {
        int id = 7;
        int transaction = f.player.sendTrailingTransaction();
        f.player.getSetbackTeleportUtil()
                .addSentTeleport(target, Vec3.ZERO, transaction, new RelativeFlag(0), false, id, 0, 0, 0, 0);
        f.pongThrough(transaction);
        return id;
    }

    /** ClientPacketListener#handleMovePlayer's 26.3 answer, carrying the client's position after the teleport. */
    private static PacketReceiveEvent<ServerboundAcceptTeleportation> acceptance(JavaTickFixture f, int id) {
        return acceptance(f, id, new Vec3(SEAT.x + 3, FLOOR_Y, SEAT.z));
    }

    /** The acceptance of a client at {@code at}; ClientPacketListener#handleMovePlayer leaves a passenger seated. */
    private static PacketReceiveEvent<ServerboundAcceptTeleportation> acceptance(JavaTickFixture f, int id, Vec3 at) {
        var event = acceptanceEvent(f, id, at);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, event.getPacket());
        f.listener.onAcceptTeleportation(event, f.player, event.getPacket());
        return event;
    }

    private static PacketReceiveEvent<ServerboundAcceptTeleportation> acceptanceEvent(
            JavaTickFixture f, int id, Vec3 at) {
        var packet = new ServerboundAcceptTeleportation(id, new Vec3d(at.x, at.y, at.z), 0, 0);
        return f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
    }

    /** The first pong after more than two seconds without a position, which BadPacketsR judges. */
    private static void pongAfterTwoSeconds(JavaTickFixture f, BadPacketsR check) throws Exception {
        pongAfter(f, check, 5_000_000_000L);
    }

    /** A pong {@code nanos} after the start of BadPacketsR's window. */
    private static void pongAfter(JavaTickFixture f, BadPacketsR check, long nanos) throws Exception {
        long clock = System.nanoTime() - nanos;
        JavaTickFixture.setField(BadPacketsR.class, check, "clock", clock);
        JavaTickFixture.setField(BadPacketsR.class, check, "lastTransTime", 0L);
        pong(f, check);
    }

    /** A ClientboundTickingState the client has acknowledged. */
    private static void tickRate(JavaTickFixture f, float rate) {
        f.player.itemUseBurst.tickRateSent(rate);
        f.player.packetStateData.serverTickRate = rate;
    }

    private static void position(JavaTickFixture f, BadPacketsR check) {
        var packet = new ServerboundMovePlayer(
                SEAT.x, FLOOR_Y, SEAT.z, f.player.xRot, f.player.yRot, true, false, true, false);
        check.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void pong(JavaTickFixture f, BadPacketsR check) {
        var packet = new ServerboundPong(17);
        var event = f.event(ServerboundPackets.PONG, packet);
        event.setAcceptedTransactionResponse(true);
        check.onPong(event, f.player, packet);
    }
}
