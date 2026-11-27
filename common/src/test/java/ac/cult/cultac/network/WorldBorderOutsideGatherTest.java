package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundInitializeBorder;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * WorldBorder#isInsideCloseToBorder holds for an entity up to bbMax outside the border's edge, so Entity#collide
 * gathers the border's shape there too. That shape is everything outside the floored and ceiled border box: a box
 * outside the edge meets each line of that grid whose next cell is filled, and crosses a line into the open interior.
 */
class WorldBorderOutsideGatherTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    /** Movement speed 0.1 under Speed I (effect.speed 0.2) and the sprint modifier. */
    private static final float SPEED = (float) ((double) 0.1F * (1.0D + 0.2D) * (1.0D + (double) 0.3F));
    /** A border of 10 around 0, 0: the box from -5 to 5 on both axes. */
    private static final double SIZE = 10.0;
    /** The half width of the player's 0.6F box. */
    private static final double HALF = (double) (0.6F / 2.0F);

    private record Client(ProtocolVersion protocol, ClientVersion version) {}

    private static final List<Client> CLIENTS = List.of(
            new Client(ProtocolVersion.V26_3, ClientVersion.V_26_3),
            new Client(ProtocolVersion.V26_2, ClientVersion.V_26_2),
            new Client(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11));

    /**
     * On 26.3, with a border of 10 around 8000, 18000 moved by -8000, -18000, the player stands outside the border's
     * south-west corner and sprints at yaw -112.73 for one tick, then at yaw -112.62 into the west line, which stops
     * its box at x -5 while z moves freely.
     */
    @Test
    void aSprintOutsideTheCornerStopsAtTheWestLine() {
        eachClient(f -> {
            Vec3 at = new Vec3(7994.252094821581 - 8000.0, FLOOR_Y, 18005.981385474144 - 18000.0);
            f.place(at, new Vec3(0.21385072156863316 * FRICTION, 0.0, 0.046361439515749225 * FRICTION));
            f.player.xRot = -112.73443603515625F;
            Vec3 second = new Vec3(7994.509863312537 - 8000.0, FLOOR_Y, 18005.947625913628 - 18000.0);
            PredictionResult free = f.tick(second, true);
            assertTrue(exact(free), "first tick: " + describe(free));

            f.player.xRot = -112.61691284179688F;
            Vec3 velocity = new Vec3((second.x - at.x) * FRICTION, 0.0, (second.z - at.z) * FRICTION);
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, SPEED, f.player.xRot);
            Vec3 clipped = new Vec3(-SIZE / 2.0 - HALF, FLOOR_Y, second.z + velocity.z + input.z);
            PredictionResult stopped = f.tick(clipped, true);
            assertTrue(exact(stopped), "second tick: " + describe(stopped));
        });
    }

    /** The same sprint reported through the west line does not follow vanilla. */
    @Test
    void aSprintOutsideTheCornerThroughTheWestLineFlags() {
        eachClient(f -> {
            Vec3 at = new Vec3(7994.509863312537 - 8000.0, FLOOR_Y, 18005.947625913628 - 18000.0);
            Vec3 velocity = new Vec3(0.2577684909565505 * FRICTION, 0.0, -0.033759560516045894 * FRICTION);
            f.place(at, velocity);
            f.player.xRot = -112.61691284179688F;
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, SPEED, f.player.xRot);
            PredictionResult result = f.tick(at.add(velocity.x + input.x, 0.0, velocity.z + input.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        });
    }

    /** Outside the corner, a sprint towards -z stops with the box on the south line: the cell past it is filled. */
    @Test
    void aSprintOutsideTheCornerStopsAtTheSouthLine() {
        eachClient(f -> {
            Vec3 at = new Vec3(-SIZE / 2.0 - HALF - 0.1, FLOOR_Y, SIZE / 2.0 + HALF + 0.1);
            Vec3 velocity = new Vec3(0.0, 0.0, -0.15);
            f.place(at, velocity);
            f.player.xRot = 180.0F;
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, SPEED, f.player.xRot);
            PredictionResult result = f.tick(new Vec3(at.x + input.x, FLOOR_Y, SIZE / 2.0 + HALF), true);
            assertTrue(exact(result), describe(result));
        });
    }

    /**
     * A player just outside the west edge whose box lies within the border's z range crosses the west line into the
     * open interior: the cell beyond it is empty, so nothing stops the box there.
     */
    @Test
    void aSprintFromOutsideTheWestEdgeIntoTheBorderCrossesTheLine() {
        eachClient(f -> {
            Vec3 at = new Vec3(-SIZE / 2.0 - HALF - 0.1, FLOOR_Y, 0.5);
            Vec3 velocity = new Vec3(0.15, 0.0, 0.0);
            f.place(at, velocity);
            f.player.xRot = -90.0F;
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, SPEED, f.player.xRot);
            PredictionResult result = f.tick(at.add(velocity.x + input.x, 0.0, velocity.z + input.z), true);
            assertTrue(exact(result), describe(result));
        });
    }

    private interface Scenario {
        void run(JavaTickFixture f) throws Exception;
    }

    private static void eachClient(Scenario scenario) {
        List<Executable> rows = new ArrayList<>();
        for (Client client : CLIENTS) {
            rows.add(() -> {
                try (var f = fixture(client)) {
                    scenario.run(f);
                }
            });
        }
        assertAll(rows);
    }

    private static JavaTickFixture fixture(Client client) throws Exception {
        JavaTickFixture f = new JavaTickFixture(client.protocol(), client.version());
        var packet = new ClientboundInitializeBorder(0.0, 0.0, SIZE, SIZE, 0L, 29_999_984);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.INITIALIZE_BORDER, packet, false);
        f.player.checkManager.getWorldBorder().onInitializeBorder(event, f.player, packet);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot(
                        "minecraft:movement_speed",
                        0.1F,
                        List.of(new AttributeModifier(
                                "minecraft:effect.speed", 0.2D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)))));
        f.input(SPRINT);
        f.command(PlayerCommandAction.START_SPRINTING);
        return f;
    }
}
