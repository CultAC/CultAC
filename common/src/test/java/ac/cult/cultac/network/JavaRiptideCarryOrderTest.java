package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** LivingEntity#aiStep rebounds before a later RemotePlayer tick adds Entity#push to the stored velocity. */
class JavaRiptideCarryOrderTest {
    @ParameterizedTest(name = "{0}: a late push follows the spin rebound")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aLateRemotePlayerPushIsNotRebounded(String release, ClientVersion version) throws Exception {
        spinAcrossLatePush(version, false);
    }

    @ParameterizedTest(name = "{0}: sprint stays held across the hit and rebound")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void continuousSprintCarriesTheAttackBeforeTheLatePush(String release, ClientVersion version) throws Exception {
        spinAcrossLatePush(version, true);
    }

    /** The native continuous-sprint failure: leave water, touch a RemotePlayer, then immediately travel in air. */
    @ParameterizedTest(name = "{0}: a water spin carries the late push after the sprint hit")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aWaterSpinReboundsBeforeTheLaterRemotePlayerPush(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(.5, 80.2, .5);
            for (int y = 80; y <= 82; y++)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 target = new Vec3(-2.5, 79, .5);
            f.addRemotePlayer(71, target, GameMode.SURVIVAL);
            f.place(start, new Vec3(-3, -.005, 0));
            f.player.riptideSpinAttackTicks = 10;
            f.player.riptideSpinCertain = true;
            f.player.xRot = 90;
            f.player.yRot = 0;
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, true));
            Vec3 movement = new Vec3(-3 - (double) .98F * .02F, -.005, 0);
            Vec3 position = start.add(movement);
            var hit = f.tick(position, false);
            assertTrue(exact(hit), describe(hit));
            Vec3 travelled = movement.multiply(.9F, .8F, .9F);
            Vec3 impulse = push(target, position);
            Vec3 expected = travelled.multiply(.6, 1, .6).scale(-.2).add(impulse);
            assertCarries(f, expected);
            Vec3 wrong = travelled.add(impulse).multiply(.6, 1, .6).scale(-.2);
            assertEquals(.007840000105, wrong.distanceTo(expected), 1e-10);
            assertFalse(carry(f).states().stream().anyMatch(s -> s.velocity().distanceTo(wrong) < 1e-12));

            var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(
                    f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
            Vec3 nextMove = threshold(version, expected).add(-(double) .98F * .025999999F, 0, 0);
            var next = f.tick(position.add(nextMove), false);
            assertTrue(exact(next), describe(next));
        }
    }

    @ParameterizedTest(name = "{0}: applied baby data resizes the spin target and pusher")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void appliedAgeDataResizesTheExistingMovementBoxes(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.COW, new Vec3(-3.5, 79, .5), 0, 0, 0);
            var entity = f.player.compensatedEntities.getEntity(71);
            var adult = entity.getExactMovementCollisionBox();
            for (boolean baby : new boolean[] {true, false}) {
                f.player.compensatedEntities.updateEntityMetadata(71,
                        List.of(new EntityMetadata.Entry(16, baby, ByteBuffer.allocate(0))),
                        f.player.lastTransactionReceived.get());
                var box = entity.getExactMovementCollisionBox();
                assertEquals(adult.minY, box.minY, 0.0);
                assertEquals((adult.maxY - adult.minY) * (baby ? .5 : 1), box.maxY - box.minY, 1e-12);
                assertEquals((adult.maxX - adult.minX) * (baby ? .5 : 1), box.maxX - box.minX, 1e-12);
            }
        }
    }

    private static void spinAcrossLatePush(ClientVersion version, boolean sprint) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(-4.5, 80, .5);
            Vec3 spin = new Vec3(1.5, 0, 0);
            Vec3 target = start.add(1, -1, 0);
            f.addRemotePlayer(71, target, GameMode.SURVIVAL);
            f.place(start, spin);
            f.player.riptideSpinAttackTicks = 10;
            f.player.riptideSpinCertain = true;
            // The touch attack can reset sprint after travel, before sendPosition reports the flag.
            f.player.isSprinting = false;
            f.input(new ServerboundPlayerInput(sprint, false, false, false, false, false, sprint));
            Vec3 input = new Vec3(0, 0, sprint ? (double) 0.98F * 0.025999999F : 0);
            Vec3 movement = spin.add(input);
            Vec3 position = start.add(movement);
            var hit = f.tick(position, false);
            assertTrue(exact(hit), describe(hit));
            Vec3 travelled = drag(movement);
            Vec3 rebound = sprint ? travelled.multiply(.6, 1, .6).scale(-.2) : travelled.scale(-.2);
            Vec3 push = push(target, position);
            assertTrue(push.horizontalDistanceSqr() > 0, "the later tick must actually push the player");
            Vec3 velocity = rebound.add(push);
            assertCarries(f, velocity);
            Vec3 wrong = (sprint ? travelled.add(push).multiply(.6, 1, .6) : travelled.add(push)).scale(-.2);
            assertFalse(carry(f).states().stream().anyMatch(s -> s.velocity().distanceTo(wrong) < 1e-12),
                    "the later push must not be scaled by the earlier attack/rebound");

            if (sprint) {
                var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
                new PacketEntityAction().onPlayerCommand(
                        f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
            }
            // Keep the same keys held. The hit ends autoSpinAttackTicks; the following travel never rebounds again.
            for (int tick = 0; tick < 3; tick++) {
                movement = threshold(version, velocity).add(input);
                position = position.add(movement);
                var next = f.tick(position, false);
                assertTrue(exact(next), describe(next));
                velocity = drag(movement).add(push(target, position));
                assertCarries(f, velocity);
            }
        }
    }

    private static JavaPredictionCarry carry(JavaTickFixture f) {
        return assertInstanceOf(JavaPredictionCarry.class,
                f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
    }

    private static void assertCarries(JavaTickFixture f, Vec3 expected) {
        var carry = carry(f);
        assertTrue(carry.states().stream().anyMatch(s -> s.velocity().distanceTo(expected) < 1e-12),
                () -> "missing " + expected + " in " + carry.states());
    }

    private static Vec3 drag(Vec3 velocity) {
        return new Vec3(velocity.x * .91F, (velocity.y - .08) * .98F, velocity.z * .91F);
    }

    /** LivingEntity#aiStep switches player X/Z from per-axis to horizontal-length thresholding in 1.21.5. */
    private static Vec3 threshold(ClientVersion version, Vec3 velocity) {
        boolean legacy = version.isOlderThan(ClientVersion.V_1_21_5);
        boolean horizontal = velocity.horizontalDistanceSqr() < 9e-6;
        return new Vec3(
                (legacy ? Math.abs(velocity.x) < .003 : horizontal) ? 0 : velocity.x,
                Math.abs(velocity.y) < .003 ? 0 : velocity.y,
                (legacy ? Math.abs(velocity.z) < .003 : horizontal) ? 0 : velocity.z);
    }

    /** Entity#push, after this stationary target's tick; its box overlaps all four local endpoints. */
    private static Vec3 push(Vec3 target, Vec3 player) {
        double x = target.x - player.x, z = target.z - player.z;
        double root = Math.sqrt(Math.max(Math.abs(x), Math.abs(z)));
        double scale = Math.min(1 / root, 1);
        return new Vec3(-(x / root * scale * .05F), 0, -(z / root * scale * .05F));
    }
}
