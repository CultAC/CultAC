package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A player standing on a Shulker. Entity#collide stops the player on the box Shulker#makeBoundingBox gives a live
 * shulker, which its peek opens upwards from the floor it is attached to. Each client tick Shulker#tick steps the peek
 * 0.05 towards DATA_PEEK_ID, and a growing peek lifts the player with Entity#move(MoverType.SHULKER) at the shulker's
 * place in ClientLevel#tickEntities: before the player's travel, or after it, which from 26.3 is before the report.
 */
class JavaShulkerEntityTest {
    private static final int SHULKER = 100;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final Vec3 SHULKER_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void standingOnAClosedShulkerIsExact(ClientVersion version) throws Exception {
        for (boolean shulkerFirst : new boolean[] {false, true}) {
            try (var f = standingOnShulker(version, shulkerFirst)) {
                var client = new Client(version, shulkerFirst);
                for (int tick = 0; tick < 6; tick++) assertExact(f, client.tick(), version + " tick " + tick);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aPeekingShulkerLiftsAndLowersThePlayerOnIt(ClientVersion version) throws Exception {
        for (boolean shulkerFirst : new boolean[] {false, true}) {
            for (byte peek : new byte[] {30, 100}) {
                try (var f = standingOnShulker(version, shulkerFirst)) {
                    var client = new Client(version, shulkerFirst);
                    String name = version + " " + (shulkerFirst ? "shulker first" : "player first") + " " + peek;
                    assertExact(f, client.tick(), name + " standing");
                    // The bundle's ping is answered in the prelude of the tick that handles DATA_PEEK_ID.
                    f.pongThrough(peek(f, peek));
                    client.raw = peek;
                    for (int tick = 0; tick < 24; tick++) assertExact(f, client.tick(), name + " opening " + tick);
                    f.pongThrough(peek(f, (byte) 0));
                    client.raw = 0;
                    for (int tick = 0; tick < 24; tick++) assertExact(f, client.tick(), name + " closing " + tick);
                }
            }
        }
    }

    /**
     * A lid reopened one step short of closed climbs by float steps that end just under its target, so its last step
     * lifts the player 4.8e-5: less than the 2.0E-4 LocalPlayer#sendPosition withholds a position below. The client
     * stands on the open lid from then on, wherever it last reported.
     */
    @Test
    void aLiftSendPositionWithholdsStillLeavesThePlayerOnTheLid() throws Exception {
        ClientVersion version = ClientVersion.V_26_3;
        try (var f = standingOnShulker(version, false)) {
            var client = new Client(version, false);
            var sender = new Sender(f);
            sender.tick(client.tick(), "standing");
            f.pongThrough(peek(f, (byte) 100));
            client.raw = 100;
            for (int tick = 0; tick < 24; tick++) sender.tick(client.tick(), "opening " + tick);
            f.pongThrough(peek(f, (byte) 0));
            client.raw = 0;
            for (int tick = 0; tick < 19; tick++) sender.tick(client.tick(), "closing " + tick);
            f.pongThrough(peek(f, (byte) 50));
            client.raw = 50;
            for (int tick = 0; tick < 16; tick++) sender.tick(client.tick(), "reopening " + tick);
            assertTrue(sender.withheldLift, "the scenario must end on a lift sendPosition withholds");
            assertEquals(client.y, f.player.y, 1.0E-12);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aPeekTheClientHasNotHandledLiftsNothing(ClientVersion version) throws Exception {
        try (var f = standingOnShulker(version, false)) {
            var client = new Client(version, false);
            peek(f, (byte) 100);
            for (int tick = 0; tick < 3; tick++) assertExact(f, client.tick(), version + " unhandled " + tick);
            var opened = new Client(version, false);
            opened.raw = 100;
            opened.tick();
            Report lifted = opened.tick();
            assertTrue(lifted.y > client.y, "the replica lifts the player");
            assertRejected(f.tick(new Vec3(SHULKER_AT.x, lifted.y, SHULKER_AT.z), lifted.onGround), "VerticalOffset");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void hoveringAboveAClosingShulkerIsRejected(ClientVersion version) throws Exception {
        for (boolean shulkerFirst : new boolean[] {false, true}) {
            try (var f = standingOnShulker(version, shulkerFirst)) {
                var client = new Client(version, shulkerFirst);
                String name = version + " " + (shulkerFirst ? "shulker first" : "player first");
                assertExact(f, client.tick(), name + " standing");
                f.pongThrough(peek(f, (byte) 30));
                client.raw = 30;
                for (int tick = 0; tick < 12; tick++) assertExact(f, client.tick(), name + " opening " + tick);
                f.pongThrough(peek(f, (byte) 0));
                client.raw = 0;
                double open = client.y;
                Report first = client.tick();
                if (first.y == open) {
                    assertExact(f, first, name + " closing");
                    first = client.tick();
                }
                assertTrue(first.y < open, name + " the replica lowers the player");
                assertRejected(f.tick(new Vec3(SHULKER_AT.x, open, SHULKER_AT.z), true), "VerticalOffset");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void sinkingIntoAnOpenShulkerIsRejected(ClientVersion version) throws Exception {
        try (var f = standingOnShulker(version, false)) {
            var client = new Client(version, false);
            f.pongThrough(peek(f, (byte) 30));
            client.raw = 30;
            for (int tick = 0; tick < 12; tick++) assertExact(f, client.tick(), version + " opening " + tick);
            // The closed shulker's top lies below the open one's by more than one tick's fall.
            PredictionResult closedTop = f.tick(new Vec3(SHULKER_AT.x, FLOOR_Y + 1.0, SHULKER_AT.z), true);
            assertRejected(closedTop, "VerticalOffset");
        }
    }

    /**
     * Player#maybeBackOffFromEdge keeps a sneaking player on the shulker: Player#canFallAtLeast's
     * CollisionGetter#noCollision counts the shulker's box with the blocks. The held deltaMovement carries.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void sneakingHoldsThePlayerAtTheShulkersEdge(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
            // The shulker's box ends at z=1; the player's box reaches 1.0E-6 past it.
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1.0, 1.299999);
            double velocity = 0.05;
            f.place(position, new Vec3(0, GROUNDED_Y, velocity));
            f.player.onGround = true;
            f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, true, false));
            for (int tick = 0; tick < 4; tick++) {
                float input = tick == 0 ? 0.98F : 0.98F * 0.3F;
                velocity = (velocity + (double) input * 0.1F) * (0.6F * 0.91F);
                var held = f.tick(position, true);
                assertTrue(exact(held), version + " held " + tick + ": " + describe(held));
            }
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            position = position.add(0, 0, velocity + (double) (0.98F * 0.3F) * 0.1F);
            var released = f.tick(position, true);
            assertTrue(exact(released), version + " released: " + describe(released));
        }
    }

    /**
     * A shulker attached to a wall opens towards the player standing on the floor beside it and pushes it sideways;
     * Entity#move leaves the local player off the ground after any such move.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aWallShulkerPushesThePlayerBesideIt(ClientVersion version) throws Exception {
        for (boolean shulkerFirst : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                String name = version + " " + (shulkerFirst ? "shulker first" : "player first");
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
                if (shulkerFirst) f.player.compensatedEntities.appendSelfToClientTickOrder();
                // The player's west face touches the shulker's east face.
                f.place(new Vec3(1.0 + (double) 0.3F, FLOOR_Y, SHULKER_AT.z), new Vec3(0.0, GROUNDED_Y, 0.0));
                f.input(NONE);
                f.pongThrough(attachFace(f, Direction.WEST));
                var client = new SideClient(version, shulkerFirst);
                assertSideExact(f, client.tick(), name + " standing");
                f.pongThrough(peek(f, (byte) 30));
                client.raw = 30;
                for (int tick = 0; tick < 10; tick++) assertSideExact(f, client.tick(), name + " opening " + tick);
            }
        }
    }

    /**
     * A shulker attached to the wall west of it and open towards the east is shoved one block east by a piston:
     * Entity#move(PISTON) and Shulker#setPos put it on the next block centre. The player stands still on the part of
     * the shell both places share, so the client sends no position. ClientLevel#tickEntities ticks the player before
     * the shulker, so the next travel collides with the box Shulker#makeBoundingBox opens once from the new centre by
     * the peek the shulker held before its own tick.
     */
    @Test
    void anIdlePlayerStaysOnAShulkerAPistonMoved() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
            Vec3 aside = new Vec3(5.5, FLOOR_Y, 0.5);
            f.place(aside, new Vec3(0.0, GROUNDED_Y, 0.0));
            f.input(NONE);
            f.pongThrough(attachFace(f, Direction.WEST));
            f.pongThrough(peek(f, (byte) 100));
            for (int tick = 0; tick < 21; tick++) {
                PredictionResult opening = f.tick(aside, true);
                assertTrue(exact(opening), "opening " + tick + ": " + describe(opening));
            }
            // The open shell spans x 0..2; the player stands over x 1..2, which the shell keeps after the shove.
            Vec3 onShell = new Vec3(1.5, FLOOR_Y + 1.0, 0.5);
            f.place(onShell, new Vec3(0.0, GROUNDED_Y, 0.0));
            for (int tick = 0; tick < 2; tick++) {
                PredictionResult standing = f.tick(onShell, true);
                assertTrue(exact(standing), "standing " + tick + ": " + describe(standing));
            }
            f.player
                    .compensatedEntities
                    .getEntity(SHULKER)
                    .applyBlockEntityMovement(
                            f.player, new SimpleCollisionBox(1, FLOOR_Y, 0, 2, FLOOR_Y + 1, 1), new Vec3(1, 0, 0));
            f.tickEnd();
            PredictionResult idle =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(exact(idle), "after the shove: " + describe(idle));
        }
    }

    /**
     * LivingEntity#getPassengerRidingPosition seats a passenger from Entity#position(), not the shell
     * Shulker#makeBoundingBox opens, at the attachment of getDimensions: Shulker#getDefaultDimensions scales a floor
     * shulker's height by 1 + currentPeekAmount while it is open. A player sits Avatar#DEFAULT_VEHICLE_ATTACHMENT
     * lower.
     */
    @ParameterizedTest
    @EnumSource(
            value = Direction.class,
            names = {"DOWN", "UP", "WEST", "NORTH"})
    void anOpenShulkerSeatsItsPassengerOnItsPosition(Direction face) throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
            Vec3 aside = new Vec3(5.5, FLOOR_Y, 0.5);
            f.place(aside, new Vec3(0.0, GROUNDED_Y, 0.0));
            f.input(NONE);
            f.pongThrough(attachFace(f, face));
            f.pongThrough(peek(f, (byte) 100));
            for (int tick = 0; tick < 21; tick++) f.tick(aside, true);
            PacketEntity shulker = entities.getEntity(SHULKER);
            SimpleCollisionBox shell = shulker.getPossibleMovementCollisionBoxes();
            // The shell has opened a whole block towards the face opposite its attachment.
            assertEquals(4.0, shell.maxX - shell.minX + shell.maxY - shell.minY + shell.maxZ - shell.minZ, 1e-6);

            double height = face == Direction.DOWN ? 2.0 : 1.0;
            Vec3 seat = new Vec3(SHULKER_AT.x, FLOOR_Y + height - 0.6, SHULKER_AT.z);
            Vec3 riding = BoundingBoxSize.getRidingOffsetFromVehicle(shulker, entities.getSelf(), f.player);
            assertEquals(seat.x, riding.x, 1e-9, face + " x");
            assertEquals(seat.y, riding.y, 1e-6, face + " y");
            assertEquals(seat.z, riding.z, 1e-9, face + " z");
            // The local player's ride-tick seat and reach origins read the same position candidates.
            for (SimpleCollisionBox box : entities.ridingPositionCandidates(shulker)) {
                Vec3 candidate = BoundingBoxSize.getRidingOffsetFromVehicle(shulker, entities.getSelf(), f.player, box);
                assertEquals(seat.x, candidate.x, 1e-9, face + " candidate x");
                assertEquals(seat.z, candidate.z, 1e-9, face + " candidate z");
            }
        }
    }

    private static void assertSideExact(JavaTickFixture f, Report report, String name) {
        PredictionResult result = f.tick(new Vec3(report.y, FLOOR_Y, SHULKER_AT.z), report.onGround);
        assertTrue(exact(result), name + " x " + report.y + " ground " + report.onGround + ": " + describe(result));
    }

    /** The shulker's DATA_ATTACH_FACE_ID; its proof transaction. */
    private static int attachFace(JavaTickFixture f, Direction face) {
        var entry = new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_ATTACH_FACE, face, ByteBuffer.wrap(new byte[] {
            (byte) WatchableIndexUtil.SHULKER_ATTACH_FACE, 11, (byte) face.ordinal()
        }));
        var packet = new EntityMetadata(SHULKER, List.of(entry));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static void assertExact(JavaTickFixture f, Report report, String name) {
        PredictionResult result = f.tick(new Vec3(SHULKER_AT.x, report.y, SHULKER_AT.z), report.onGround);
        assertTrue(exact(result), name + " y " + report.y + " ground " + report.onGround + ": " + describe(result));
    }

    /** A survival player of {@code version} at rest on a closed shulker attached to the floor. */
    private static JavaTickFixture standingOnShulker(ClientVersion version, boolean shulkerFirst) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
        if (shulkerFirst) f.player.compensatedEntities.appendSelfToClientTickOrder();
        f.place(new Vec3(SHULKER_AT.x, FLOOR_Y + 1.0, SHULKER_AT.z), new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.onGround = true;
        f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.input(NONE);
        return f;
    }

    /** The shulker's DATA_PEEK_ID; its proof transaction. */
    private static int peek(JavaTickFixture f, byte peek) {
        var packet = new EntityMetadata(
                SHULKER, List.of(EntityMetadata.Entry.byteValue(WatchableIndexUtil.SHULKER_PEEK, peek)));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    record Report(double y, boolean onGround) {}

    /** LocalPlayer#sendPosition for a client that never turns: a position past 2.0E-4 or every 20th tick. */
    private static final class Sender {
        private final JavaTickFixture f;
        private double sentY = FLOOR_Y + 1.0;
        private boolean sentOnGround = true;
        // The first tick reports the position the client stands at.
        private int reminder = 19;
        boolean withheldLift;

        Sender(JavaTickFixture f) {
            this.f = f;
        }

        void tick(Report report, String name) {
            var simulation = f.player.checkManager.getSimulationProcessor();
            PredictionResult before = simulation.getLastPrediction();
            reminder++;
            double moved = report.y - sentY;
            if (moved * moved > 2.0E-4 * 2.0E-4 || reminder >= 20) {
                f.tick(new Vec3(SHULKER_AT.x, report.y, SHULKER_AT.z), report.onGround);
                sentY = report.y;
                reminder = 0;
            } else {
                withheldLift |= moved > 0.0;
                if (report.onGround != sentOnGround) f.statusTick(report.onGround, false);
                else f.tickEnd();
            }
            sentOnGround = report.onGround;
            PredictionResult result = simulation.getLastPrediction();
            assertTrue(result != before, name + ": the tick committed no prediction");
            assertTrue(
                    !result.isExempt() && !result.hasEffectiveFlags(),
                    name + " y " + report.y + " ground " + report.onGround + ": " + describe(result));
        }
    }

    /** The client: a player without input on the floor east of a size 1 shulker attached to the wall west of it. */
    private static final class SideClient {
        private final ClientVersion version;
        private final boolean shulkerFirst;
        double x = 1.0 + (double) 0.3F;
        boolean onGround = true;
        byte raw;
        float peek;

        SideClient(ClientVersion version, boolean shulkerFirst) {
            this.version = version;
            this.shulkerFirst = shulkerFirst;
        }

        /** One client tick: the x and onGround the client reports. */
        Report tick() {
            if (shulkerFirst) shulkerTick();
            // LivingEntity#travel lands the grounded player back on the floor.
            onGround = true;
            if (shulkerFirst) return new Report(x, onGround);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                shulkerTick();
                return new Report(x, onGround);
            }
            Report report = new Report(x, onGround);
            shulkerTick();
            return report;
        }

        private void shulkerTick() {
            float old = peek;
            float target = raw * 0.01F;
            if (peek == target) return;
            peek = peek > target
                    ? CultMath.clamp(peek - 0.05F, target, 1.0F)
                    : CultMath.clamp(peek + 0.05F, 0.0F, target);
            float push = physical(peek) - physical(old);
            // Shulker#getProgressAabb's east face: 0.5 + phys past the shulker's centre.
            if (push <= 0.0F || !(x - 0.3F < 0.5 + 1.0 * 0.5 + 1.0 * (double) physical(peek) * 1.0F)) return;
            x += push;
            onGround = false;
        }

        private float physical(float amount) {
            return 0.5F - VanillaMath.sin(version, (0.5F + amount) * (float) Math.PI) * 0.5F;
        }
    }

    /** The client: a player without input on a shulker of size 1 attached to the floor. */
    private static final class Client {
        private final ClientVersion version;
        private final boolean shulkerFirst;
        double y = FLOOR_Y + 1.0;
        double deltaY = GROUNDED_Y;
        boolean onGround = true;
        byte raw;
        float peek;

        Client(ClientVersion version, boolean shulkerFirst) {
            this.version = version;
            this.shulkerFirst = shulkerFirst;
        }

        /** One client tick: the position and onGround the client reports. */
        Report tick() {
            if (shulkerFirst) shulkerTick();
            travel();
            if (shulkerFirst) return new Report(y, onGround);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // LocalPlayer#sendChanges reports after ClientLevel#tickEntities.
                shulkerTick();
                return new Report(y, onGround);
            }
            // LocalPlayer#tick reports from the player's place; the shulker moves it afterwards.
            Report report = new Report(y, onGround);
            shulkerTick();
            return report;
        }

        /** LivingEntity#travel without input: Entity#move by deltaMovement, then gravity and drag. */
        private void travel() {
            double top = top(peek);
            double movement = deltaY;
            if (y + deltaY < top) movement = top - y;
            // Entity#move skips setPos for a collided move below 1e-7.
            if (movement * movement > 1.0E-7 || deltaY * deltaY - movement * movement < 1.0E-7) y += movement;
            onGround = deltaY < 0.0 && movement != deltaY;
            if (onGround) deltaY = 0.0;
            deltaY = (deltaY - 0.08) * 0.98F;
        }

        /** Shulker#tick: updatePeekAmount and onPeekAmountChange's Entity#move(MoverType.SHULKER) upwards. */
        private void shulkerTick() {
            float old = peek;
            float target = raw * 0.01F;
            if (peek == target) return;
            peek = peek > target
                    ? CultMath.clamp(peek - 0.05F, target, 1.0F)
                    : CultMath.clamp(peek + 0.05F, 0.0F, target);
            float push = physical(peek) - physical(old);
            if (push <= 0.0F || !(y < top(peek))) return;
            y += push;
            // Entity#move: an upward move without collision leaves the player off the ground.
            onGround = false;
        }

        /** Shulker#getProgressAabb's top for a size 1 shulker on the floor. */
        private double top(float peek) {
            return 1.0 + 1.0 * (double) physical(peek) * 1.0F + FLOOR_Y;
        }

        private float physical(float amount) {
            return 0.5F - VanillaMath.sin(version, (0.5F + amount) * (float) Math.PI) * 0.5F;
        }
    }
}
