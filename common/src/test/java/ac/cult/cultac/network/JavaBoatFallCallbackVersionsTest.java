package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** AbstractBoat#checkFallDamage samples deltaMovement.y on every permitted Entity#move, before restitution. */
class JavaBoatFallCallbackVersionsTest {
    private static final int BOAT = 100;
    private static final Vec3 FLOOR = new Vec3(0.5, FLOOR_Y, 0.1);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}: boat travel samples the virtual fall callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void ordinaryBoatTravelUsesTheFallCallbackGate(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = FLOOR.add(0, 8, 0);
            mount(f, version, start);
            var result = f.rideTick(start.add(0, -0.04, 0), false);
            assertTrue(exact(result), describe(result));
            assertEquals(version == ClientVersion.V_1_21_4 ? 0 : -0.04, f.player.boatData.lastYd, 1e-13);
        }
    }

    @ParameterizedTest(name = "{0}: boat travel uses water below instead of generic entity water contact")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void ordinaryBoatTravelUsesTheSameVirtualFallDistanceRule(String release, ClientVersion version) throws Exception {
        travelVirtualFallDistance(version, true);
    }

    @Test
    void aReceivedWaterTagReloadChangesTheTravelFallCallback() throws Exception {
        travelVirtualFallDistance(ClientVersion.V_26_3, false);
    }

    private static void travelVirtualFallDistance(ClientVersion version, boolean waterTagged) throws Exception {
        try (var f = new JavaTickFixture()) {
            // The first endpoint is in block Y=74 with air below; the second is in Y=73 with water below.
            Vec3 start = FLOOR.add(0, 10.05, 0);
            mount(f, version, start);
            if (!waterTagged) clearWaterTag(f);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 8, 0), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var first = f.rideTick(start.add(0, -0.04, 0), false);
            assertTrue(exact(first), describe(first));
            var second = f.rideTick(start.add(0, -0.04 - 0.08, 0), false);
            assertTrue(exact(second), describe(second));
            var carry = (JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            assertEquals(
                    version == ClientVersion.V_1_21_4 ? 0 : (double) 0.04F + (waterTagged ? 0 : (double) 0.08F),
                    carry.fallDistance(),
                    1e-13,
                    "the second move uses the client's received water-tag membership below the boat");
        }
    }

    @ParameterizedTest(name = "{0}: a boat fall callback reads water below its post-move block")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aBoxMoveUsesTheBoatsVirtualFallDistanceRule(String release, ClientVersion version) throws Exception {
        boxVirtualFallDistance(version, true);
    }

    @Test
    void aReceivedWaterTagReloadChangesTheBoxMoveFallCallback() throws Exception {
        boxVirtualFallDistance(ClientVersion.V_26_3, false);
    }

    private static void boxVirtualFallDistance(ClientVersion version, boolean waterTagged) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = FLOOR.add(0, 9.25, 0);
            mount(f, version, start);
            if (!waterTagged) clearWaterTag(f);
            f.player.compensatedEntities.getEntity(BOAT).hasGravity = false;
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 8, 0), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var travel = f.rideTick(start, false);
            assertTrue(exact(travel), describe(travel));
            var initial =
                    new JavaPassState(start, new Vec3(0, -0.375, 0), false, null, null, 0.5, ClientPistonDeltas.NONE);
            var mover = new ClientBlockEntityPass.ShulkerBox(
                    new SimpleCollisionBox(0, FLOOR_Y + 9, -1, 1, FLOOR_Y + 11, 1), new Vec3(0, -0.25, 0));
            var result = JavaBlockEntityPass.run(
                    f.player,
                    travel.getSimulationContext(),
                    new ClientBlockEntityPass(List.of(mover), 100),
                    initial,
                    f.player.pose,
                    false,
                    start);
            assertEquals(FLOOR_Y + 9.0, result.state().position().y, 1e-10);
            assertEquals(
                    version == ClientVersion.V_1_21_4 || waterTagged ? 0.5 : 0.75,
                    result.state().fallDistance(),
                    1e-13,
                    "AbstractBoat checks received water-tag membership below its post-move block");
            if (version == ClientVersion.V_1_21_4) assertEquals(null, result.boatLastYd());
            else
                assertEquals(
                        -0.375,
                        result.boatLastYd(),
                        1e-13,
                        "the callback samples deltaMovement, independently of the -0.25 position change");
        }
    }

    private static void clearWaterTag(JavaTickFixture f) {
        if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
        f.player.registryState.appendTags(new RegistryTags(
                Map.of("minecraft:fluid", new RegistryTags.Payload(Map.of("minecraft:water", List.of())))));
    }

    @ParameterizedTest(name = "{0}: a consumed web multiplier clears motion before the boat callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aWebClearsBoatMotionBeforeTheTravelFallCallback(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = FLOOR.add(0, 8, 0);
            mount(f, version, start);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 8, 0), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var first = f.rideTick(start.add(0, -0.04, 0), false);
            assertTrue(exact(first), describe(first));
            var second = f.rideTick(start.add(0, -0.04 + (-0.04 - 0.04) * (double) 0.05F, 0), false);
            assertTrue(exact(second), describe(second));
            assertEquals(
                    0,
                    f.player.boatData.lastYd,
                    1e-13,
                    "Entity#move clears deltaMovement before the virtual fall callback consumes it");
        }
    }

    @ParameterizedTest(name = "{0}: a piston consumes web stuck speed after writing slime velocity")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aWebClearsSlimeVelocityBeforeThePistonFallCallback(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            mountOnFloor(f, version);
            // The boat's side touches web without occupying the piston's destination.
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var contact = f.rideTick(FLOOR, true);
            assertTrue(exact(contact), describe(contact));
            isolateSlime(f);
            extend(f, new BlockPos(0, FLOOR_Y - 2, 0), "up", 1, "slime_block");
            boolean unscaled = release.equals("1.21.10") || version.isNewerThanOrEquals(ClientVersion.V_1_21_11);
            double rise = unscaled ? 0.51 : 0.51 * (double) 0.05F;
            Vec3 endpoint = FLOOR.add(0, rise, 0);
            var pushed = f.rideTick(
                    version.isNewerThanOrEquals(ClientVersion.V_26_3) ? endpoint : FLOOR,
                    version.isOlderThan(ClientVersion.V_26_3));
            assertTrue(exact(pushed), describe(pushed));
            assertEquals(0, f.player.boatData.lastYd, 1e-13);
            if (version == ClientVersion.V_1_21_9) {
                var carry = (JavaPredictionCarry)
                        f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
                assertTrue(
                        carry.states().stream()
                                .anyMatch(s -> s.pistonStart() != null
                                        && Math.abs(s.pistonStart().position().y - endpoint.y) < 1e-10),
                        "protocol 773 must retain the real release's exact physical endpoint");
            } else
                assertEquals(endpoint.y, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition.y, 1e-10);
            // Remove both moving block entities before the following travel distinguishes a hidden root start.
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.AIR.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.AIR.defaultState());
            var following = f.rideTick(endpoint.add(0, -0.04 * (double) 0.05F, 0), false);
            assertTrue(exact(following), describe(following));
            assertEquals(0, f.player.boatData.lastYd, 1e-13);
        }
    }

    @ParameterizedTest(name = "{0}: a horizontal piston replaces the travel's boat lastYd")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aHorizontalPistonSamplesThePostTravelVelocity(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            mountOnFloor(f, version);
            extend(f, new BlockPos(0, FLOOR_Y, 2), "north", 2, "stone");
            Vec3 endpoint = new Vec3(FLOOR.x, FLOOR.y, 0.5 - 1.375F / 2.0 - 0.01);
            var result = f.rideTick(
                    version.isNewerThanOrEquals(ClientVersion.V_26_3) ? endpoint : FLOOR,
                    version.isOlderThan(ClientVersion.V_26_3));
            assertTrue(exact(result), describe(result));
            assertEquals(endpoint.z, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition.z, 1e-10);
            assertEquals(
                    0,
                    f.player.boatData.lastYd,
                    1e-13,
                    "travel clipped the gravity to zero before the piston invoked checkFallDamage again");
        }
    }

    @ParameterizedTest(name = "{0}: a slime piston samples its new boat velocity")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anUpwardSlimePistonSamplesTheVelocityItWrote(String release, ClientVersion version) throws Exception {
        upwardSlime(version, true);
    }

    @ParameterizedTest(name = "{0}: attached stone can move the boat before the slime ticker reaches it")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void attachedStoneKeepsItsOwnCallbackInsteadOfInventingASlimeLaunch(String release, ClientVersion version)
            throws Exception {
        upwardSlime(version, false);
    }

    private static void upwardSlime(ClientVersion version, boolean isolated) throws Exception {
        try (var f = new JavaTickFixture()) {
            mountOnFloor(f, version);
            if (isolated) {
                // Otherwise sticky adjacency moves these four stones first; the first stone already
                // lifts the boat beyond the slime ticker's swept face, so slime never writes its velocity.
                isolateSlime(f);
            }
            extend(f, new BlockPos(0, FLOOR_Y - 2, 0), "up", 1, "slime_block");
            Vec3 endpoint = FLOOR.add(0, 0.51, 0);
            var result = f.rideTick(
                    version.isNewerThanOrEquals(ClientVersion.V_26_3) ? endpoint : FLOOR,
                    version.isOlderThan(ClientVersion.V_26_3));
            assertTrue(exact(result), describe(result));
            assertEquals(endpoint.y, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition.y, 1e-10);
            assertEquals(
                    version == ClientVersion.V_1_21_4 || !isolated ? 0 : 1,
                    f.player.boatData.lastYd,
                    1e-13,
                    "the virtual callback samples the velocity left by the actual preceding movers");
        }
    }

    private static void mountOnFloor(JavaTickFixture f, ClientVersion version) throws Exception {
        mount(f, version, FLOOR);
        for (int tick = 0; tick < 6; tick++) {
            var result = f.rideTick(FLOOR, true);
            assertTrue(exact(result), describe(result));
        }
    }

    private static void isolateSlime(JavaTickFixture f) {
        for (BlockPos pos : List.of(
                new BlockPos(1, FLOOR_Y - 1, 0),
                new BlockPos(-1, FLOOR_Y - 1, 0),
                new BlockPos(0, FLOOR_Y - 1, 1),
                new BlockPos(0, FLOOR_Y - 1, -1)))
            f.player.compensatedWorld.updateBlock(pos, BlockIds.AIR.defaultState());
    }

    private static void mount(JavaTickFixture f, ClientVersion version, Vec3 position) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(position, Vec3.ZERO);
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, position, 0, 0, 0);
        f.player.compensatedEntities.getEntity(BOAT).onGround = position.y == FLOOR_Y;
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NONE);
    }

    private static void extend(JavaTickFixture f, BlockPos pos, String facing, int face, String moved) {
        int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", facing);
        f.player.compensatedWorld.updateBlock(pos, piston);
        f.player.compensatedWorld.updateBlock(
                facing.equals("up")
                        ? new BlockPos(pos.getX(), pos.getY() + 1, pos.getZ())
                        : new BlockPos(pos.getX(), pos.getY(), pos.getZ() - 1),
                REGISTRY.block("minecraft:" + moved).defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        var packet = new ClientboundBlockEvent(pos, 0, face, REGISTRY.blockIndex(piston));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
