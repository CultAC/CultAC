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
import ac.cult.blocksim.entity.EntityTypes;
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
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
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
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_SPRINT =
            new ServerboundPlayerInput(false, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SNEAK_SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SNEAK_SPRINT_BACK =
            new ServerboundPlayerInput(false, true, false, false, false, true, true);
    // Speed II raises the movement speed to 0.14, which a stone or slab block leaves unchanged on the ground.
    private static final double WALKING_WITH_SPEED = (double) 0.98F * (float) (0.1F * 1.4D);
    private static final double SNEAKING_WITH_SPEED = (double) (0.98F * 0.3F) * (float) (0.1F * 1.4D);
    private static final double SNEAK_STEP = (double) (0.98F * 0.3F) * (double) 0.1F;
    private static final Vec3 SHULKER_OVER_THE_HEAD = new Vec3(0.5, FLOOR_Y + 1, -2.5);
    private static final Vec3 SHULKER_CLEAR_OF_THE_PLAYER = new Vec3(5.5, FLOOR_Y + 1, -2.5);
    private static final ServerboundPlayerInput SPRINT_BACK =
            new ServerboundPlayerInput(false, true, false, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_BACK_LEFT =
            new ServerboundPlayerInput(false, true, true, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, false, true);
    private static final float SPRINTING_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final double CAPTURE_SHIFT_X = 271;
    private static final double CAPTURE_SHIFT_Z = -272;
    private static final double[][] CAPTURED_BACKWARD_RUN = {
        {-270.7928108610354, 273.6888251723098, 206.0403},
        {-270.88745851906776, 273.882823763336, 206.0403},
        {-270.9950597641237, 274.1032167028535, 206.0403},
        {-271.10973366851607, 274.3380209583212, 206.0403},
        {-271.2284009037546, 274.5806293840209, 206.10739},
        {-271.34978533353376, 274.8272342870383, 206.37576},
        {-271.47331990937334, 275.0756883468893, 206.71123},
        {-271.60041600946215, 275.3239191063504, 207.91896},
    };
    private static final double[][] CAPTURED_SIDEWAYS_RUN = {
        {-267.5365415194631, 269.84182987161887, 511.3436},
        {-267.61373065225865, 270.1160644745327, 511.3436},
        {-267.76766935071606, 270.3268948153968, 511.3436},
        {-267.9635133168923, 270.5031064251607, 511.3436},
        {-268.18223756190065, 270.66041620554836, 511.3436},
        {-268.4131661787196, 270.8079300932808, 511.07516},
        {-268.64936025130186, 270.9525601566403, 509.80014},
    };
    private static final double[][] CAPTURED_DIAGONAL_RUN = {
        {-268.9056159321453, 271.0051404492622, 506.7137},
        {-269.17454010897364, 271.017824580888, 502.08386},
        {-269.45113641362155, 271.01691526516476, 498.46057},
        {-269.73207489590686, 271.0117708500871, 497.0514},
        {-270.0153998721764, 271.0047749551218, 496.85007},
        {-270.3000326082465, 270.9969176435168, 496.78296},
        {-270.5853793812508, 270.98858999837194, 496.78296},
    };
    private static final double MINECART_MIN_X = 0.01;
    private static final double MINECART_MAX_X = 0.99;

    @Test
    void zeroHealthClearsTravelInputOnlyAfterItsReceipt() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(.5, FLOOR_Y, .5);
            f.place(position, GROUNDED);
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.player.compensatedEntities.getSelf().setHealth(20);
            int before = f.player.sendTransactionAndGetId();
            int receipt = metadataHealth(f, 0);
            f.pongThrough(before);
            // The preceding ping does not prove metadata health. The client still travels on its keys.
            f.input(BACK);
            position = position.add(0, 0, -WALK);
            assertExact(f.tick(position, true));
            assertTrue(f.player.compensatedEntities.getSelf().actionAlive);

            f.pongThrough(receipt);
            f.place(position, GROUNDED);
            assertFalse(f.player.compensatedEntities.getSelf().actionAlive);
            // LivingEntity#aiStep clears xxa/zza after applyInput when health <= 0.
            assertExact(f.tick(position, true));
        }
    }

    @Test
    void aDeadPlayerCannotMoveFromReportedKeys() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(.5, FLOOR_Y, .5);
            f.place(position, GROUNDED);
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.pongThrough(metadataHealth(f, 0));
            f.input(BACK);
            PredictionResult moved = f.tick(position.add(0, 0, -WALK), true);
            assertTrue(moved.hasFlag(InputKeys.class), describe(moved));
        }
    }

    @Test
    void zeroHealthStillCarriesPacketVelocityWithHeldKeys() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(.5, FLOOR_Y, .5);
            f.place(position, GROUNDED);
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.pongThrough(metadataHealth(f, 0));
            f.input(BACK);
            f.pongThrough(f.velocity(new Vec3(.25, GROUNDED.y, 0)));
            assertExact(f.tick(position.add(.25, 0, 0), true));
        }
    }

    @Test
    void metadataAndSetHealthWriteTheSameClientHealthState() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(.5, FLOOR_Y, .5);
            f.place(position, GROUNDED);
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.pongThrough(metadataHealth(f, 0));
            assertFalse(f.player.compensatedEntities.getSelf().actionAlive);
            assertEquals(0, f.player.compensatedEntities.getSelf().getHealth());
            f.input(BACK);
            assertExact(f.tick(position, true));

            f.pongThrough(health(f, 20));
            assertTrue(f.player.compensatedEntities.getSelf().actionAlive);
            assertTrue(f.tick(position, true).hasFlag(InputKeys.class), "living keys must move the player again");
        }
    }

    private static int metadataHealth(JavaTickFixture f, float health) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(EntityMetadata.Entry.health(
                        WatchableIndexUtil.LIVING_HEALTH, f.fixture.data.version(), health)));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static int health(JavaTickFixture f, float health) {
        var packet = new ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth(health, 20, 5);
        var event = new PacketSendEvent<>(
                f.fixture.user,
                ConnectionPhase.PLAY,
                ac.cult.cultac.protocol.packet.ClientboundPackets.SET_HEALTH,
                packet,
                false);
        new ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn().onSetHealth(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

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
    void walkingPastAShulkersCornerAlongItsSideIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
            f.player.compensatedEntities.entityMap.get(100).setClientTickOrder(-1L);
            Vec3 start = new Vec3(1.23274584128289, FLOOR_Y, (1.0 + (1.0 + 0.6F)) / 2);
            double velocity = 0.044379499469431094;
            f.place(start, new Vec3(velocity, GROUNDED.y, 0));
            float yaw = 154.91962F;
            f.player.xRot = yaw;
            f.player.yRot = 36.494892F;
            var keys = new ServerboundPlayerInput(true, false, false, true, false, false, false);
            f.input(keys);
            Vec3 input = ClientMoveInput.of(f, keys, 1.0F, 0.1F, yaw);
            // Direction#axisStepOrder moves the longer z first, into the shulker's side; x then clears its corner.
            assertTrue(Math.abs(input.z) > Math.abs(velocity + input.x) && start.x - 0.3F < 1.0);
            assertExact(f.tick(start.add(velocity + input.x, 0, 0), true));

            assertNoFlags(f);
        }
    }

    @Test
    void aPushedPlayerStoppedByAShulkersSideBesideABlockIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(2, FLOOR_Y, 1),
                    registry.block("minecraft:stone").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            double face = 1.0;
            // Shulker#setPos snaps an unmounted shell to its block; the neighboring block meets that side.
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.SHULKER, new Vec3(1.5, FLOOR_Y, face - 0.5), 0, 0, 0);
            Vec3 target = new Vec3(2 - 0.3F, FLOOR_Y, face + 0.3F);
            Vec3 start = target.add(-0.01, 0, 0.012);
            f.player.compensatedEntities.entityMap.get(100).setClientTickOrder(-1L);
            f.place(start, new Vec3(0, GROUNDED.y, -0.09));
            f.player.xRot = -90.0F;
            f.player.yRot = 0.0F;
            f.input(FORWARD);
            assertExact(f.tick(target, true));
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
    void sprintingBackwardIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            startCapturedRun(f, SPRINT_BACK, CAPTURED_BACKWARD_RUN[0], CAPTURED_BACKWARD_RUN[1]);
            f.command(PlayerCommandAction.START_SPRINTING);
            assertRunFlagged(f, CAPTURED_BACKWARD_RUN);
        }
    }

    @Test
    void sprintingSidewaysIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.command(PlayerCommandAction.START_SPRINTING);
            startCapturedRun(f, SPRINT_BACK_LEFT, CAPTURED_SIDEWAYS_RUN[0], CAPTURED_SIDEWAYS_RUN[1]);
            f.input(SPRINT_LEFT);
            assertRunFlagged(f, CAPTURED_SIDEWAYS_RUN);
        }
    }

    @Test
    void sprintingDiagonallyForwardIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.command(PlayerCommandAction.START_SPRINTING);
            startCapturedRun(f, SPRINT_FORWARD_LEFT, CAPTURED_DIAGONAL_RUN[0], CAPTURED_DIAGONAL_RUN[1]);
            for (int row = 2; row < CAPTURED_DIAGONAL_RUN.length; row++) {
                assertExact(replay(f, CAPTURED_DIAGONAL_RUN[row]));
            }

            assertNoFlags(f);
        }
    }

    @Test
    void sprintStrafingIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, -4.5), GROUNDED);
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(SPRINT_FORWARD);
            Vec3 position = new Vec3(0.5, FLOOR_Y, -4.5);
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < 12; tick++) {
                ServerboundPlayerInput keys =
                        tick < 4 ? SPRINT_FORWARD : tick < 8 ? SPRINT_FORWARD_RIGHT : SPRINT_FORWARD_LEFT;
                f.input(keys);
                f.player.xRot = tick * 7.5F;
                Vec3 move = velocity.add(ClientMoveInput.of(f, keys, 1.0F, SPRINTING_SPEED, f.player.xRot));
                position = position.add(move.x, 0, move.z);
                assertExact(f.tick(position, true));
                velocity = new Vec3(move.x * FRICTION, 0, move.z * FRICTION);
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
    void walkingAt1214ReplacesTheServersPowderSnowModifierAfterTravel() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            f.place(new Vec3(0.5, FLOOR_Y, 4.5), GROUNDED);
            // Travel uses the server's modifier once; removeFrost runs afterwards, with zero frozen ticks here.
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
                double input = tick == 0 ? (double) 0.98F * (double) (float) (0.1F - 0.02D) : WALK;
                z += velocity - input;
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
                velocity = (velocity - input) * FRICTION;
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

    @Test
    void sprintingUnderAHappyGhastHoveringABlockUpIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            sprintUnderAHoveringHappyGhast(f, false);
        }
    }

    @Test
    void sprintingUnderAGhastlingHoveringABlockUpIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            sprintUnderAHoveringHappyGhast(f, true);
        }
    }

    /**
     * HappyGhast#canBeCollidedWith admits a player below the top of a ghast's box only while it stays still, and
     * never admits a ghastling, so neither blocks the standing pose.
     */
    private static void sprintUnderAHoveringHappyGhast(JavaTickFixture f, boolean baby) {
        f.player.compensatedEntities.addEntity(
                100, EntityTypeIds.HAPPY_GHAST, new Vec3(0.5, FLOOR_Y + 1, 0.5), 0, 0, 0);
        var ghast = (PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(100);
        ghast.isBaby = baby;
        assertFalse(ghast.staysStill);
        f.place(new Vec3(0.5, FLOOR_Y, -4.5), GROUNDED);
        f.player.isSprinting = true;
        f.player.sprintState.onClientTick(true);
        f.input(SPRINT_FORWARD);
        double z = -4.5;
        double velocity = 0.0;
        while (z < 0.5) {
            z += velocity + SPRINT;
            f.tick(new Vec3(0.5, FLOOR_Y, z), true);
            velocity = (velocity + SPRINT) * FRICTION;
        }

        assertNoFlags(f);
        var standing = new SimpleCollisionBox(0.2, FLOOR_Y, z - 0.3, 0.8, FLOOR_Y + 1.8, z + 0.3);
        assertTrue(ghast.getPossibleMovementCollisionBoxes().isIntersected(standing));
        assertEquals(Pose.STANDING, f.player.pose);
    }

    @Test
    void walkingUnderAStillHappyGhastHoveringABlockUpIsSlowedLikeCrawling() throws Exception {
        try (var f = new JavaTickFixture()) {
            walkUnderAStillHappyGhast(f, (double) (0.98F * 0.3F) * (float) (0.1F * 1.4D));
            assertNoFlags(f);
            assertEquals(Pose.SWIMMING, f.player.pose);
        }
    }

    @Test
    void walkingUnderAStillHappyGhastHoveringABlockUpAtTheWalkingSpeedIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertTrue(walkUnderAStillHappyGhast(f, (double) 0.98F * (float) (0.1F * 1.4D)) > 0);
        }
    }

    /**
     * The InputKeys violations of a walk with Speed II along +x by {@code accel} under an adult happy ghast that stays
     * still a block up. HappyGhast#canBeCollidedWith admits the player there, so neither the standing nor the crouching pose fits and
     * Player#updatePlayerPose falls back to the swimming pose, which LocalPlayer#isMovingSlowly counts as crawling.
     */
    private static double walkUnderAStillHappyGhast(JavaTickFixture f, double accel) {
        f.player.compensatedEntities.addEntity(
                100, EntityTypeIds.HAPPY_GHAST, new Vec3(0.5, FLOOR_Y + 1, 0.5), 0, 0, 0);
        ((PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(100)).staysStill = true;
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot(
                        "minecraft:movement_speed",
                        0.1F,
                        List.of(new AttributeModifier(
                                "minecraft:effect.speed", 0.4D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)))));
        f.player.xRot = -90;
        double x = 0.5;
        f.place(new Vec3(x, FLOOR_Y, 0.5), GROUNDED);
        f.input(NONE);
        f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
        assertEquals(Pose.SWIMMING, f.player.pose);
        f.input(FORWARD);
        double velocity = 0.0;
        for (int tick = 0; tick < 4; tick++) {
            velocity += accel;
            x += velocity;
            f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
            velocity *= FRICTION;
        }
        return keyFlags(f);
    }

    @Test
    void reversingWhileSneakingThroughAMinecartWithSpeedIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            double x = -2.5;
            startAlongX(f, x, FLOOR_Y);
            addMinecart(f);
            f.input(SNEAK_SPRINT);
            assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
            f.input(SNEAK_SPRINT_FORWARD);
            double velocity = 0.0;
            for (int tick = 0; tick < 40; tick++) {
                if (tick == 30) f.input(SNEAK_SPRINT_BACK);
                velocity += tick < 30 ? SNEAKING_WITH_SPEED : -SNEAKING_WITH_SPEED;
                x += velocity;
                assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
                velocity *= FRICTION;
            }

            assertNoFlags(f);
        }
    }

    @Test
    void pressingShiftAsTheBoxEntersAMinecartSlowsTheKeys() throws Exception {
        try (var f = new JavaTickFixture()) {
            pressShiftAsTheBoxEntersAMinecart(f, SNEAKING_WITH_SPEED);
            assertNoFlags(f);
        }
    }

    @Test
    void walkingOnAfterPressingShiftAsTheBoxEntersAMinecartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            pressShiftAsTheBoxEntersAMinecart(f, WALKING_WITH_SPEED);
            assertTrue(keyFlags(f) > 0);
        }
    }

    /**
     * Walks into a minecart with Speed II, pressing shift on the tick whose move brings the box into the cart, then
     * accelerates by {@code accel}. LocalPlayer#aiStep reads the previous tick's shift, so the press tick still walks.
     */
    private static void pressShiftAsTheBoxEntersAMinecart(JavaTickFixture f, double accel) {
        double x = -3.5;
        startAlongX(f, x, FLOOR_Y);
        addMinecart(f);
        f.input(FORWARD);
        double velocity = 0.0;
        int pressed = -1;
        for (int tick = 0; pressed < 0 || tick < pressed + 4; tick++) {
            if (pressed < 0 && x + velocity + WALKING_WITH_SPEED + 0.3 > MINECART_MIN_X) {
                pressed = tick;
                f.input(SNEAK_FORWARD);
            }
            velocity += pressed < 0 || tick == pressed ? WALKING_WITH_SPEED : accel;
            x += velocity;
            PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
            if (accel == SNEAKING_WITH_SPEED) assertExact(result);
            velocity *= FRICTION;
        }
        assertTrue(x - 0.3 < MINECART_MAX_X, "the box left the cart at " + x);
    }

    @Test
    void releasingShiftInsideAMinecartWalksAtTheWalkingSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            double x = -3.5;
            startAlongX(f, x, FLOOR_Y);
            addMinecart(f);
            f.input(SNEAK);
            assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
            f.input(SNEAK_FORWARD);
            double velocity = 0.0;
            int released = -1;
            for (int tick = 0; released < 0 || tick < released + 5; tick++) {
                if (released < 0 && x > 0.0) {
                    released = tick;
                    f.input(FORWARD);
                }
                // LocalPlayer#aiStep reads the previous tick's shift, so the release tick still sneaks.
                velocity += released < 0 || tick == released ? SNEAKING_WITH_SPEED : WALKING_WITH_SPEED;
                x += velocity;
                assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
                velocity *= FRICTION;
            }

            assertTrue(x - 0.3 < MINECART_MAX_X, "the box left the cart at " + x);
            assertEquals(Pose.STANDING, f.player.pose);
            assertNoFlags(f);
        }
    }

    @Test
    void releasingShiftUnderAShulkerThatBlocksStandingKeepsTheSneakingSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            releaseShiftUnderAShulkerThatBlocksStanding(f, SNEAKING_WITH_SPEED, 8);
            assertNoFlags(f);
        }
    }

    @Test
    void releasingShiftUnderAShulkerThatBlocksStandingAndWalkingOnIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            releaseShiftUnderAShulkerThatBlocksStanding(f, WALKING_WITH_SPEED, 3);
            assertTrue(keyFlags(f) > 0);
        }
    }

    /**
     * Sneaks with Speed II along slabs under a shulker whose box overlaps the standing box there but not the crouching
     * one, releases shift under it and then accelerates by {@code accel} for {@code ticks} ticks. LocalPlayer#aiStep
     * reads the previous tick's shift, so the release tick still sneaks.
     */
    private static void releaseShiftUnderAShulkerThatBlocksStanding(JavaTickFixture f, double accel, int ticks) {
        var registry = DataTables.defaults().registry();
        int slab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "bottom");
        for (int blockX = -5; blockX <= 3; blockX++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(blockX, FLOOR_Y, 0), slab);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, new Vec3(0.5, FLOOR_Y + 2, 0.5), 0, 0, 0);
        double y = FLOOR_Y + 0.5;
        double x = -3.5;
        startAlongX(f, x, y);
        f.input(SNEAK);
        assertExact(f.tick(new Vec3(x, y, 0.5), true));
        f.input(SNEAK_FORWARD);
        double velocity = 0.0;
        int released = -1;
        for (int tick = 0; released < 0 || tick <= released + ticks; tick++) {
            if (released < 0 && x > -0.2) {
                released = tick;
                f.input(FORWARD);
            }
            velocity += released < 0 || tick == released ? SNEAKING_WITH_SPEED : accel;
            x += velocity;
            PredictionResult result = f.tick(new Vec3(x, y, 0.5), true);
            if (accel == SNEAKING_WITH_SPEED) assertExact(result);
            velocity *= FRICTION;
        }
        assertTrue(x - 0.3 < 1.0, "the box left the shulker at " + x);
    }

    @Test
    void walkingSlowlyWithoutShiftBesideAShulkerThatMayCoverTheHeadIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertTrue(moveOnceWithoutShiftBesideAShulker(f, SHULKER_CLEAR_OF_THE_PLAYER, true, SNEAK_STEP) > 0);
        }
    }

    @Test
    void walkingWithoutShiftBesideAShulkerThatMayCoverTheHeadMovesAtTheWalkingSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertEquals(0, moveOnceWithoutShiftBesideAShulker(f, SHULKER_CLEAR_OF_THE_PLAYER, true, WALK));
        }
    }

    @Test
    void walkingSlowlyWithoutShiftUnderAShulkerOverTheHeadIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertTrue(moveOnceWithoutShiftBesideAShulker(f, SHULKER_OVER_THE_HEAD, false, SNEAK_STEP) > 0);
        }
    }

    @Test
    void walkingSlowlyWithoutShiftBesideAShulkerClearOfThePlayerIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertTrue(moveOnceWithoutShiftBesideAShulker(f, SHULKER_CLEAR_OF_THE_PLAYER, false, SNEAK_STEP) > 0);
        }
    }

    /**
     * The InputKeys violations of one move along +z by {@code accel} without shift from (0.5, FLOOR_Y, -2.5), beside a
     * shulker spawned at {@code at}; one {@code moving} over the player's head has had the first ping of that move
     * answered and so may be at either position. A shulker over the head overlaps both the crouching and the standing
     * box, so in no world does LocalPlayer#aiStep crouch the player.
     */
    private static double moveOnceWithoutShiftBesideAShulker(JavaTickFixture f, Vec3 at, boolean moving, double accel) {
        f.place(new Vec3(0.5, FLOOR_Y, -2.5), GROUNDED);
        f.player.xRot = 0;
        f.input(NONE);
        f.tick(new Vec3(0.5, FLOOR_Y, -2.5), true);
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, at, 0, 0, 0);
        if (moving) {
            f.tick(new Vec3(0.5, FLOOR_Y, -2.5), true);
            f.player
                    .compensatedEntities
                    .getEntity(100)
                    .onFirstTransaction(true, true, SHULKER_OVER_THE_HEAD.x - at.x, 0, 0, f.player);
        }
        f.input(FORWARD);
        f.tick(new Vec3(0.5, FLOOR_Y, -2.5 + accel), true);
        return keyFlags(f);
    }

    @Test
    void releasingShiftWhereStandingFitsAndKeepingTheSneakingSpeedIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            double x = -3.5;
            startAlongX(f, x, FLOOR_Y);
            f.input(SNEAK);
            assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
            f.input(SNEAK_FORWARD);
            double velocity = 0.0;
            for (int tick = 0; tick < 10; tick++) {
                if (tick == 5) f.input(FORWARD);
                velocity += SNEAKING_WITH_SPEED;
                x += velocity;
                f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
                velocity *= FRICTION;
            }

            assertTrue(keyFlags(f) > 0);
        }
    }

    @Test
    void walkingOutFromAShulkerOverTheFeetWithShiftHeldMovesAtTheWalkingSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            double x = -3.5;
            startAlongX(f, x, FLOOR_Y);
            f.input(SNEAK);
            assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
            // Its box overlaps the swimming box and so the crouching one: LocalPlayer#aiStep does not crouch.
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, new Vec3(x, FLOOR_Y, 0.5), 0, 0, 0);
            f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
            f.input(SNEAK_FORWARD);
            double velocity = 0.0;
            for (int tick = 0; tick < 4; tick++) {
                velocity += WALKING_WITH_SPEED;
                x += velocity;
                f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
                velocity *= FRICTION;
            }

            assertEquals(0, keyFlags(f));
        }
    }

    /** Places the player grounded at {@code (x, y, 0.5)} facing +x with Speed II. */
    private static void startAlongX(JavaTickFixture f, double x, double y) {
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot(
                        "minecraft:movement_speed",
                        0.1F,
                        List.of(new AttributeModifier(
                                "minecraft:effect.speed", 0.4D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)))));
        f.player.xRot = -90;
        f.place(new Vec3(x, y, 0.5), GROUNDED);
    }

    /** A minecart on the floor block at (0, 0), its box spanning x from 0.01 to 0.99. */
    private static void addMinecart(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(
                101, EntityTypes.defaults().byKey("minecart").id(), new Vec3(0.5, FLOOR_Y + 0.0625, 0.5), 0, 0, 0);
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

    private static void startCapturedRun(JavaTickFixture f, ServerboundPlayerInput keys, double[] from, double[] to) {
        f.player.xRot = (float) to[2];
        f.place(captured(to), new Vec3((to[0] - from[0]) * FRICTION, GROUNDED.y, (to[1] - from[1]) * FRICTION));
        f.input(keys);
    }

    private static void assertRunFlagged(JavaTickFixture f, double[][] run) {
        for (int row = 2; row < run.length; row++) {
            PredictionResult result = replay(f, run[row]);
            assertTrue(
                    result.hasFlag(ac.cult.cultac.checks.impl.prediction.checks.psuedo.OmniSprint.class),
                    "row " + row + " " + describe(result));
        }
    }

    private static PredictionResult replay(JavaTickFixture f, double[] row) {
        f.player.xRot = (float) row[2];
        return f.tick(captured(row), true);
    }

    private static Vec3 captured(double[] row) {
        return new Vec3(row[0] + CAPTURE_SHIFT_X, FLOOR_Y, row[1] + CAPTURE_SHIFT_Z);
    }
}
