package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Packet-handler corrections and passive interpolation must preserve the pig's exact physical carry. */
class JavaVehicleControlRegainTest {
    private static final int PIG = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double FALL = -0.0784000015258789D;

    /** A passive server move changes the root's ground before control returns, even without a client wire bit. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void passiveAirborneGroundReplacesTheLastControlledGroundOnRegain(String release, ClientVersion version)
            throws Exception {
        regainAfterPassiveGroundUpdate(version, false);
    }

    /** Server ground is valid client state even when its passive interpolation target is above the floor. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void passiveGroundedFlagReplacesTheLastControlledAirOnRegain(String release, ClientVersion version)
            throws Exception {
        regainAfterPassiveGroundUpdate(version, true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aMovementPacketIgnoredByTheControlledRootDoesNotReplaceItsGround(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version)) {
            var selected = new ServerboundSetCarriedItem(0);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, selected), f.player, selected);
            float speed = (float) (0.25D * 0.225D);
            float move = speed * (0.21600002F / (0.6F * 0.6F * 0.6F));
            assertVehicleExact(f.rideTick(START.add(0, 0, move), true), f.player.packetStateData.acceptedClientTick);
            var processor = f.player.checkManager.getSimulationProcessor();
            var ground = processor.getLastOnGround();
            var carry = processor.getCurrentPredictionCarry();
            boolean rootGround = pig(f).onGround;
            var packet = new ClientboundMoveEntity(
                    PIG, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), 0, 0, !rootGround, true, false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
            f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(rootGround, pig(f).onGround);
            assertEquals(ground, processor.getLastOnGround());
            assertSame(carry, processor.getCurrentPredictionCarry());
        }
    }

    private static void regainAfterPassiveGroundUpdate(ClientVersion version, boolean ground) throws Exception {
        try (var f = mounted(version)) {
            PacketEntityRideable pig = pig(f);
            // Establish a controlled prediction for this SAME root; first-mount initialization already refreshes ground.
            var control = new ServerboundSetCarriedItem(0);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, control), f.player, control);
            float riddenSpeed = (float) (0.25D * 0.225D);
            float firstMove = riddenSpeed * (0.21600002F / (0.6F * 0.6F * 0.6F));
            assertVehicleExact(f.rideTick(START.add(0, 0, firstMove), true), f.player.packetStateData.acceptedClientTick);
            var release = new ServerboundSetCarriedItem(1);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, release), f.player, release);
            Vec3 start = START.add(0, 3, 0);
            Vec3 velocity = new Vec3(0, -0.2, 0.05);
            seed(f, velocity);
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(!ground));
            var packet = new ClientboundMoveEntity(
                    PIG, new EntityDelta.Linear((short) 0, (short) 12288, (short) 0), 0, 0, ground, true, false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
            f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            if (!ground && version == ClientVersion.V_1_21_9) {
                var carry = (JavaPredictionCarry) f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
                assertTrue(carry.states().stream().anyMatch(state -> state.pistonStart() != null));
                for (var state : carry.states()) {
                    if (state.pistonStart() == null) continue;
                    assertFalse(state.pistonStart().onGround());
                    assertNull(state.pistonStart().support().getBlockPos());
                    assertFalse(state.pistonStart().support().isOnGround());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.rideTick();
            assertPosition(start, pig.clientPhysicalPosition);
            assertEquals(ground, pig.onGround);
            // In 26.3 commonTick consumes the final interpolation step before aiStep tests for damping.
            Vec3 passiveCarry = version.isNewerThanOrEquals(ClientVersion.V_26_3) ? velocity.scale(0.98D) : velocity;
            assertEquals(passiveCarry, pig.deltaMovement);

            var selected = new ServerboundSetCarriedItem(0);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, selected), f.player, selected);
            Vec3 inherited = version.isOlderThan(ClientVersion.V_1_21_5) ? passiveCarry.scale(0.98D) : passiveCarry;
            float speed = (float) (0.25D * 0.225D);
            float acceleration = ground ? speed * (0.21600002F / (0.6F * 0.6F * 0.6F)) : speed * 0.1F;
            Vec3 attempted = inherited.add(0, 0, acceleration);
            assertVehicleExact(f.rideTick(start.add(attempted), false), f.player.packetStateData.acceptedClientTick);
        }
    }

    /**
     * Source capture: echo -> Pongs -> SetCarriedItem(0) -> Rot -> ticking MoveVehicle. The cached empty hand must
     * neither drop the correction nor turn its response into a travel/boost tick.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void staleCarriedSlotCorrectionAppliesBeforeTheNextControlledTravel(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version)) {
            PacketEntityRideable pig = pig(f);
            // Translate/rotate the first captured regain onto the fixture's loaded floor.
            placeRoot(f, START.add(0, 0, 0.10141267948247701));
            Vec3 carry = new Vec3(0, FALL, 0.05537132942895785);
            seed(f, carry);
            var prediction = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            int tick = f.player.packetStateData.acceptedClientTick;
            var velocities = Set.copyOf(f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());

            Vec3 codecBase = pig.desyncClientPos;
            int proof = correction(f, START, 0, 0);
            assertEquals(1, f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount());
            assertFalse(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(pig));
            assertFalse(echo(f, START, 0, 0).isCancelled());
            assertPosition(START, pig.clientPhysicalPosition);
            assertEquals(codecBase, pig.desyncClientPos);
            assertEquals(carry, pig.deltaMovement);
            assertEquals(velocities, f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            assertSame(prediction, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            assertEquals(tick, f.player.packetStateData.acceptedClientTick);
            assertEquals(0, f.player.packetStateData.clientTickVehicleMovePacketsThisClientTick);
            assertEquals(0, f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount());
            f.pongThrough(proof);
            assertPosition(START, pig.clientPhysicalPosition);

            var selected = new ServerboundSetCarriedItem(0);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, selected), f.player, selected);
            assertEquals(0, f.player.getInventory().inventory.selected);
            // LivingEntity#aiStep's legacy !isEffectiveAi damping precedes Pig#travelRidden through 1.21.4.
            double inherited = carry.z * (version.isOlderThan(ClientVersion.V_1_21_5) ? 0.98D : 1.0D);
            float speed = (float) (0.25D * 0.225D);
            double acceleration = speed * (0.21600002F / (0.6F * 0.6F * 0.6F));
            assertVehicleExact(f.rideTick(START.add(0, 0, inherited + acceleration), true), tick);
            assertEquals(Set.of(pig.deltaMovement), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            assertEquals(codecBase, pig.desyncClientPos);
        }
    }

    /** Both large captured offsets equal a missed correction; neither correction changes the already exact velocity. */
    @ParameterizedTest
    @CsvSource({
        "0.10141267948247701, 0.05537132942895785, 0.11162132793884173",
        "0.11380299639765723, 0.062136443250432, 0.11838644176031587"
    })
    void capturedCorrectionSnapshotsProduceTheCapturedNextTravel(double precedingMove, double velocity, double travel)
            throws Exception {
        try (var f = mounted(ClientVersion.V_26_3)) {
            placeRoot(f, START.add(0, 0, precedingMove));
            seed(f, new Vec3(0, FALL, velocity));
            int proof = correction(f, START, 0, 0);
            assertFalse(echo(f, START, 0, 0).isCancelled());
            f.pongThrough(proof);
            var selected = new ServerboundSetCarriedItem(0);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, selected), f.player, selected);
            assertVehicleExact(f.rideTick(START.add(0, 0, travel), true), f.player.packetStateData.acceptedClientTick);
        }
    }

    /** A passive pig ignores handleMoveVehicle; the response-proof pong proves no physical snap by itself. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anIgnoredCorrectionAndPassiveTicksKeepTheExactPosition(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            Vec3 exact = new Vec3(0.53025321550086, FLOOR_Y, 0.515401812253);
            placeRoot(f, exact);
            Vec3 velocity = new Vec3(0.04, 0, -0.05);
            seed(f, velocity);
            PacketEntityRideable pig = pig(f);
            var interpolation = pig.newPacketLocation;
            var prediction = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            int proof = correction(f, START.add(1, 0, 0), 90, 30);
            f.pongThrough(proof);
            assertPosition(exact, pig.clientPhysicalPosition);
            assertSame(interpolation, pig.newPacketLocation);
            assertEquals(velocity, pig.deltaMovement);
            for (int tick = 0; tick < 3; tick++) {
                f.rideTick();
                velocity = velocity.multiply(0.98D, 0.98D, 0.98D);
                assertPosition(exact, pig.clientPhysicalPosition);
                assertEquals(velocity, pig.deltaMovement);
                assertSame(prediction, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            }
        }
    }

    /** An echo at the active TARGET changes no physical/interpolation/rotation state, including legacy lerp. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void noSnapActiveTargetEchoPreservesPhysicalState(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            startInterpolation(f);
            f.rideTick();
            PacketEntityRideable pig = pig(f);
            var interpolation = pig.newPacketLocation;
            Vec3 physical = pig.clientPhysicalPosition;
            Vec3 target = interpolation.targetPosition();
            assertNotEquals(target, physical);
            float yaw = pig.clientPhysicalYaw;
            float pitch = pig.clientPhysicalPitch;
            Vec3 velocity = new Vec3(0.04, 0.01, -0.05);
            seed(f, velocity);
            pig.boost.onSynced(100);
            float factor = pig.boost.factor(version);
            int step = interpolation.getExactInterpolationStep();
            int proof = correction(f, target, 120, 60);
            float sentYaw = version.isOlderThan(ClientVersion.V_1_21_5) ? yaw : interpolation.getTargetYaw();
            float sentPitch = version.isOlderThan(ClientVersion.V_1_21_5) ? pitch : interpolation.getTargetPitch();
            assertFalse(echo(f, target, sentYaw, sentPitch).isCancelled());
            f.pongThrough(proof);
            assertSame(interpolation, pig.newPacketLocation);
            assertEquals(step, interpolation.getExactInterpolationStep());
            assertTrue(interpolation.hasActiveInterpolationTarget());
            assertPosition(physical, pig.clientPhysicalPosition);
            assertEquals(yaw, pig.clientPhysicalYaw);
            assertEquals(pitch, pig.clientPhysicalPitch);
            assertEquals(velocity, pig.deltaMovement);
            assertEquals(factor, pig.boost.factor(version));
            assertEquals(0, f.player.packetStateData.clientTickVehicleMovePacketsThisClientTick);
            assertPosition(physical, new Vec3(f.player.x, f.player.y, f.player.z));
        }
    }

    /** A correction at the current physical point still cancels a different active target. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void snapComparesTheActiveTargetInsteadOfThePhysicalPoint(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            startInterpolation(f);
            f.rideTick();
            PacketEntityRideable pig = pig(f);
            Vec3 physical = pig.clientPhysicalPosition;
            Vec3 velocity = new Vec3(0.04, 0.01, -0.05);
            seed(f, velocity);
            int proof = correction(f, physical, 120, 60);
            assertFalse(echo(f, physical, 120, 60).isCancelled());
            f.pongThrough(proof);
            assertFalse(pig.newPacketLocation.hasActiveInterpolationTarget());
            assertPosition(physical, pig.clientPhysicalPosition);
            assertEquals(120, pig.clientPhysicalYaw);
            assertEquals(60, pig.clientPhysicalPitch);
            assertEquals(velocity, pig.deltaMovement);
        }
    }

    /** A block-entity move before receipt can change an originally matching target into a real snap. */
    @org.junit.jupiter.api.Test
    void correctionDecisionUsesTheTargetAtReceipt() throws Exception {
        try (var f = mounted(ClientVersion.V_26_3)) {
            startInterpolation(f);
            f.rideTick();
            PacketEntityRideable pig = pig(f);
            Vec3 target = pig.newPacketLocation.targetPosition();
            int proof = correction(f, target, 120, 60);
            Vec3 movement = new Vec3(0.125, 0, 0);
            pig.applyBlockEntityMovement(f.player,
                    GetBoundingBox.getPacketEntityBoundingBox(f.player,
                            pig.clientPhysicalPosition.x + movement.x, pig.clientPhysicalPosition.y,
                            pig.clientPhysicalPosition.z, pig), movement);
            assertNotEquals(target, pig.newPacketLocation.targetPosition());
            assertFalse(echo(f, target, 120, 60).isCancelled());
            f.pongThrough(proof);
            assertPosition(target, pig.clientPhysicalPosition);
            assertFalse(pig.newPacketLocation.hasActiveInterpolationTarget());
            assertEquals(120, pig.clientPhysicalYaw);
        }
    }

    /** Through 26.2 aiStep enters the lerp branch before stepping; 26.3 sees the commonTick's finished path. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void finalPassiveInterpolationStepDecaysAtTheReleaseSpecificTime(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version)) {
            startInterpolation(f);
            seed(f, new Vec3(0.1, 0.0029, -0.04));
            for (int step = 1; step <= 3; step++) {
                f.rideTick();
                double decay = step == 3 && version.isNewerThanOrEquals(ClientVersion.V_26_3) ? 0.98D : 1.0D;
                Vec3 expected = new Vec3(0.1D * decay, 0, -0.04D * decay);
                assertEquals(expected, pig(f).deltaMovement, "step " + step);
                assertEquals(Set.of(expected), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
                assertEquals(step < 3, pig(f).newPacketLocation.hasActiveInterpolationTarget());
            }
            Vec3 before = pig(f).deltaMovement;
            f.rideTick();
            assertEquals(before.multiply(0.98D, 0.98D, 0.98D), pig(f).deltaMovement);
        }
    }

    /** The literal is a float promoted to double: 1e-5D lies just outside the vanilla no-snap distance. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void correctionSnapUsesThePromotedFloatThreshold(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            Vec3 zeroX = new Vec3(0, FLOOR_Y, 0.5);
            placeRoot(f, zeroX);
            int proof = correction(f, zeroX.add((double) 1.0E-5F, 0, 0), 90, 30);
            assertFalse(echo(f, zeroX, 0, 0).isCancelled());
            f.pongThrough(proof);
            assertEquals(0, pig(f).clientPhysicalYaw);
            Vec3 outside = zeroX.add(1.0E-5D, 0, 0);
            proof = correction(f, outside, 90, 30);
            assertFalse(echo(f, outside, 90, 30).isCancelled());
            f.pongThrough(proof);
            assertEquals(90, pig(f).clientPhysicalYaw);
        }
    }

    /** A ticking packet equal to a pending correction is still travel, never a handler acknowledgement. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void pairedTickCannotAcceptACorrection(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            PacketEntityRideable pig = pig(f);
            pig.boost.onSynced(100);
            int proof = correction(f, START, 120, 60);
            var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            echo(f, START, 0, 0);
            assertFalse(f.player.packetStateData.lastPacketWasTeleport);
            assertEquals(1, f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount());
            assertEquals(1, f.player.packetStateData.clientTickVehicleMovePacketsThisClientTick);
            assertTrue(pig.boost.factor(version) > 1.0F);
            f.pongThrough(proof);
        }
    }

    private static JavaTickFixture mounted(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(START, Vec3.ZERO);
        f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
        f.player.getInventory().selectHotbarSlot(1);
        var spawn = new ClientboundAddEntity(PIG, "minecraft:pig", new Vec3d(START.x, START.y, START.z), 0, 0, 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, spawn);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        pig(f).hasSaddle = true;
        f.setPassengers(PIG, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < 6; tick++) f.rideTick();
        pig(f).onGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        return f;
    }

    private static PacketEntityRideable pig(JavaTickFixture f) {
        return (PacketEntityRideable) f.player.compensatedEntities.getEntity(PIG);
    }

    private static void placeRoot(JavaTickFixture f, Vec3 position) {
        pig(f).setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(
                f.player, position.x, position.y, position.z, pig(f)), 0, 0);
    }

    private static void seed(JavaTickFixture f, Vec3 velocity) {
        pig(f).deltaMovement = velocity;
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
    }

    private static int correction(JavaTickFixture f, Vec3 position, float yaw, float pitch) {
        var packet = new ClientboundMoveVehicle(new Vec3d(position.x, position.y, position.z), yaw, pitch);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_VEHICLE, packet, false);
        new PacketServerTeleport().onMoveVehicle(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static PacketReceiveEvent<ServerboundMoveVehicle> echo(JavaTickFixture f, Vec3 position, float yaw, float pitch) {
        var packet = new ServerboundMoveVehicle(new Vec3d(position.x, position.y, position.z), yaw, pitch, true, true);
        var event = f.event(ServerboundPackets.MOVE_VEHICLE, packet);
        f.listener.onMoveVehicle(event, f.player, packet);
        return event;
    }

    private static void startInterpolation(JavaTickFixture f) {
        var packet = new ClientboundMoveEntity(
                PIG, new EntityDelta.Linear((short) 3072, (short) 0, (short) 0), 30, 15, true, true, true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(pig(f).newPacketLocation.hasActiveInterpolationTarget());
    }

    private static void assertPosition(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-12);
        assertEquals(expected.y, actual.y, 1.0E-12);
        assertEquals(expected.z, actual.z, 1.0E-12);
    }
}
