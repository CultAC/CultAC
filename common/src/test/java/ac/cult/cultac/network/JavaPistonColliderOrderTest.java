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
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Two east-facing pistons extend in one tick, the one at z -1 first, so its PistonMovingBlockEntity ticks first in
 * Level#tickBlockEntities. Its stone shoves a boat that reaches into the player's row east, out of the path of the
 * second piston's stone, which then shoves the player. The player's Entity#move(PISTON) collides with the boat where
 * the first ticker left it, so neither stroke meets the boat. PistonMovingBlockEntity#moveCollidedEntities moves a boat
 * riding a marker armor stand the same way, apart from its vehicle, which ArmorStand#getPistonPushReaction leaves
 * alone; the boat's next Entity#rideTick seats it on the stand again.
 */
class JavaPistonColliderOrderTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final BlockPos FIRST = new BlockPos(-1, FLOOR_Y, -1);
    private static final BlockPos SECOND = new BlockPos(-2, FLOOR_Y, 0);
    private static final int BOAT = 100;
    private static final int STAND = 101;

    @ParameterizedTest
    @CsvSource({"V_1_21_11, false", "V_26_3, false", "V_1_21_11, true", "V_26_3, true"})
    void aBoatAnEarlierPistonShovedNoLongerBlocksTheLaterShove(ClientVersion version, boolean riding) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var world = f.player.compensatedWorld;
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            for (BlockPos base : new BlockPos[] {FIRST, SECOND}) {
                world.updateBlock(base, piston);
                world.updateBlock(base.relative(Direction.EAST), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();
            // The boat's west face is 0.25 east of the player, beyond AbstractBoat's 0.2 push reach, but inside the
            // 0.31 the second piston's first stroke shoves the player; it spans both rows.
            double boatWest = 0.5 + HALF_WIDTH + 0.25;
            Vec3 boat = new Vec3(boatWest + 1.375 / 2, FLOOR_Y, 0.1);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boat, 0, 0, 0);
            if (riding) {
                // A marker's 0 x 0 dimensions seat the boat at the stand's own position.
                f.player.compensatedEntities.addEntity(STAND, EntityTypeIds.ARMOR_STAND, boat, 0, 0, 0);
                marker(f);
                f.setPassengers(STAND, List.of(BOAT));
                f.pongThrough(f.player.lastTransactionSent.get());
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            assertExact(f.tick(position, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, FIRST, 0, Direction.EAST.get3DDataValue(), piston);
            JavaPistonSlimeLaunchTest.blockEvent(f, SECOND, 0, Direction.EAST.get3DDataValue(), piston);
            // First strokes: the boat moves to x 1.5 + 0.01, the player's west face from x 0.2 to 0.5 + 0.01.
            Vec3 first = new Vec3(0.5 + 0.01 + HALF_WIDTH, FLOOR_Y, 0.5);
            // Second strokes: the faces reach x 2 and x 1; each shove clears its face by 0.01. The riding boat's
            // Entity#rideTick has seated it on the stand again, and the first piston's 0.5 + 0.01 leaves its west
            // face at x 1.56: the player's shove meets it after 0.45 and, from the ground, steps onto it.
            Vec3 second = new Vec3(1.0 + 0.01 + HALF_WIDTH, FLOOR_Y + (riding ? 0.5625 : 0), 0.5);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // LocalPlayer#sendChanges reports after each pass; a horizontal Entity#move(PISTON) clears onGround.
                assertExact(f.tick(first, false));
                assertExact(f.tick(second, false));
            } else {
                assertExact(f.tick(position, true));
                assertExact(f.tick(first, true));
                // The step's vertical collision zeroed deltaMovement.y, so the next travel's move meets nothing below.
                assertExact(f.tick(second, !riding));
            }
            if (!riding)
                assertEquals(
                        2.0 + 0.01 + 1.375 / 2,
                        f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition.x,
                        1.0E-6,
                        "both strokes of the first piston shove the boat");
        }
    }

    /** ArmorStand's DATA_CLIENT_FLAGS with the marker bit. */
    @SuppressWarnings("unchecked")
    private static void marker(JavaTickFixture f) {
        var type = (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(candidate -> candidate.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
        var packet = new EntityMetadata(STAND, List.of(EntityMetadata.Entry.byteValue(15, (byte) 16)));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
