package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A rocket's add_entity, attach metadata and remove_entities through PacketEntityReplication. The client applies
 * each packet at the start of a tick, once it has answered every ping before it, and has applied it once it answers
 * the ping after it. FireworkRocketEntity#tick boosts the player at the end of each tick it holds the rocket.
 */
class JavaFireworkPacketTest {
    private static final int ROCKET = 4242;
    private static final int FIREWORK_ATTACHED_TO_TARGET = 9;
    private static final double GRAVITY = 0.08;
    private static final float YAW = 30.0F, PITCH = -20.0F;
    private static final Vec3 FROM = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 40, 8.5);
    /** Gliding south from here crosses into chunk (0, 2) in tick 1. */
    private static final Vec3 BORDER = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 40, 30.5);

    private static final Vec3 DEEP = new Vec3(8.5, -200, 8.5);
    private static final int OTHER = 777;
    private static final Vec3 CARRIED = new Vec3(0.5, -0.1, 0.8);

    /** The attachment is in flight before tick 1 and proven before tick 2. */
    @Test
    void aRocketBoostsFromTheTickItsAttachmentCanPrecede() throws Exception {
        assertExact(arrival(0, 0));
        assertNotVanilla(arrival(0, 1));
        assertExact(arrival(0, 0, 0));
        assertExact(arrival(0, 0, 1));
    }

    @Test
    void aProvenRocketBoostsEveryGlidingTick() throws Exception {
        assertExact(arrival(0, 0, 1, 1, 1));
        assertExact(arrival(0, 0, 0, 1, 1));
        assertNotVanilla(arrival(0, 0, 0, 0));
        assertNotVanilla(arrival(0, 0, 1, 0));
        assertNotVanilla(arrival(0, 0, 1, 1, 0));
        assertNotVanilla(arrival(0, 0, 1, 2));
    }

    /** The removal is in flight before tick 4 and proven before tick 5. */
    @Test
    void aRocketMayBoostUntilItsRemovalIsProven() throws Exception {
        assertNotVanilla(removal(0, 0, 1, 1, 0));
        assertExact(removal(0, 0, 1, 1, 1, 0, 0));
        assertExact(removal(0, 0, 1, 1, 1, 1, 0));
        assertNotVanilla(removal(0, 0, 1, 1, 1, 0, 1));
        assertNotVanilla(removal(0, 0, 1, 1, 1, 1, 1));
    }

    /**
     * The rocket is proven before tick 1 and FireworkRocketEntity#tick places it at the player in chunk (0, 2) in
     * tick 1. The client ticks it in tick 2 only if it has loaded that chunk.
     */
    @Test
    void aRocketStopsOnceItFollowsThePlayerIntoAChunkTheClientHasNotLoaded() throws Exception {
        assertNotVanilla(crossing(false, 0, 0, 0));
        assertExact(crossing(false, 0, 0, 1, 0));
        assertNotVanilla(crossing(true, 0, 0, 1, 0));
        assertExact(crossing(true, 0, 0, 1, 1));
    }

    /**
     * The rocket stays in chunk (0, 2) when the player is teleported back to {@link #FROM}, until a proven position
     * sync before tick 2 places it at the player, after which it ticks in tick 2.
     */
    @Test
    void aRocketLeftInAChunkTheClientHasNotLoadedStaysUntilTheServerPlacesIt() throws Exception {
        assertExact(leftBehind(-1, false, 0, 0, 0, 0, 0, 0));
        assertNotVanilla(leftBehind(2, false, 0, 0, 0, 0));
        assertExact(leftBehind(2, false, 0, 0, 0, 1));
    }

    /** ClientPacketListener#handleTeleportEntity moves the rocket by a relative X/Y/Z from where it stopped. */
    @Test
    void aRelativeTeleportLeavesTheRocketWhereItStopped() throws Exception {
        assertExact(leftBehind(2, true, 0, 0, 0, 0, 0, 0));
    }

    /**
     * The server removes the proven rocket before tick 3 and adds it again under the same id and attach metadata,
     * in the same flush or a ping apart, and both are proven before tick 3. The old rocket boosts after tick 2 and
     * the new one from tick 3.
     */
    @Test
    void aRocketAddedAgainUnderItsIdBoostsOnceProven() throws Exception {
        for (boolean pingApart : new boolean[] {false, true}) {
            assertExact(readded(pingApart, 0, 1, 1, 1, 1, 1, 1));
            assertNotVanilla(readded(pingApart, 0, 1, 1, 1, 0));
        }
    }

    /** Entity#checkBelowWorld discards the proven rocket in tick 1, which still boosts. */
    @Test
    void aRocketBelowTheWorldBoostsOnceMore() throws Exception {
        assertNotVanilla(deep(0, 0, 0));
        assertExact(deep(0, 0, 1, 0));
        assertNotVanilla(deep(0, 0, 1, 1));
    }

    /** FireworkRocketEntity#tick keeps boosting the player it resolved after a target naming another entity. */
    @Test
    void aRocketHoldingThePlayerKeepsBoostingItWhenRetargeted() throws Exception {
        assertExact(retargeted(0, 0, 1, 1, 1, 1));
        assertNotVanilla(retargeted(0, 0, 1, 1, 0));
        assertNotVanilla(retargeted(0, 0, 1, 1, 1, 0));
    }

    /** The counts after each tick follow the pongs that precede the tick's move. */
    @Test
    void theRocketCountsFollowThePongOrder() throws Exception {
        try (var f = gliding(FROM)) {
            Vec3[] glide = {FROM, CARRIED};
            spawnRocket(f);
            int edge = f.player.lastTransactionSent.get();
            int attachedAt = f.player.sendTrailingTransaction();
            tick(f, glide, 0);
            assertCounts(f, 0, 0);

            f.pongThrough(edge);
            tick(f, glide, 0);
            assertCounts(f, 1, 0);

            f.pongThrough(attachedAt);
            tick(f, glide, 0);
            assertCounts(f, 1, 1);

            int before = f.player.sendTrailingTransaction();
            removeRocket(f);
            int removedAt = f.player.lastTransactionSent.get();
            tick(f, glide, 1);
            assertCounts(f, 1, 1);

            f.pongThrough(before);
            tick(f, glide, 1);
            assertCounts(f, 1, 0);

            f.pongThrough(removedAt);
            tick(f, glide, 1);
            assertCounts(f, 0, 0);
        }
    }

    /**
     * Spawns before tick 1, where the client has answered every ping before the attachment, proves the attachment
     * before tick 2 and plays a tick from each boost count.
     */
    private static PredictionResult arrival(int... boosts) throws Exception {
        return play(boosts, false);
    }

    /** As {@link #arrival}, then sends the removal before tick 4 and proves it before tick 5. */
    private static PredictionResult removal(int... boosts) throws Exception {
        return play(boosts, true);
    }

    /** Spawns and proves the rocket after tick 0 and plays a tick from each boost count. */
    private static PredictionResult crossing(boolean loaded, int... boosts) throws Exception {
        try (var f = gliding(BORDER)) {
            if (loaded) f.player.compensatedWorld.ensureValidationChunkLoaded(0, 2);
            return proven(f, BORDER, boosts);
        }
    }

    /**
     * As {@link #crossing} with the rocket left in chunk (0, 2), then teleports the player to {@link #FROM}, sends a
     * proven position sync placing the rocket at the player, or a relative teleport_entity by nothing, before tick
     * {@code syncedAt} and plays a tick from each boost count.
     */
    private static PredictionResult leftBehind(int syncedAt, boolean relative, int... boosts) throws Exception {
        try (var f = gliding(BORDER)) {
            assertExact(proven(f, BORDER, new int[] {0, 0, 1, 0}));
            f.teleport(f.player.sendTrailingTransaction(), FROM, new RelativeFlag(0), YAW, PITCH, YAW, PITCH);
            Vec3[] glide = {FROM, Vec3.ZERO};
            PredictionResult result = null;
            for (int tick = 0; tick < boosts.length; tick++) {
                if (tick == syncedAt && relative) teleportRocketBy(f, Vec3.ZERO);
                if (tick == syncedAt && !relative) syncRocket(f, glide[0]);
                if (result != null) assertExact(result);
                result = tick(f, glide, boosts[tick]);
            }
            return result;
        }
    }

    /** As {@link #arrival}, with the rocket removed and added again before tick 3, a ping apart if asked. */
    private static PredictionResult readded(boolean pingApart, int... boosts) throws Exception {
        try (var f = gliding(FROM)) {
            Vec3[] glide = {FROM, CARRIED};
            PredictionResult result = null;
            for (int tick = 0; tick < boosts.length; tick++) {
                if (tick == 0) {
                    spawnRocket(f);
                    f.pongThrough(f.player.sendTrailingTransaction());
                }
                if (tick == 3) {
                    removeRocket(f);
                    if (pingApart) f.player.sendTrailingTransaction();
                    spawnRocket(f);
                    f.pongThrough(f.player.sendTrailingTransaction());
                }
                if (result != null) assertExact(result);
                result = tick(f, glide, boosts[tick]);
            }
            return result;
        }
    }

    /** As {@link #crossing}, below the level. */
    private static PredictionResult deep(int... boosts) throws Exception {
        try (var f = gliding(DEEP)) {
            return proven(f, DEEP, boosts);
        }
    }

    private static PredictionResult proven(JavaTickFixture f, Vec3 from, int[] boosts) {
        Vec3[] glide = {from, CARRIED};
        PredictionResult result = null;
        for (int tick = 0; tick < boosts.length; tick++) {
            if (tick == 1) {
                spawnRocket(f);
                f.pongThrough(f.player.sendTrailingTransaction());
            }
            if (result != null) assertExact(result);
            result = tick(f, glide, boosts[tick]);
        }
        return result;
    }

    /** As {@link #arrival}, then targets another entity before tick 3 and proves it before tick 4. */
    private static PredictionResult retargeted(int... boosts) throws Exception {
        try (var f = gliding(FROM)) {
            Vec3[] glide = {FROM, CARRIED};
            PredictionResult result = null;
            for (int tick = 0; tick < boosts.length; tick++) {
                if (tick == 1) {
                    f.pongThrough(f.player.lastTransactionSent.get());
                    spawnRocket(f);
                    f.pongThrough(f.player.lastTransactionSent.get());
                }
                if (tick == 2) f.pongThrough(f.player.sendTrailingTransaction());
                if (tick == 3) attach(f, OptionalInt.of(OTHER));
                if (tick == 4) f.pongThrough(f.player.sendTrailingTransaction());
                if (result != null) assertExact(result);
                result = tick(f, glide, boosts[tick]);
            }
            return result;
        }
    }

    private static PredictionResult play(int[] boosts, boolean removes) throws Exception {
        try (var f = gliding(FROM)) {
            Vec3[] glide = {FROM, CARRIED};
            PredictionResult result = null;
            for (int tick = 0; tick < boosts.length; tick++) {
                if (tick == 1 || removes && tick == 4) f.pongThrough(f.player.lastTransactionSent.get());
                if (tick == 1) {
                    spawnRocket(f);
                    f.pongThrough(f.player.lastTransactionSent.get());
                }
                if (tick == 2) f.pongThrough(f.player.sendTrailingTransaction());
                if (removes && tick == 4) removeRocket(f);
                if (removes && tick == 5) f.pongThrough(f.player.lastTransactionSent.get());
                if (result != null) assertExact(result);
                result = tick(f, glide, boosts[tick]);
            }
            return result;
        }
    }

    /** One client tick from the stored velocity after {@code boosts} rockets; {@code glide} holds where and how. */
    private static PredictionResult tick(JavaTickFixture f, Vec3[] glide, int boosts) {
        Vec3 start = glide[1];
        for (int i = 0; i < boosts; i++) start = boost(f, start);
        // LivingEntity#aiStep zeroes a player's horizontal velocity under 0.003 and its vertical one under 0.003.
        boolean still = start.x * start.x + start.z * start.z < 9.0E-6;
        start = new Vec3(still ? 0 : start.x, Math.abs(start.y) < 0.003 ? 0 : start.y, still ? 0 : start.z);
        glide[1] = ElytraTransform.getElytraMovement(f.player, start, look(f), PITCH, GRAVITY);
        glide[0] = glide[0].add(glide[1]);
        return f.tick(glide[0], false);
    }

    /** The add_entity and its attach metadata. */
    private static void spawnRocket(JavaTickFixture f) {
        var add = new ClientboundAddEntity(
                ROCKET, "minecraft:firework_rocket", new Vec3d(f.player.x, f.player.y, f.player.z), 0, 0, 0);
        var addEvent =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(addEvent, f.player, add);
        addEvent.tasksAfter().forEach(Runnable::run);
        attach(f, OptionalInt.of(f.player.entityID));
    }

    /** The rocket's DATA_ATTACHED_TO_TARGET. */
    private static void attach(JavaTickFixture f, OptionalInt target) {
        var entry = new EntityMetadata.Entry(FIREWORK_ATTACHED_TO_TARGET, target, ByteBuffer.wrap(new byte[] {1}));
        var data = new EntityMetadata(ROCKET, List.of(entry));
        var dataEvent = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), data, false);
        f.player.packetEntityReplication.onSetEntityData(dataEvent, f.player, data);
        dataEvent.tasksAfter().forEach(Runnable::run);
    }

    /** An entity_position_sync placing the rocket at {@code at}, and the pong after it. */
    private static void syncRocket(JavaTickFixture f, Vec3 at) {
        var path = new PositionPath.Linear(new Vec3d(at.x, at.y, at.z));
        var packet = new ClientboundEntityPositionSync(ROCKET, path, 0, 0, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, packet, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.sendTrailingTransaction());
    }

    /** A teleport_entity moving the rocket by {@code by} with relative X/Y/Z, and the pong after it. */
    private static void teleportRocketBy(JavaTickFixture f, Vec3 by) {
        var packet = new ClientboundTeleportEntity(
                ROCKET,
                new Vec3d(by.x, by.y, by.z),
                Vec3d.ZERO,
                0,
                0,
                Set.of(Relative.X, Relative.Y, Relative.Z),
                false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.sendTrailingTransaction());
    }

    /** The remove_entities and its proof. */
    private static void removeRocket(JavaTickFixture f) {
        var packet = new ClientboundRemoveEntities(List.of(ROCKET));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static JavaTickFixture gliding(Vec3 from) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.isGliding = true;
        f.player.refreshPlayerPose();
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        f.place(from, CARRIED);
        return f;
    }

    private static Vector3dm look(JavaTickFixture f) {
        return ReachUtils.getLook(f.player, YAW, PITCH);
    }

    private static Vec3 boost(JavaTickFixture f, Vec3 velocity) {
        Vector3dm look = look(f);
        return velocity.add(
                look.getX() * 0.1 + (look.getX() * 1.5 - velocity.x) * 0.5,
                look.getY() * 0.1 + (look.getY() * 1.5 - velocity.y) * 0.5,
                look.getZ() * 0.1 + (look.getZ() * 1.5 - velocity.z) * 0.5);
    }

    private static void assertCounts(JavaTickFixture f, int possible, int certain) {
        assertEquals(possible, f.player.compensatedFireworks.getMaxFireworksAppliedPossible(), "possible");
        assertEquals(certain, f.player.compensatedFireworks.getCertainFireworksApplied(), "certain");
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static void assertNotVanilla(PredictionResult result) {
        assertFalse(result.isExempt(), describe(result));
        assertTrue(result.getOffset() > 0.01, describe(result));
        assertFalse(result.getFlags().isEmpty(), describe(result));
    }
}
