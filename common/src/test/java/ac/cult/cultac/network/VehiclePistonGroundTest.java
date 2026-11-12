package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Minecraft#tick in 26.3 runs tickBlockEntities before LocalPlayer#sendChanges reports the controlled root. */
class VehiclePistonGroundTest {
    private static final int ROOT = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.1);

    static Stream<Arguments> cases() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release -> Stream.of(
                        Arguments.of(release.get()[0], release.get()[1], EntityTypeIds.OAK_BOAT),
                        Arguments.of(release.get()[0], release.get()[1], EntityTypeIds.HORSE)));
    }

    static Stream<Integer> roots() {
        return Stream.of(EntityTypeIds.OAK_BOAT, EntityTypeIds.HORSE);
    }

    @ParameterizedTest(name = "{0}: root {2} reports ground after its last move")
    @MethodSource("cases")
    void aHorizontalPistonPushDoesNotFlagOrRewriteVanillaGround(String release, ClientVersion version, int type)
            throws Exception {
        try (var f = mounted(version, type)) {
            double halfWidth = (type == EntityTypeIds.HORSE ? 1.3964844F : 1.375F) / 2.0;
            Vec3 first = new Vec3(0.5, FLOOR_Y, 0.5 - halfWidth - 0.01);
            Vec3 second = first.add(0, 0, -0.5);
            extend(f);
            boolean reportsBeforePistons = version.isOlderThan(ClientVersion.V_26_3);
            if (reportsBeforePistons) assertVanilla(f, START, true);
            assertVanilla(f, first, reportsBeforePistons);
            if (!reportsBeforePistons) {
                var pass = JavaMovementEngine.passAfterTravel(lastPrediction(f));
                assertTrue(pass.result().moved());
                assertTrue(pass.travel().onGround(), "travel lands before the piston moves the root");
                assertFalse(pass.result().state().onGround(), "the horizontal piston move clears ground");
            }
            assertVanilla(f, second, reportsBeforePistons);
            assertVanilla(f, second, true);
            assertEquals(0.0, f.player.checkManager.getCheck(VehicleGround.class).violations);
        }
    }

    @ParameterizedTest
    @MethodSource("roots")
    void aPistonPushCannotClaimTheGroundFromTheEarlierTravel(int type) throws Exception {
        try (var f = mounted(ClientVersion.V_26_3, type)) {
            double halfWidth = (type == EntityTypeIds.HORSE ? 1.3964844F : 1.375F) / 2.0;
            extend(f);
            var event = move(f, new Vec3(0.5, FLOOR_Y, 0.5 - halfWidth - 0.01), true);
            assertFalse(event.isCancelled());
            assertFalse(event.getPacket().onGround(), "the server must receive the last move's ground");
            assertTrue(f.player.checkManager.getCheck(VehicleGround.class).violations > 0);
            assertEquals(
                    DesyncStatus.FALSE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
            assertFalse(f.player.compensatedEntities.getEntity(ROOT).onGround);
        }
    }

    private static void assertVanilla(JavaTickFixture f, Vec3 to, boolean ground) {
        var event = move(f, to, ground);
        assertFalse(event.isCancelled());
        assertEquals(ground, event.getPacket().onGround(), "a vanilla packet must retain its ground bit");
        assertVehicleExact(lastPrediction(f), 0);
        assertEquals(0.0, f.player.checkManager.getCheck(VehicleGround.class).violations);
        if (f.player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            assertEquals(ground, f.player.compensatedEntities.getEntity(ROOT).onGround);
            assertEquals(
                    DesyncStatus.fromBoolean(ground),
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
        }
    }

    private static PredictionResult lastPrediction(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static PacketReceiveEvent<ServerboundMoveVehicle> move(JavaTickFixture f, Vec3 to, boolean ground) {
        // A horse passenger's Rot reports its own airborne state, independently of the root's ground.
        boolean riderGround = f.player.compensatedEntities.getEntity(ROOT).isBoat() && ground;
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, riderGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var packet = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, ground, true);
        var event = f.event(ServerboundPackets.MOVE_VEHICLE, packet);
        f.listener.onMoveVehicle(event, f.player, packet);
        f.tickEnd();
        return event;
    }

    private static JavaTickFixture mounted(ClientVersion version, int type) throws Exception {
        var f = new JavaTickFixture();
        boolean ready = false;
        try {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(ROOT, type, START, 0, 0, 0);
            var root = f.player.compensatedEntities.getEntity(ROOT);
            if (root instanceof PacketEntityHorse horse) horse.hasSaddle = true;
            root.onGround = true;
            f.setPassengers(ROOT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            // A horse initially requests zero Y, then gravity supplies a downward request on the following ticks.
            for (int tick = 0; tick < 6; tick++) {
                move(f, START, root.isBoat() || tick != 0);
                assertVehicleExact(lastPrediction(f), tick);
            }
            f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
            ready = true;
            return f;
        } finally {
            if (!ready) f.close();
        }
    }

    /** PistonBaseBlock#triggerEvent extending north with a stone block in front of it. */
    private static void extend(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        var base = new BlockPos(0, FLOOR_Y, 2);
        int piston = registry.with(registry.block("minecraft:piston").defaultState(), "facing", "north");
        f.player.compensatedWorld.updateBlock(base, piston);
        f.player.compensatedWorld.updateBlock(
                base.north(), registry.block("minecraft:stone").defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.player.compensatedWorld.pistons.handleBlockEvent(base, 0, 2);
    }
}
