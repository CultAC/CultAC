package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.movement.GhostFriction;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client keeps a refused placement until it applies the block-changed ack, and LivingEntity#travelInAir takes that
 * block's friction. Simulation accepts the slide; GhostFriction takes away the speed the server's block would not give.
 */
class GhostFrictionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final int PACKED_ICE =
            DataTables.defaults().registry().block("minecraft:packed_ice").defaultState();
    private static final int STONE = BlockIds.STONE.defaultState();
    private static final int AIR = BlockIds.AIR.defaultState();
    private static final double ICE_FRICTION = 0.98F * 0.91F;
    private static final double STONE_FRICTION = 0.6F * 0.91F;
    // A client standing on the ground: LivingEntity#travelInAir's gravity, (0 - 0.08) * 0.98F.
    private static final double GROUNDED_Y = (0.0 - 0.08) * (double) 0.98F;
    // LivingEntity#getFrictionInfluencedSpeed for a walking client on packed ice, times the 0.98F input scale.
    private static final double ICE_WALK = (double) 0.98F * (double) (0.1F * (0.21600002F / (0.98F * 0.98F * 0.98F)));
    private static final double STONE_WALK = (double) 0.98F * (double) (0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    private static final double AIR_FRICTION = 0.91F;

    @Test
    void aSlideOnRefusedIceLosesTheSpeedTheIceGave() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, STONE);
            drain(f);

            double z = 0.5;
            double velocity = 0.5;
            f.place(new Vec3(0.5, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, velocity));
            f.input(NONE);
            for (int tick = 0; tick < 2; tick++) {
                z += velocity;
                PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                velocity *= ICE_FRICTION;
            }
            assertEquals(1, check.violations, "the second tick moves faster than stone allows");

            // Without the ice the first tick carried 0.5 * stone friction, the second moved that and carried less.
            double serverVelocity = 0.5 * STONE_FRICTION * STONE_FRICTION;
            List<ClientboundEntityMotion> motions = motions(f);
            assertEquals(1, motions.size());
            assertEquals(0.0, motions.getFirst().velocity().x(), 1.0E-4);
            assertEquals(GROUNDED_Y, motions.getFirst().velocity().y(), 1.0E-4);
            assertEquals(serverVelocity, motions.getFirst().velocity().z(), 1.0E-4);

            // The client applies the server's ack and the correction before its next tick.
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(new BlockPos(0, FLOOR_Y - 1, 1)));
            double sent = motions.getFirst().velocity().z();
            for (int tick = 0; tick < 3; tick++) {
                z += sent;
                PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), "after the correction, tick " + tick + " " + describe(result));
                sent *= STONE_FRICTION;
            }
            assertEquals(1, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    @Test
    void anAcceptedIcePlacementKeepsItsSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, PACKED_ICE);
            drain(f);
            slide(f, 6);
            assertEquals(0, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    @Test
    void aPlacementTheServerHasNotAnsweredKeepsItsSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            drain(f);
            slide(f, 6);
            assertEquals(0, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    @Test
    void aDisabledCheckLeavesTheSlide() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            check.setEnabled(false);
            predictIce(f);
            answer(f, STONE);
            drain(f);
            slide(f, 6);
            assertEquals(0, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    /** Ice holds more speed than stone but builds it slower: walking on refused ice is never faster than on stone. */
    @Test
    void walkingOnRefusedIceIsNotCorrected() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, STONE);
            drain(f);

            double z = -6.5;
            double velocity = 0.0;
            f.place(new Vec3(0.5, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, 0));
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.input(FORWARD);
            for (int tick = 0; tick < 40; tick++) {
                velocity += ICE_WALK;
                z += velocity;
                PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                velocity *= ICE_FRICTION;
            }
            assertEquals(0, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    /** The correction carries the walk the client made, and the client's next tick adds stone's grip by itself. */
    @Test
    void aWalkOnRefusedIceIsCorrectedToTheStoneWalk() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, STONE);
            drain(f);

            double z = 0.5;
            double velocity = 0.5;
            double serverVelocity = 0.5;
            f.place(new Vec3(0.5, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, velocity));
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.input(FORWARD);
            for (int tick = 0; tick < 2; tick++) {
                velocity += ICE_WALK;
                z += velocity;
                PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                velocity *= ICE_FRICTION;
                serverVelocity = (serverVelocity + STONE_WALK) * STONE_FRICTION;
            }
            assertEquals(1, check.violations);
            List<ClientboundEntityMotion> motions = motions(f);
            assertEquals(1, motions.size());
            assertEquals(serverVelocity, motions.getFirst().velocity().z(), 1.0E-4);

            f.pongThrough(f.player.lastTransactionSent.get());
            double sent = motions.getFirst().velocity().z();
            for (int tick = 0; tick < 3; tick++) {
                sent += STONE_WALK;
                z += sent;
                PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), "after the correction, tick " + tick + " " + describe(result));
                sent *= STONE_FRICTION;
            }
            assertEquals(1, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    /** A correction during a jump keeps the client's vertical velocity. */
    @Test
    void aCorrectionDuringAJumpKeepsTheJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, STONE);
            drain(f);

            double z = 0.5;
            double velocity = 0.5;
            f.place(new Vec3(0.5, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, velocity));
            f.input(NONE);
            z += velocity;
            assertTrue(exact(f.tick(new Vec3(0.5, FLOOR_Y, z), true)));
            velocity *= ICE_FRICTION;

            f.input(JUMP);
            double y = FLOOR_Y + (double) 0.42F;
            z += velocity;
            PredictionResult jump = f.tick(new Vec3(0.5, y, z), false);
            assertTrue(exact(jump), describe(jump));
            assertEquals(1, check.violations);
            double verticalVelocity = ((double) 0.42F - 0.08) * (double) 0.98F;
            List<ClientboundEntityMotion> motions = motions(f);
            assertEquals(1, motions.size());
            assertEquals(verticalVelocity, motions.getFirst().velocity().y(), 1.0E-4);
            assertEquals(0.5 * STONE_FRICTION * STONE_FRICTION, motions.getFirst().velocity().z(), 1.0E-4);

            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            verticalVelocity = motions.getFirst().velocity().y();
            double sent = motions.getFirst().velocity().z();
            for (int tick = 0; tick < 3; tick++) {
                y += verticalVelocity;
                z += sent;
                PredictionResult result = f.tick(new Vec3(0.5, y, z), false);
                assertTrue(exact(result), "after the correction, tick " + tick + " " + describe(result));
                verticalVelocity = (verticalVelocity - 0.08) * (double) 0.98F;
                sent *= AIR_FRICTION;
            }
            assertEquals(1, check.violations);
            assertTrue(motions(f).isEmpty());
        }
    }

    /** Where the server left air, the ice's speed is measured against a block of default friction. */
    @Test
    void aSlideOnIceRefusedOverAGapLosesTheIceSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, AIR);
            drain(f);
            slide(f, 2);
            assertEquals(1, check.violations);
            List<ClientboundEntityMotion> motions = motions(f);
            assertEquals(1, motions.size());
            assertEquals(0.5 * STONE_FRICTION * STONE_FRICTION, motions.getFirst().velocity().z(), 1.0E-4);
        }
    }

    @Test
    void aPendingCorrectionIsNotRepeated() throws Exception {
        try (var f = new JavaTickFixture()) {
            GhostFriction check = enable(f);
            predictIce(f);
            answer(f, STONE);
            drain(f);
            slide(f, 5);
            assertEquals(1, check.violations);
            assertEquals(1, motions(f).size());
        }
    }

    private static void slide(JavaTickFixture f, int ticks) {
        double z = 0.5;
        double velocity = 0.5;
        f.place(new Vec3(0.5, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, velocity));
        f.input(NONE);
        for (int tick = 0; tick < ticks; tick++) {
            z += velocity;
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
            assertTrue(exact(result), "tick " + tick + " " + describe(result));
            velocity *= ICE_FRICTION;
        }
    }

    private static GhostFriction enable(JavaTickFixture f) {
        f.player.nettyScheduler.removeScheduler();
        f.fixture.channel.runPendingTasks();
        f.fixture.registrar.register(routes -> {
            routes.send(
                    ClientboundPackets.PING,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketPingListener().onPing(event, player, packet));
            routes.send(
                    ClientboundPackets.ENTITY_MOTION,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.packetEntityReplication.onSetEntityMotion(event, player, packet));
        });
        GhostFriction check = f.player.checkManager.getListener(GhostFriction.class);
        check.setEnabled(true);
        return check;
    }

    /** One placement the client predicts: packed ice in the floor under the slide. */
    private static void predictIce(JavaTickFixture f) {
        CompensatedWorld world = f.player.compensatedWorld;
        world.advanceClientPredictionSequence(world.clientPredictionSequence() + 1);
        world.startPredicting();
        for (BlockPos pos : floor()) world.updateBlock(pos, PACKED_ICE);
        world.stopPredicting(world.clientPredictionSequence());
        for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);
    }

    /** The server's block updates for the placement, then its block-changed ack, both still unread by the client. */
    private static void answer(JavaTickFixture f, int state) {
        CompensatedWorld world = f.player.compensatedWorld;
        f.player.sendTransaction();
        int transaction = f.player.lastTransactionSent.get();
        for (BlockPos pos : floor()) world.queueServerBlockUpdate(pos.x(), pos.y(), pos.z(), state, transaction);
        world.handlePredictionConfirmation(world.clientPredictionSequence());
    }

    private static List<BlockPos> floor() {
        List<BlockPos> floor = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -8; z <= 8; z++) floor.add(new BlockPos(x, FLOOR_Y - 1, z));
        }
        return floor;
    }

    private static void drain(JavaTickFixture f) {
        motions(f);
    }

    private static List<ClientboundEntityMotion> motions(JavaTickFixture f) {
        List<ClientboundEntityMotion> motions = new ArrayList<>();
        ByteBuf frame;
        while ((frame = f.fixture.channel.readOutbound()) != null) {
            try {
                int id = Wire.readVarInt(frame);
                Object packet = f.fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame);
                if (packet instanceof ClientboundEntityMotion motion) motions.add(motion);
            } finally {
                frame.release();
            }
        }
        return motions;
    }
}
