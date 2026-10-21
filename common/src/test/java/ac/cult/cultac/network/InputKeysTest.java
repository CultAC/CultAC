package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#sendChanges sends the keys KeyboardInput#tick read before the tick's position, and on the ground they add
 * exactly their input before Entity#move. Player#maybeBackOffFromEdge shortens that move only for a shift key, so a
 * Safe Walk client holding back without shift moves less than its keys, or not at all.
 */
class InputKeysTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput BACK =
            new ServerboundPlayerInput(false, true, false, false, false, false, false);
    private static final ServerboundPlayerInput BACK_LEFT =
            new ServerboundPlayerInput(false, true, true, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_BACK =
            new ServerboundPlayerInput(false, true, false, false, false, true, false);
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    // JavaTickFixture's floor ends at z = -8; a box centred here overhangs it by 0.299.
    private static final double EDGE_Z = -8.299;
    private static final double WALK = (double) 0.98F * (double) 0.1F;
    private static final double SPRINT = (double) 0.98F * (double) 0.13F;
    private static final double FRICTION = 0.6F * 0.91F;

    @Test
    void safeWalkStandingStillAtTheEdgeWithoutShiftIsFlaggedAndSetBack() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, EDGE_Z), GROUNDED);
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, EDGE_Z), true));

            // Without shift the client walks off the edge; Safe Walk backs the whole move off and sends no position.
            f.input(BACK);
            for (int tick = 0; tick < 12 && !f.player.getSetbackTeleportUtil().isPendingSetback(); tick++) {
                f.tickEnd();
            }

            assertTrue(keyFlags(f) > 0, "InputKeys did not flag");
            assertTrue(simulationFlags(f) > 0, "Simulation did not flag");
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), "the stall was not set back");
        }
    }

    @Test
    void safeWalkShorteningTheMoveWithoutShiftIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            // The full 0.098 back crosses the edge; Player#maybeBackOffFromEdge's 0.05 step stops it 0.048 back.
            double z = -8.23;
            f.place(new Vec3(0.5, FLOOR_Y, z), GROUNDED);
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));

            f.input(BACK);
            PredictionResult backedOff = f.tick(new Vec3(0.5, FLOOR_Y, z + (-WALK + 0.05)), true);

            assertTrue(backedOff.hasFlag(InputKeys.class), describe(backedOff));
            assertEquals(1, keyFlags(f));
            assertEquals(1, simulationFlags(f));
        }
    }

    @Test
    void movingWithoutEverSendingThePlayerInputIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            // The keys stay at Input.EMPTY until the first PLAYER_INPUT, so withholding it cannot hide the input.
            double z = -6;
            f.place(new Vec3(0.5, FLOOR_Y, z), GROUNDED);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));

            PredictionResult moved = f.tick(new Vec3(0.5, FLOOR_Y, z - WALK), true);

            assertTrue(moved.hasFlag(InputKeys.class), describe(moved));
        }
    }

    @Test
    void sneakingBackOffAtTheEdgeIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, EDGE_Z), GROUNDED);
            f.input(SNEAK);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, EDGE_Z), true));

            f.input(SNEAK_BACK);
            for (int tick = 0; tick < 8; tick++) f.tickEnd();
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, EDGE_Z), true));

            assertNoFlags(f);
        }
    }

    @Test
    void walkingStrafingAndReleasingTheKeysIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            double z = 4.5;
            f.place(new Vec3(0.5, FLOOR_Y, z), GROUNDED);
            f.input(BACK);
            double velocity = 0.0;
            for (int tick = 0; tick < 5; tick++) {
                z += velocity - WALK;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity - WALK) * FRICTION;
            }

            f.input(NONE);
            while (Math.abs(velocity) >= 0.003) {
                z += velocity;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity *= FRICTION;
            }
            // LivingEntity#aiStep zeroes the delta under 0.003; the client then sends no position.
            for (int tick = 0; tick < 5; tick++) f.tickEnd();

            f.input(BACK_LEFT);
            Vec3 diagonal = VelocityTransformer.getExactControllerModifiedInputStatic(new Vec3(1, 0, -1), 1.0F)
                    .scale(0.1F);
            assertExact(f.tick(new Vec3(0.5 + diagonal.x, FLOOR_Y, z + diagonal.z), true));

            assertNoFlags(f);
        }
    }

    @Test
    void walkingIntoAWallIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, -1), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            // The box's back face touches the wall at z = 0.
            f.place(new Vec3(0.5, FLOOR_Y, 0.3), GROUNDED);
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.3), true));

            f.input(BACK);
            for (int tick = 0; tick < 5; tick++) f.tickEnd();
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.3), true));

            assertNoFlags(f);
        }
    }

    @Test
    void walkingAlongAWallsEndWhilePushedIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, -1), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            // The box's side touches the wall, whose end lies 0.01 into the box's z range.
            Vec3 start = new Vec3(1.0 - 0.3F, FLOOR_Y, 0.29);
            f.place(start, GROUNDED);
            // A zombie ticking before the player pushes it by 0.05 / sqrt(0.5) * 0.5 along -z this tick.
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start.add(0, 0, 0.5), 0, 0, 0);
            f.player.compensatedEntities.entityMap.get(100).setClientTickOrder(-1L);
            float yaw = 310.0F;
            f.player.xRot = yaw;
            var keys = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            f.input(keys);
            Vec3 input = ClientMoveInput.of(f, keys, 1.0F, 0.1F, yaw);
            // Direction#axisStepOrder moves the longer x first, into the wall; the shorter z then clears its end.
            double z = input.z - 0.05 * 0.5 / Math.sqrt(0.5);
            assertTrue(Math.abs(input.x) > Math.abs(z) && z > 0.01);
            assertExact(f.tick(start.add(0, 0, z), true));

            assertNoFlags(f);
        }
    }

    @Test
    void knockedUpAgainstASlabUnderALedgeIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int slab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "bottom");
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), slab);
            // The cake's face, 1/16 past the slab's, stops the box once the step has lifted it onto the slab.
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y + 1, 0),
                    registry.block("minecraft:cake").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            // The box's side touches the slab.
            double x = 1.0 - 0.3F;
            f.place(new Vec3(x, FLOOR_Y, 0.5), GROUNDED);
            // Only a respawn writes the player's lastOnGround.
            f.player.lastOnGround = false;
            var keys = new ServerboundPlayerInput(true, false, true, false, false, false, false);
            f.input(keys);
            // Without the knockback the keys alone step to the same height; its z tells the two apart.
            f.pongThrough(f.velocity(new Vec3(0, 0.4, 0.2)));
            Vec3 input = ClientMoveInput.of(f, keys, 1.0F, 0.1F, 0.0F);
            assertExact(f.tick(new Vec3(1.0625 - 0.3F, FLOOR_Y + 0.5, 0.5 + 0.2 + input.z), false));

            assertNoFlags(f);
        }
    }

    @Test
    void sprintingIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, -4.5), GROUNDED);
            f.player.isSprinting = true;
            f.player.sprintState.onClientTick(true);
            f.input(SPRINT_FORWARD);
            double z = -4.5;
            double velocity = 0.0;
            for (int tick = 0; tick < 5; tick++) {
                z += velocity + SPRINT;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity + SPRINT) * FRICTION;
            }

            assertNoFlags(f);
        }
    }

    @Test
    void walkingSlowedByTheServersPowderSnowModifierIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            // LivingEntity#aiStep runs tryAddFrost only on the server, so the client walks at the speed the server's
            // minecraft:powder_snow modifier leaves after leaving the snow.
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot(
                            "minecraft:movement_speed",
                            0.1F,
                            List.of(new AttributeModifier(
                                    "minecraft:powder_snow", -0.02D, AttributeModifier.Operation.ADD_VALUE)))));
            double slowed = (double) 0.98F * (double) (float) (0.1F - 0.02D);
            f.input(BACK);
            double z = 4.5;
            double velocity = 0.0;
            for (int tick = 0; tick < 3; tick++) {
                z += velocity - slowed;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity - slowed) * FRICTION;
            }

            assertNoFlags(f);
        }
    }

    @Test
    void walkingAt1214IgnoresTheServersPowderSnowModifier() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            // Before 1.21.5 LivingEntity#aiStep runs removeFrost on the client, which drops the server's modifier.
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot(
                            "minecraft:movement_speed",
                            0.1F,
                            List.of(new AttributeModifier(
                                    "minecraft:powder_snow", -0.02D, AttributeModifier.Operation.ADD_VALUE)))));
            f.input(BACK);
            double z = 4.5;
            double velocity = 0.0;
            for (int tick = 0; tick < 3; tick++) {
                z += velocity - WALK;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity - WALK) * FRICTION;
            }

            assertNoFlags(f);
        }
    }

    @Test
    void walkingWhileUsingAnItemIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
            // LocalPlayer#modifyInput scales the keys by 0.98F, then by the 0.2F of an item in use.
            double slowed = (double) (0.98F * 0.2F) * (double) 0.1F;
            f.input(BACK);
            double z = 4.5;
            double velocity = 0.0;
            for (int tick = 0; tick < 5; tick++) {
                z += velocity - slowed;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity - slowed) * FRICTION;
            }

            assertNoFlags(f);
        }
    }

    @Test
    void walkingSlowedByAnItemUseThatMayHaveStartedIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            f.input(BACK);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 4.5 - WALK), true));
            double velocity = -WALK * FRICTION;

            // Minecraft#tick runs handleKeybinds, which starts the use, before LocalPlayer#aiStep reads the keys, so
            // LocalPlayer#modifyInput already scales this tick by 0.2F. A possibly refused start leaves it uncertain.
            f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), false);
            double slowed = (double) (0.98F * 0.2F) * (double) 0.1F;
            double z = 4.5 - WALK;
            for (int tick = 0; tick < 3; tick++) {
                z += velocity - slowed;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity - slowed) * FRICTION;
            }

            assertNoFlags(f);
        }
    }

    @Test
    void walkingOnTheTickAfterAGlideEndsIsSlowedLikeCrawling() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            f.input(BACK);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 4.5 - WALK), true));
            double velocity = -WALK * FRICTION;

            // Player#updatePlayerPose runs after LocalPlayer#aiStep, so the tick after the glide ends still starts in
            // the FALL_FLYING pose. LivingEntity#isVisuallySwimming counts that pose without fall flying, which makes
            // LocalPlayer#isMovingSlowly scale the keys by the sneaking speed.
            f.player.pose = Pose.FALL_FLYING;
            double slowed = (double) (0.98F * 0.3F) * (double) 0.1F;
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, 4.5 - WALK + velocity - slowed), true);

            assertExact(result);
            assertNoFlags(f);
        }
    }

    @Test
    void handFlagsProvenBeforeTheTickAfterAGlideKeepTheFallFlyingPose() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            f.input(BACK);

            // The glide's last tick ended in the FALL_FLYING pose; the server's shared flags then stop the glide and
            // its living-entity flags arrive, both read before the next tick. LivingEntity#onSyncedDataUpdated only
            // swaps the used item for DATA_LIVING_ENTITY_FLAGS, so the pose stays until Player#updatePlayerPose.
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            selfMetadata(f, WatchableIndexUtil.ENTITY_SHARED_FLAGS, (byte) 0);
            selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 2);
            double slowed = (double) (0.98F * 0.3F) * (double) 0.1F;
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, 4.5 - slowed), true);

            assertFalse(result.hasEffectiveFlags(), result.getFlags().toString());
            assertExact(result);
            assertNoFlags(f);
            assertEquals(Pose.STANDING, f.player.pose);
        }
    }

    private static void selfMetadata(JavaTickFixture f, int index, byte value) {
        var packet = new EntityMetadata(
                f.player.entityID, List.of(new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static double keyFlags(JavaTickFixture f) {
        return f.player.checkManager.getListener(InputKeys.class).violations;
    }

    private static double simulationFlags(JavaTickFixture f) {
        return f.player.checkManager.getCheck(OffsetHandler.class).violations;
    }

    private static void assertNoFlags(JavaTickFixture f) {
        assertEquals(0, keyFlags(f));
        assertEquals(0, simulationFlags(f));
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
