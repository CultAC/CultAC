package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaRemotePistonTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** A remote pusher's piston move happens after this tick's player travel, even when it sends no position. */
    @Test
    void theIdlePlayerCollectsEntityPushesBeforePistonsMoveTheRemoteEntityAway() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 playerPosition = new Vec3(0.5, FLOOR_Y, -0.15);
            f.place(playerPosition, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            f.tick(playerPosition, true);
            // The NPC's exact -Z push cancels an existing +Z velocity, so the client's position stays still.
            f.place(playerPosition, new Vec3(0, -0.0784000015258789, 0.05 * 0.55 / Math.sqrt(0.55)));
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.ZOMBIE, new Vec3(0.5, FLOOR_Y - 1.75, 0.4), 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.setClientTickOrder(-1);
            int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "south");
            BlockPos base = new BlockPos(0, FLOOR_Y - 2, -2);
            f.player.compensatedWorld.updateBlock(base, piston);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y - 2, -1),
                    BLOCKS.block("minecraft:stone").defaultState());
            f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 3, 0);

            f.tickEnd();

            var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertEquals(
                    1,
                    result.getSimulationContext().getWorldData().getNumPushing(),
                    "the NPC is still touching when entities tick, before the piston separates them");
            assertTrue(actor.clientPhysicalPosition.z > 0.65, "the same completed tick must still push the NPC away");
            assertFalse(result.hasEffectiveFlags(), result.getFlags().toString());
        }
    }

    /** Entity#move starts preserving remote ground on horizontal moves in 1.21.4. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void honeyCarriesARemoteGroundedEntityUntilItsGroundStateChanges(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(-0.5, FLOOR_Y + 0.9375, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            var state = new JavaPassState(
                    start,
                    new Vec3(0, -0.0784000015258789, 0),
                    true,
                    new MainSupportingBlockData(new BlockPos(-1, FLOOR_Y, 0), false),
                    null,
                    0,
                    ClientPistonDeltas.NONE);
            int honey = BLOCKS.block("minecraft:honey_block").defaultState();
            for (int tick = 0; tick < 2; tick++) {
                var piston = new ClientBlockEntityPass.Piston(
                        new BlockPos(0, FLOOR_Y, 0), honey, Direction.EAST, true, false, tick * 0.5F);
                state = JavaBlockEntityPass.runEntity(
                                f.player, actor, new ClientBlockEntityPass(List.of(piston), 10 + tick), state)
                        .state();
                boolean preservesGround = version.isNewerThanOrEquals(ClientVersion.V_1_21_4);
                assertEquals(start.x + (preservesGround ? tick + 1 : 1) * 0.5, state.position().x, 1.0E-12);
                assertEquals(start.y, state.position().y);
                assertEquals(preservesGround, state.onGround());
                assertEquals(new Vec3(0, -0.0784000015258789, 0), state.velocity());
                actor.onMovement(f.player);
                assertEquals(
                        state.position(),
                        actor.clientPhysicalPosition,
                        "an idle interpolation handler must not snap the piston displacement back");
            }
        }
    }

    /** Only 26.3 gates slime's velocity callback on canSimulateMovement; older clients exclude ServerPlayer. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void slimePushesARemoteMobWithTheVersionsOwnVelocityCallback(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 1.8);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            Vec3 velocity = new Vec3(0.04, -0.0784000015258789, 0.02);
            var state = new JavaPassState(
                    start, velocity, true, new MainSupportingBlockData(null, true), null, 0, ClientPistonDeltas.NONE);
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 1, 1),
                    BLOCKS.block("minecraft:slime_block").defaultState(),
                    Direction.NORTH,
                    true,
                    false,
                    0);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), state)
                    .state();
            assertEquals(start.z - 0.51, after.position().z, 1.0E-12);
            assertEquals(
                    version.isOlderThan(ClientVersion.V_26_3) ? new Vec3(velocity.x, velocity.y, -1) : velocity,
                    after.velocity());
            assertEquals(version.isNewerThanOrEquals(ClientVersion.V_1_21_4), after.onGround());
        }
    }

    /** AbstractInterpolationHandler#applyPredictedMovement shifts the queued path and leaves the codec untouched. */
    @Test
    void aPistonMovesTheRemoteEntityAndItsRemainingPath() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.onBundledPositionSyncTransaction(3.5, start.y, start.z, f.player);
            actor.onMovement(f.player);
            assertEquals(0.5 + 3.0 * (1.0F / 3), actor.clientPhysicalPosition.x, 1.0E-12);
            Vec3 codec = actor.desyncClientPos;
            Vec3 moved = actor.clientPhysicalPosition.add(0.51, 0, 0);
            actor.applyBlockEntityMovement(
                    f.player,
                    GetBoundingBox.getPacketEntityBoundingBox(f.player, moved.x, moved.y, moved.z, actor),
                    new Vec3(0.51, 0, 0));
            assertEquals(codec, actor.desyncClientPos);
            actor.onMovement(f.player);
            assertEquals(1.01 + 3.0 * (2.0F / 3), actor.clientPhysicalPosition.x, 1.0E-12);
            actor.onMovement(f.player);
            assertEquals(4.01, actor.clientPhysicalPosition.x, 1.0E-12);
        }
    }

    /** A solid target rejects the interpolation shift while the current physical piston movement still happens. */
    @Test
    void aBlockedShiftDoesNotMoveTheServerInterpolationTarget() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.onBundledPositionSyncTransaction(3.5, start.y, start.z, f.player);
            actor.onMovement(f.player);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(4, FLOOR_Y + 10, 0),
                    BLOCKS.block("minecraft:stone").defaultState());
            Vec3 moved = actor.clientPhysicalPosition.add(0.51, 0, 0);
            actor.applyBlockEntityMovement(
                    f.player,
                    GetBoundingBox.getPacketEntityBoundingBox(f.player, moved.x, moved.y, moved.z, actor),
                    new Vec3(0.51, 0, 0));
            actor.onMovement(f.player);
            actor.onMovement(f.player);
            assertEquals(3.5, actor.clientPhysicalPosition.x, 1.0E-12);
        }
    }
}
