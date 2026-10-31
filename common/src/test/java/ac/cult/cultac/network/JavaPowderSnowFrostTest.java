package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaPowderSnowFrostTest {
    private static final double DRAG = 0.6F * 0.91F;
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void respawnKeepsFrozenMetadataOnlyWithEntityData(boolean keep) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.powderSnowFrozenTicks = 140;
            f.fixture.respawn(keep ? ClientboundRespawn.KEEP_ENTITY_DATA : (byte) 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(keep ? 140 : 0, f.player.powderSnowFrozenTicks);
        }
    }

    @Test
    void frostDoesNotAllowUnslowedSprinting() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, GROUNDED);
            f.player.powderSnowFrozenTicks = 70;
            f.tickEnd();
            f.player.isSprinting = true;
            f.player.sprintState.onClientTick(true);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, true));
            float speed = (float) ((0.1F + (double) (-0.05F * 0.5F))
                    * (1.0D + CompensatedEntities.SPRINTING_SPEED_MODIFIER.amount()));
            double movement = (double) 0.98F * speed;
            position = position.add(0, 0, movement);
            var legal = f.tick(position, true);
            assertTrue(exact(legal), describe(legal));
            double cheat = movement * DRAG
                    + (double) 0.98F * (float) (0.1F * (1.0D + CompensatedEntities.SPRINTING_SPEED_MODIFIER.amount()));
            var illegal = f.tick(position.add(0, 0, cheat), true);
            assertFalse(exact(illegal), describe(illegal));
            assertTrue(illegal.hasEffectiveFlags(), describe(illegal));
        }
    }

    @Test
    void theClientRemovesFrostWhenItsLegacySupportBlockIsAir() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 5, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.powderSnowFrozenTicks = 140;
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot(
                            "minecraft:movement_speed",
                            0.1F,
                            List.of(new AttributeModifier(
                                    "minecraft:powder_snow", -0.02D, AttributeModifier.Operation.ADD_VALUE)))));
            var result = f.tick(position, false);
            assertTrue(exact(result), describe(result));
            assertEquals((double) 0.1F, f.player.compensatedEntities.getSelf().playerSpeed);
        }
    }

    @Test
    void aFrozenMetadataProofIsAppliedAfterAnIdleTicksTravel() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), GROUNDED);
            var packet = new EntityMetadata(
                    f.player.entityID,
                    List.of(new EntityMetadata.Entry(
                            WatchableIndexUtil.ENTITY_TICKS_FROZEN, 140, ByteBuffer.allocate(0))));
            @SuppressWarnings("unchecked")
            var type = (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                    .filter(t -> t.recordClass() == EntityMetadata.class)
                    .findFirst()
                    .orElseThrow();
            var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, type, packet, false);
            new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
            assertFalse(event.getWritesAfterSend().isEmpty(), "the frozen-count proof must follow its metadata");
            event.tasksAfter().forEach(Runnable::run);
            int proof = f.player.lastTransactionSent.get();
            f.tickEnd();
            assertEquals(0, f.player.powderSnowFrozenTicks);
            assertEquals(0.1F, f.player.compensatedEntities.getSelf().playerSpeed);
            f.pongThrough(proof);
            assertEquals(140, f.player.powderSnowFrozenTicks);
            assertEquals(0.1F, f.player.compensatedEntities.getSelf().playerSpeed);
            f.tickEnd();
            assertEquals((double) (0.1F - 0.05F), f.player.compensatedEntities.getSelf().playerSpeed);
            f.input(FORWARD);
            var result = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + (double) 0.98F * (0.1F - 0.05F)), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * Through 1.21.4 LivingEntity#aiStep runs removeFrost/tryAddFrost after travel on both sides.
     * Metadata changes the count, not the modifier travel is already using. Since 1.21.5 only
     * the server replaces frost, and the client keeps the last attribute packet's modifier.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void frozenCountChangesTheFollowingTravelRatherThanTheCurrentTravel(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), GROUNDED);
            f.input(FORWARD);
            double z = 0.5;
            double velocity = 0;
            boolean local = version.isOlderThan(ClientVersion.V_1_21_5);
            float speed = 0.1F;
            for (int count : new int[] {1, 2, 70, 140, 200, 0, 0}) {
                // Acknowledged DATA_TICKS_FROZEN is server-owned; clients do not increment it themselves.
                f.player.powderSnowFrozenTicks = count;
                double movement = velocity + (double) 0.98F * speed;
                z += movement;
                var result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), release + " count=" + count + " " + describe(result));
                velocity = movement * DRAG;
                speed = local ? (float) (0.1F + -0.05F * (Math.min(count, 140) / 140F)) : 0.1F;
                assertEquals(speed, (float) f.player.compensatedEntities.getSelf().playerSpeed);
            }
        }
    }

    /** A server attribute packet is visible to the first travel, before the client's post-travel replacement. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void serverFrostIsReplacedOnlyAfterTravelAndKeepsTheOtherAttributeOperations(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), GROUNDED);
            f.player.powderSnowFrozenTicks = 70;
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot(
                            "minecraft:movement_speed",
                            0.1F,
                            List.of(
                                    new AttributeModifier(
                                            "minecraft:powder_snow", -0.02D, AttributeModifier.Operation.ADD_VALUE),
                                    new AttributeModifier(
                                            "test:base", 0.5D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                                    new AttributeModifier(
                                            "test:total", 0.2D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)))));
            f.input(FORWARD);
            double z = 0.5;
            double velocity = 0;
            for (int tick = 0; tick < 3; tick++) {
                double frost = tick == 0 || version.isNewerThanOrEquals(ClientVersion.V_1_21_5)
                        ? -0.02D
                        : (double) (-0.05F * 0.5F);
                double base = 0.1F + frost;
                float speed = (float) ((base + base * 0.5D) * 1.2D);
                double movement = velocity + (double) 0.98F * speed;
                z += movement;
                var result = f.tick(new Vec3(0.5, FLOOR_Y, z), true);
                assertTrue(exact(result), release + " tick=" + tick + " " + describe(result));
                velocity = movement * DRAG;
            }
        }
    }
}
