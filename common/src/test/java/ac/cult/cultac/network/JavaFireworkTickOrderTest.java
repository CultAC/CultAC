package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaEntityVelocityTicks;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityFirework;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaFireworkTickOrderTest {
    private static final Vec3 VELOCITY = new Vec3(0.31, -0.024, 0.27);

    private static PacketEntityFirework rocket(JavaTickFixture f, int id) {
        var entities = f.player.compensatedEntities;
        entities.addEntity(id, EntityTypeIds.FIREWORK_ROCKET, new Vec3(0.5, 80, 0.5), 0, 0, 0);
        var rocket = assertInstanceOf(PacketEntityFirework.class, entities.getEntity(id));
        rocket.attachment = f.player.entityID;
        f.player.compensatedFireworks.addNewFirework(id);
        return rocket;
    }

    /** A rocket after LocalPlayer cannot change this travel, but must boost the following stored velocity. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void eachMathCandidateCarriesEveryLateRocketExactlyOnce(ClientVersion version) throws Exception {
        for (boolean vanilla : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                Vec3 position = new Vec3(0.5, 80, 0.5);
                f.place(position, VELOCITY);
                f.player.isGliding = true;
                f.player.pose = Pose.FALL_FLYING;
                f.player.boundingBox =
                        GetBoundingBox.getPlayerBoundingBox(f.player, position.x, position.y, position.z);
                rocket(f, 100);
                rocket(f, 101);
                Vec3 velocity = VELOCITY;
                for (int tick = 0; tick < 5; tick++) {
                    f.player.xRot = 17.293F + tick * 13.713F;
                    f.player.yRot = -33.713F + tick * 7.31F;
                    if (!vanilla) f.player.trigHandler.toggleShitMath();
                    Vec3 movement;
                    Vec3 carried;
                    try {
                        var look = ReachUtils.getLook(f.player, f.player.xRot, f.player.yRot);
                        movement = ElytraTransform.getElytraMovement(f.player, velocity, look, f.player.yRot, 0.08);
                        carried = boost(boost(movement, look), look);
                    } finally {
                        if (!vanilla) f.player.trigHandler.toggleShitMath();
                    }
                    position = position.add(movement);
                    var result = f.tick(position, false);
                    assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
                    assertEquals(0.0, result.getOffset(), 1e-13, JavaTickFixture.describe(result));
                    var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
                    assertEquals(0.0, movement.distanceTo(move.attempted()), 1e-13);
                    var carry = assertInstanceOf(
                            JavaPredictionCarry.class,
                            f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
                    Vec3 expected = carried;
                    assertTrue(
                            carry.states().stream()
                                    .anyMatch(state -> state.velocity().distanceTo(expected) <= 1e-13),
                            () -> "missing exact rocket carry " + expected + " from " + carry.states());
                    assertTrue(f.player.trigHandler.isVanillaMath());
                    velocity = carried;
                }
            }
        }
    }

    @Test
    void tickingMembershipOrderAndRemovalDetermineTheBoostCount() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            var early = rocket(f, 100);
            var late = rocket(f, 101);
            entities.getSelf().setClientTickOrder(early.getClientTickOrder() + 1);
            late.setClientTickOrder(entities.getSelf().getClientTickOrder() + 1);
            var context = mock(SimulationContext.class);
            when(context.getEntities()).thenReturn(entities);
            when(context.getVersion()).thenReturn(ClientVersion.V_26_3);
            when(context.getWorldData()).thenReturn(mock(WorldData.class));
            when(context.getStart()).thenReturn(new Vec3(0.5, 80, 0.5));
            when(context.isGliding()).thenReturn(true);
            assertEquals(1, f.player.compensatedFireworks.tickingRockets(true));
            assertEquals(1, f.player.compensatedFireworks.tickingRockets(false));
            var look = ReachUtils.getLook(f.player, 0, 0);
            Vec3 boosted = boost(VELOCITY, look);
            assertEquals(
                    List.of(boosted),
                    JavaEntityVelocityTicks.afterTravel(
                            f.player, context, new PredVector(VELOCITY), context.getStart(), VELOCITY));
            assertEquals(
                    0.0,
                    boosted.distanceTo(
                            JavaEntityVelocityTicks.beforeTravel(f.player, context, List.of(new PredVector(VELOCITY)))
                                    .getFirst()),
                    1e-13);

            f.player.packetStateData.serverTicksFrozen = true;
            assertEquals(0, f.player.compensatedFireworks.tickingRockets(true));
            assertEquals(0, f.player.compensatedFireworks.tickingRockets(false));
            f.player.packetStateData.serverFrozenTickStepsRemaining = 1;
            assertEquals(1, f.player.compensatedFireworks.tickingRockets(false));
            f.player.packetStateData.serverTicksFrozen = false;
            late.setPositionRaw(late.getExactMovementCollisionBox().copy().offset(0, 0, 160));
            assertEquals(0, f.player.compensatedFireworks.tickingRockets(false));
            f.player.compensatedFireworks.removeFirework(100);
            assertEquals(0, f.player.compensatedFireworks.tickingRockets(true));
        }
    }

    @Test
    void earlyRocketSeesTheGlideStateBeforeLocalPlayerAiStep() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            var early = rocket(f, 100);
            entities.getSelf().setClientTickOrder(early.getClientTickOrder() + 1);
            var context = mock(SimulationContext.class);
            when(context.getEntities()).thenReturn(entities);
            when(context.getStart()).thenReturn(new Vec3(0.5, 80, 0.5));
            when(context.isGliding()).thenReturn(true);
            f.player.packetStateData.glidingBeforeClientAction = false;
            assertEquals(
                    0.0,
                    VELOCITY.distanceTo(
                            JavaEntityVelocityTicks.beforeTravel(f.player, context, List.of(new PredVector(VELOCITY)))
                                    .getFirst()));
            f.player.packetStateData.glidingBeforeClientAction = true;
            var look = ReachUtils.getLook(f.player, 0, 0);
            assertEquals(
                    0.0,
                    boost(VELOCITY, look)
                            .distanceTo(JavaEntityVelocityTicks.beforeTravel(
                                            f.player, context, List.of(new PredVector(VELOCITY)))
                                    .getFirst()));
        }
    }

    private static Vec3 boost(Vec3 velocity, ac.cult.cultac.utils.math.Vector3dm look) {
        return velocity.add(
                look.getX() * 0.1 + (look.getX() * 1.5 - velocity.x) * 0.5,
                look.getY() * 0.1 + (look.getY() * 1.5 - velocity.y) * 0.5,
                look.getZ() * 0.1 + (look.getZ() * 1.5 - velocity.z) * 0.5);
    }

    /** The old sphere selected Y=0 here, suppressing the floor collision although the rocket cannot boost. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void anAttachedRocketCannotEraseANonGlidingFloorContact(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var floor = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            f.place(floor, new Vec3(0, -0.08 * (double) 0.98F, 0));
            rocket(f, 100);
            var result = f.tick(floor, true);
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(-0.08 * (double) 0.98F, move.attempted().y, 1e-13);
            assertTrue(move.verticalCollisionBelow());
        }
    }

    @Test
    void aRocketScalesOnlyTheBoatPushesThatPrecedeIt() throws Exception {
        for (boolean rocketFirst : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                var entities = f.player.compensatedEntities;
                if (rocketFirst) rocket(f, 100);
                entities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(1.6, 80, 0.5), 0, 0, 0);
                if (!rocketFirst) rocket(f, 100);
                var context = mock(SimulationContext.class);
                var world = mock(WorldData.class);
                when(world.isPushable()).thenReturn(true);
                when(context.getWorldData()).thenReturn(world);
                when(context.getEntities()).thenReturn(entities);
                when(context.getVersion()).thenReturn(ClientVersion.V_26_3);
                when(context.getScale()).thenReturn(1.0F);
                when(context.isGliding()).thenReturn(true);
                Vec3 start = new Vec3(0.54, 80, 0.5);
                var look = ReachUtils.getLook(f.player, 0, 0);
                Vec3 push = new Vec3(-(double) 0.05F, 0, 0);
                Vec3 expected = rocketFirst ? boost(VELOCITY, look).add(push) : boost(VELOCITY.add(push), look);
                var actual = JavaEntityVelocityTicks.afterTravel(
                        f.player, context, new PredVector(VELOCITY), start, VELOCITY);
                assertTrue(actual.stream().anyMatch(v -> v.distanceTo(expected) <= 1e-13), actual.toString());
                Vec3 wrongOrder = rocketFirst
                        ? boost(VELOCITY.add(push), look)
                        : boost(VELOCITY, look).add(push);
                assertTrue(actual.stream().noneMatch(v -> v.distanceTo(wrongOrder) <= 1e-13), actual.toString());
            }
        }
    }
}
