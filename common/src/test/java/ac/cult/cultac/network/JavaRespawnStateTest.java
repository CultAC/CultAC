package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ClientPacketListener#handleRespawn: what the new LocalPlayer takes from the old one. */
class JavaRespawnStateTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final byte KEEP_NOTHING = 0;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 HIGH = new Vec3(0.5, FLOOR_Y + 10, 0.5);

    /**
     * From 1.21.6 KEEP_ENTITY_DATA hands the new player the old getLastSentInput(), and
     * LocalPlayer#tick sends ServerboundPlayerInputPacket only when the keys differ: a shift held
     * through the respawn is never resent.
     */
    @Test
    void keptEntityDataKeepsTheLastSentShiftFrom1216() throws Exception {
        for (ClientVersion version : List.of(ClientVersion.V_1_21_6, ClientVersion.V_26_2, ClientVersion.V_26_3)) {
            try (var f = sneakingFixture(version)) {
                respawn(f, ClientboundRespawn.KEEP_ENTITY_DATA);
                assertTrue(f.player.isSneaking, version.toString());
            }
        }
    }

    /** Before 1.21.6 the new player starts lastSentInput at Input.EMPTY and resends a held shift. */
    @Test
    void keptEntityDataBefore1216ResendsTheShift() throws Exception {
        try (var f = sneakingFixture(ClientVersion.V_1_21_5)) {
            respawn(f, ClientboundRespawn.KEEP_ENTITY_DATA);
            assertFalse(f.player.isSneaking);
        }
    }

    @Test
    void respawnWithoutKeptEntityDataResendsTheShift() throws Exception {
        try (var f = sneakingFixture(ClientVersion.V_26_3)) {
            respawn(f, KEEP_NOTHING);
            assertFalse(f.player.isSneaking);
        }
    }

    /** The held shift keeps the new player sneaking: it moves at sneaking speed, not walking speed. */
    @Test
    void aShiftHeldThroughAKeptRespawnStillSneaks() throws Exception {
        try (var f = sneakingFixture(ClientVersion.V_26_3)) {
            respawn(f, ClientboundRespawn.KEEP_ENTITY_DATA);
            f.place(START, Vec3.ZERO);
            assertTrue(exact(f.tick(START, true)));
            // The sneaking pose: Player#getDefaultDimensions(CROUCHING) is 1.5 high.
            assertEquals(1.5, f.player.boundingBox.maxY - f.player.boundingBox.minY, 1.0E-9);
        }
    }

    /** KEEP_ENTITY_DATA assigns the old non-default entity data, the fall-flying shared flag included. */
    @Test
    void keptEntityDataKeepsTheGlideFlag() throws Exception {
        for (byte keep : new byte[] {KEEP_NOTHING, ClientboundRespawn.KEEP_ENTITY_DATA}) {
            try (var f = new JavaTickFixture()) {
                f.player.isGliding = true;
                respawn(f, keep);
                assertEquals(keep == ClientboundRespawn.KEEP_ENTITY_DATA, f.player.isGliding, "keep=" + keep);
            }
        }
    }

    /** Without KEEP_ATTRIBUTE_MODIFIERS, AttributeMap#assignBaseValues leaves the new player only the bases. */
    @Test
    void respawnWithoutKeptModifiersFallsAtTheGravityBase() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:gravity", 0.08, 1.92);
            respawn(f, KEEP_NOTHING);
            assertEquals(0.08, f.player.compensatedEntities.getSelf().gravity, 1.0E-7);

            fall(f, 0.08);
        }
    }

    @Test
    void respawnKeepingModifiersKeepsTheGravityTotal() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:gravity", 0.08, 1.92);
            respawn(f, ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS);
            assertEquals(1.0, f.player.compensatedEntities.getSelf().gravity, 1.0E-7);

            fall(f, 1.0);
        }
    }

    @Test
    void respawnWithoutKeptModifiersRestoresEveryMovementBase() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:movement_speed", 0.12, 0.1);
            attribute(f, "minecraft:scale", 1.5, 0.5);
            attribute(f, "minecraft:step_height", 0.6, 1.0);
            attribute(f, "minecraft:jump_strength", 0.5, 0.5);
            attribute(f, "minecraft:bounciness", 0.25, 0.5);
            attribute(f, "minecraft:friction_modifier", 1.0, 1.0);
            attribute(f, "minecraft:air_drag_modifier", 1.0, -0.5);
            respawn(f, KEEP_NOTHING);

            var self = f.player.compensatedEntities.getSelf();
            assertEquals(0.12, self.playerSpeed, 1.0E-7);
            assertEquals(1.5, self.scale, 1.0E-7);
            assertEquals(0.6, self.stepHeightAttribute.doubleValue(), 1.0E-7);
            assertEquals(0.5, self.jumpStrength, 1.0E-7);
            assertEquals(0.25, self.bounciness, 1.0E-7);
            assertEquals(1.0, self.frictionModifier, 1.0E-7);
            assertEquals(1.0, self.airDragModifier, 1.0E-7);
            // The new player's box is the kept base scale: 0.6 * 1.5 wide.
            assertEquals(0.9, f.player.boundingBox.maxX - f.player.boundingBox.minX, 1.0E-6);
        }
    }

    /** Attributes the server never sent keep their vanilla defaults, before 26.2 the newer ones too. */
    @Test
    void respawnLeavesUnsentAndUnsupportedAttributesAtTheirDefaults() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            attribute(f, "minecraft:bounciness", 0.25, 0.5);
            respawn(f, KEEP_NOTHING);

            var self = f.player.compensatedEntities.getSelf();
            assertEquals(0.0, self.bounciness);
            assertEquals(0.1F, self.playerSpeed, 1.0E-9);
            assertEquals(0.6, self.stepHeightAttribute, 1.0E-9);
        }
    }

    private static JavaTickFixture sneakingFixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(START, Vec3.ZERO);
        f.input(SNEAK);
        assertTrue(f.player.isSneaking);
        return f;
    }

    /** A respawn with no following attribute or input packet, after which the client has loaded. */
    private static void respawn(JavaTickFixture f, byte dataToKeep) {
        f.fixture.respawn(dataToKeep);
        f.player.getSetbackTeleportUtil().hasFullyLoaded = true;
        f.player.packetStateData.playerLoadedIntoLevel = true;
    }

    /** A still player falling from rest: LivingEntity#travelInAir moves it by -gravity * 0.98F. */
    private static void fall(JavaTickFixture f, double gravity) {
        f.place(HIGH, Vec3.ZERO);
        f.input(NONE);
        assertTrue(exact(f.tick(HIGH, false)));
        PredictionResult fall = f.tick(HIGH.add(0, -gravity * 0.98F, 0), false);
        assertTrue(exact(fall), describe(fall));
    }

    private static void attribute(JavaTickFixture f, String name, double base, double added) {
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot(
                        name,
                        base,
                        List.of(new AttributeModifier(
                                "test:modifier", added, AttributeModifier.Operation.ADD_VALUE)))));
    }
}
