package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.elytra.ElytraF;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A piston facing north at z = 2 pushes the stone at z = 1 into a player south of it. The client's
 * PistonMovingBlockEntity#tick moves the stone 0.5 per tick and its moveCollidedEntities shoves the player by its depth
 * into that tick's movement area plus 0.01, through Entity#move(PISTON), which sets onGround from that move alone.
 */
class JavaPistonPassTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput BACK =
            new ServerboundPlayerInput(false, true, false, false, false, false, false);
    private static final BlockPos PISTON = new BlockPos(0, FLOOR_Y, 2);

    /** LivingEntity#canGlide reads onGround after the preceding tick's horizontal Entity#move(PISTON). */
    @ParameterizedTest
    @CsvSource({"V_1_21_5, true", "V_1_21_5, false", "V_26_2, true", "V_26_2, false"})
    void aGlideStartUsesTheGroundStateAfterThePistonRatherThanTheEarlierReport(ClientVersion version, boolean pushed)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.4);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
            assertExact(f.tick(position, true));
            if (pushed) blockEvent(f, piston);
            assertExact(f.tick(position, true));
            assertEquals(!pushed, f.player.onGround, "the block-entity move updates the current client state");
            assertTrue(f.player.packetStateData.packetPlayerOnGround, "the earlier packet remains a grounded report");

            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            var packet =
                    new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
            var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
            new PacketEntityAction().onPlayerCommand(event, f.player, packet);
            assertEquals(!pushed, event.isCancelled());
            assertEquals(pushed, f.player.isGliding);
            f.player.checkManager.getCheck(ElytraF.class).onPlayerCommand(event, f.player, packet);
            assertEquals(pushed ? 0 : 1, f.player.checkManager.getCheck(ElytraF.class).violations);
        }
    }

    /** A clipped horizontal shove clears onGround without changing the position the client last sent. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_5", "V_26_2"})
    void anIdleLandingAfterAClippedPistonRestoresGroundBeforeTheNextInput(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int piston = pistonWithStone(f);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, -1),
                    REGISTRY.block("minecraft:stone").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, HALF_WIDTH);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            blockEvent(f, piston);
            assertExact(f.tick(position, true));
            assertFalse(f.player.onGround);
            for (int tick = 0; tick < 3; tick++) {
                var previous = f.player.checkManager.getSimulationProcessor().getLastPrediction();
                // sendPosition's cached onGround remains true: travel lands again before the next piston phase.
                f.tickEnd();
                var landed = f.player.checkManager.getSimulationProcessor().getLastPrediction();
                assertTrue(previous != landed, "each withheld idle travel must commit");
                assertExact(landed);
            }
            assertTrue(f.player.onGround);
            assertEquals(
                    ac.cult.cultac.checks.impl.prediction.DesyncStatus.TRUE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
            f.input(new ServerboundPlayerInput(false, false, true, false, false, false, false));
            assertExact(f.tick(position.add((double) (0.98F * 0.1F), 0, 0), true));
        }
    }

    /** LivingEntity#pushEntities precedes the shove, even though 26.3 reports after it. */
    @ParameterizedTest
    @CsvSource({"1.2, 1", "2.0, 0"})
    void aLaterEntityPushesFromTheTravelEndpointBeforeThePistonSeparatesThem(double entityZ, int count)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.7);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, new Vec3(0.5, FLOOR_Y, entityZ), 0, 0, 0);
            blockEvent(f, piston);

            var prediction = f.tick(new Vec3(0.5, FLOOR_Y, shove(position.z, 0.5)), false);
            assertExact(prediction);
            assertEquals(count, prediction.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());
        }
    }

    @Test
    void aJavaPistonDoesNotAlsoSupplyAnUnknownWholeStrokeCollider() throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            blockEvent(f, piston);
            var unknown = new ArrayList<SimpleCollisionBox>();
            f.player.compensatedWorld.addDynamicBlockCollisionUncertaintyBoxes(
                    new SimpleCollisionBox(-1, FLOOR_Y, -1, 2, FLOOR_Y + 2, 4), unknown);
            assertTrue(unknown.isEmpty(), "the exact piston pass must not retain the old collision envelope");
            assertTrue(
                    !f.player
                            .compensatedWorld
                            .pistons
                            .getMovingPistonCollisionShape(new BlockPos(0, FLOOR_Y, 0))
                            .isEmpty(),
                    "the moving stone still has its exact collision shape");
        }
    }

    /**
     * The stone's first movement area ends at its north face, z = 1, and reaches 0.5 north of it: a player whose box
     * ends 0.2 into it moves 0.21 north. The second area starts 0.5 further north, and the player, 0.49 into it, moves
     * 0.5. Before 26.3 LocalPlayer#tick reports the position before Level#tickBlockEntities, so each shove reaches the
     * server with the next packet; from 26.3 Minecraft#tick reports after the block entities, with the shove's onGround.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_5", "V_1_21_11", "V_26_2", "V_26_3"})
    void anExtendingPistonPushesAStandingPlayerTwice(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.4);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));

            Vec3 first = new Vec3(position.x, position.y, shove(position.z, 0.5));
            Vec3 second = new Vec3(position.x, position.y, shove(first.z, 0.0));
            blockEvent(f, piston);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // A horizontal Entity#move(PISTON) collides with nothing below, so the report is off the ground.
                assertExact(f.tick(first, false));
                assertTrue(
                        f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                                .anyMatch(v -> v.y == -FALL),
                        "travel's landing clears Y before gravity, then a horizontal piston preserves it");
                assertExact(f.tick(second, false));
                assertExact(f.tick(second, true));
            } else {
                assertExact(f.tick(position, true));
                // The travel after each shove starts off the ground and lands again on the floor.
                assertExact(f.tick(first, true));
                assertExact(f.tick(second, true));
                assertExact(f.tick(second, true));
            }
            assertEquals(second.z, f.player.z);
        }
    }

    /**
     * A 26.3 player backing away from the stone: each pass leaves the player 0.01 north of the stone's movement area
     * wherever its travel ended, so the report does not show the travel's input on Z. LocalPlayer#sendChanges reported
     * the S key before the position, and the travel carries that key's input: the ticks after each pass start from
     * it, on the ground's input and friction before the first shove and the air's after it.
     */
    @Test
    void theKeysGiveTheInputAPassHides() throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.4);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));

            f.input(BACK);
            blockEvent(f, piston);
            double groundInput = ClientMoveInput.of(f, BACK, 1.0F, 0.1F, 0.0F).z;
            double airInput = ClientMoveInput.of(f, BACK, 1.0F, 0.02F, 0.0F).z;
            double groundFriction = (double) (0.6F * 0.91F);
            double airFriction = (double) 0.91F;

            // The ground travel backs into the first movement area, which leaves the player 0.01 north of it.
            double velocity = groundInput;
            double z = shove(position.z + velocity, 0.5);
            velocity *= groundFriction;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), false));

            // The air travel after the shove, then the second area, 0.5 further north.
            velocity += airInput;
            z = shove(z + velocity, 0.0);
            velocity *= airFriction;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), false));

            // No more shoves: the air travel lands, then the ground carries the keys' input.
            velocity += airInput;
            z += velocity;
            velocity *= airFriction;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
            velocity += groundInput;
            z += velocity;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
        }
    }

    @Test
    void inputCanTurnAFullPistonShoveIntoAPartialShove() throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.7);
            f.player.xRot = 32.49F;
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.input(BACK);
            Vec3 input = ClientMoveInput.of(f, BACK, 1.0F, 0.1F, 32.49F);
            blockEvent(f, piston);
            Vec3 first = new Vec3(position.x + input.x, FLOOR_Y, shove(position.z + input.z, 0.5));
            assertExact(f.tick(first, false));
            Vec3 carried = input.multiply((double) (0.6F * 0.91F), 0, (double) (0.6F * 0.91F));
            assertTrue(
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                            .anyMatch(v -> Math.abs(v.x - carried.x) < 1.0E-12 && Math.abs(v.z - carried.z) < 1.0E-12),
                    "the partial shove hides position, not the travel's input velocity");
        }
    }

    @Test
    void aStationaryPlayerCannotIgnoreAnUnavoidablePistonShove() throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.4);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            blockEvent(f, piston);
            assertFalse(exact(f.tick(position, true)), "ignoring the shove must still flag");
        }
    }

    /** Every reachable input still leaves the player 0.01 beyond the moving face on the shoved axis. */
    @ParameterizedTest
    @ValueSource(doubles = {-0.01, -0.0001, 0.0001, 0.01})
    void aPistonShoveCannotBeShortenedByTheTravelInputRadius(double extra) throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.4);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            blockEvent(f, piston);
            Vec3 report = new Vec3(position.x, position.y, shove(position.z, 0.5) + extra);
            var result = f.tick(report, false);
            assertFalse(
                    exact(result),
                    () -> "accepted displacement from the exact moving face: " + extra + " " + describe(result));
        }
    }

    @Test
    void travelAndPistonMoveUseTheirOwnSupportingBlocksForSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            int honey = REGISTRY.block("minecraft:honey_block").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 0), honey);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, -1), honey);
            var pistonPos = new BlockPos(2, FLOOR_Y + 1, 0);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "west");
            f.player.compensatedWorld.updateBlock(pistonPos, piston);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(1.1981003640706, FLOOR_Y, 0.06060181972858);
            Vec3 velocity = new Vec3(0.06330233064157442, -FALL, -0.09863093471995575);
            f.player.xRot = 32.49F;
            f.place(position, velocity);
            f.input(BACK);
            Vec3 input = ClientMoveInput.of(f, BACK, 1.0F, 0.1F, 32.49F);
            blockEvent(f, pistonPos, piston, 4);
            Vec3 travel = position.add(velocity.x + input.x, 0, velocity.z + input.z);
            Vec3 reported = new Vec3(1.5 - HALF_WIDTH - 0.01, FLOOR_Y, travel.z);
            assertExact(f.tick(reported, false));
            // Entity#move's landing retains the stone at the old box as its supporting block. The piston's
            // horizontal move clears that support, so only that second move reads the honey beneath the center.
            double factor = (double) (0.6F * 0.91F) * (double) 0.4F;
            Vec3 expected = velocity.add(input).multiply(factor, 0, factor);
            assertTrue(
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                            .anyMatch(
                                    v -> Math.abs(v.x - expected.x) < 1.0E-12 && Math.abs(v.z - expected.z) < 1.0E-12),
                    "the honey factor belongs to the piston move, not also to the stone-supported travel");
        }
    }

    @Test
    void pistonConsumesTheTravelPowderSnowMultiplierAfterGravity() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y, -1),
                    REGISTRY.block("minecraft:powder_snow").defaultState());
            var pistonPos = new BlockPos(2, FLOOR_Y + 1, -1);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "west");
            f.player.compensatedWorld.updateBlock(pistonPos, piston);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(1.1822398992313, FLOOR_Y, -0.39388586126794);
            f.player.xRot = 32.49F;
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            f.input(BACK);
            Vec3 input = ClientMoveInput.of(f, BACK, 1.0F, 0.1F, 32.49F);
            blockEvent(f, pistonPos, piston, 4);
            // The piston is already halfway extended when this travel reaches its moving head.
            f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 reported = new Vec3(1.0 - HALF_WIDTH - 0.01, FLOOR_Y, position.z + input.z * (double) 0.9F);
            PredictionResult pushed = f.tick(reported, false);
            assertExact(pushed);
            assertTrue(
                    f.player
                            .checkManager
                            .getSimulationProcessor()
                            .getValidPlayerStartingVels()
                            .contains(Vec3.ZERO),
                    "the later piston move consumes powder snow's multiplier and clears velocity, including gravity");
            Vec3 airInput = ClientMoveInput.of(f, BACK, 1.0F, 0.02F, 32.49F);
            // The shove leaves 0.01 between the box and the moving head's west face at x=1.
            // Entity#collide clips the next input's 0.010527 X to that gap; it cannot traverse the head.
            double headGap = 1.0 - HALF_WIDTH - reported.x;
            var following = f.tick(reported.add(Math.min(airInput.x, headGap), airInput.y, airInput.z), false);
            assertExact(following);
            var collision = (JavaMoveCollision) following.getCollideAxisData();
            assertTrue(collision.collidedX());
            assertEquals(headGap, collision.collided().x, 1.0E-12);
            // Rejecting a report still chooses the predicted travel before replaying the later piston.
            pushed.addFlag(f.player.checkManager.getAngleCheck(), () -> "rejected report", 0.1D);
            Vec3 expectedTravel = JavaMovementEngine.passAfterTravel(pushed)
                    .travel()
                    .position()
                    .subtract(position);
            assertTrue(JavaFallDistance.afterCollision(f.player, pushed, reported.subtract(position))
                            .movement()
                            .distanceTo(expectedTravel)
                    < 1.0E-12);
        }
    }

    @Test
    void aTravelStepBeforeThePistonIsStillPartOfTravel() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    REGISTRY.block("minecraft:stone_slab").defaultState());
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y, 0),
                    REGISTRY.block("minecraft:stone").defaultState());
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y + 1, -1),
                    REGISTRY.block("minecraft:stone_slab").defaultState());
            var pistonPos = new BlockPos(0, FLOOR_Y + 1, 2);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "north");
            f.player.compensatedWorld.updateBlock(pistonPos, piston);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y + 1, 1),
                    REGISTRY.block("minecraft:stone").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.69, FLOOR_Y + 0.5, 0.4);
            f.place(position, new Vec3(0.08, -FALL, 0));
            f.input(NONE);
            blockEvent(f, pistonPos, piston, 2);
            PredictionResult result = f.tick(new Vec3(0.77, FLOOR_Y + 1.5, shove(position.z, 0.5)), false);
            assertTrue(
                    exact(result),
                    () -> describe(result) + " predicted=" + result.getLegacyLikePredictionVector() + " pass="
                            + JavaMovementEngine.passAfterTravel(result));
        }
    }

    @Test
    void aPistonDoesNotGiveGlidingTravelKeyboardAcceleration() throws Exception {
        try (var f = new JavaTickFixture()) {
            int piston = pistonWithStone(f);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            f.player.xRot = 32.49F;
            f.player.yRot = 13.0F;
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.4);
            Vec3 velocity = new Vec3(0, -FALL, 0);
            f.place(position, velocity);
            f.input(BACK);
            Vec3 glide = ElytraTransform.getElytraMovement(
                    f.player, velocity, ReachUtils.getLook(f.player, 32.49F, 13.0F), 13.0F, 0.08);
            blockEvent(f, piston);
            assertExact(f.tick(new Vec3(position.x + glide.x, FLOOR_Y, shove(position.z + glide.z, 0.5)), false));
            Vec3 expected = new Vec3(glide.x, 0, glide.z);
            assertTrue(
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                            .anyMatch(v -> v.distanceToSqr(expected) < 1.0E-20),
                    "travelFallFlying never adds the reported keyboard input to stored velocity");
        }
    }

    /** Player#updatePlayerPose leaves the old pose untouched when even its swimming box is obstructed. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingTheGlideFlagInsideAPistonDoesNotForceAPoseChange(boolean gliding) throws Exception {
        try (var f = new JavaTickFixture()) {
            int head = REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "east");
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), head);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.9, FLOOR_Y, 0.5);
            f.player.pose = gliding ? Pose.STANDING : Pose.FALL_FLYING;
            f.player.isGliding = gliding;
            f.place(position, Vec3.ZERO);
            assertFalse(f.player.canPlayerFitWithinBlocksAndEntitiesWhen(
                    Pose.SWIMMING, position.x, position.y, position.z));
            assertEquals(f.player.pose, f.player.updatedPose(false, position.x, position.y, position.z));
        }
    }

    @ParameterizedTest
    @CsvSource({"V_26_3, false", "V_26_3, true", "V_1_21_5, false", "V_1_21_5, true"})
    void travelInputIncludesForcedCrouchingBeforeThePoseUpdate(ClientVersion version, boolean lowCeiling)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            if (lowCeiling) {
                int topSlab =
                        REGISTRY.with(REGISTRY.block("minecraft:stone_slab").defaultState(), "type", "top");
                f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), topSlab);
                for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            var result = f.tick(position, true);
            assertEquals(Pose.STANDING, result.getSimulationContext().getPose());
            var inputs = ClientKeyInput.possibleTravelInputs(
                    f.player, result.getSimulationContext(), result.getInitialStartingVel());
            var forward = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            Vec3 slow = ClientMoveInput.of(f, forward, 0.3F, 0.1F, 0);
            Vec3 fast = ClientMoveInput.of(f, forward, 1, 0.1F, 0);
            assertEquals(lowCeiling, inputs.stream().anyMatch(v -> v.distanceToSqr(slow) < 1e-20));
            assertEquals(!lowCeiling, inputs.stream().anyMatch(v -> v.distanceToSqr(fast) < 1e-20));
            f.input(forward);
            var reported = ClientKeyInput.travelInputs(
                    f.player, result.getSimulationContext(), result.getInitialStartingVel());
            Vec3 expected = lowCeiling ? slow : fast;
            assertTrue(
                    reported.stream().anyMatch(v -> v.distanceToSqr(expected) < 1e-20),
                    "a piston hiding this input still needs its exact contribution to the carried velocity");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFullSidewaysShoveIsNotPartOfTheTravelInputAngle(boolean excessiveInput) throws Exception {
        try (var f = new JavaTickFixture()) {
            var pistonPos = new BlockPos(-2, FLOOR_Y, 0);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            f.player.compensatedWorld.updateBlock(pistonPos, piston);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(-1, FLOOR_Y, 0),
                    REGISTRY.block("minecraft:stone").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.1, FLOOR_Y + 0.42, 0.5);
            Vec3 velocity = new Vec3(-0.02873925409756608, 0.33319999363422365, 0.04513493385677914);
            f.player.xRot = 32.49F;
            f.place(position, velocity);
            var forward = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            f.input(forward);
            Vec3 input = ClientMoveInput.of(f, forward, 1, 0.02F, 32.49F);
            blockEvent(f, pistonPos, piston, 5);
            var result = f.tick(position.add(velocity).add(input).add(0.51, 0, excessiveInput ? 0.2 : 0), false);
            if (excessiveInput) {
                assertTrue(!result.isExempt() && result.getOffset() > 0.1, "the shove cannot admit excessive input");
            } else {
                assertExact(result);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUpwardPistonRetainsTheTravelSelectedByJumpInput(boolean jumping) throws Exception {
        try (var f = new JavaTickFixture()) {
            var pistonPos = new BlockPos(0, FLOOR_Y - 2, 0);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(pistonPos, piston);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));
            if (jumping) f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            blockEvent(f, pistonPos, piston, 1);
            assertExact(f.tick(position.add(0, 0.51, 0), false));
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carried.stream().anyMatch(v -> v.y == (jumping ? 0.33319999363422365 : -FALL)),
                    "a full shove and a jump plus partial shove share the endpoint, but jump was not pressed: "
                            + carried);
        }
    }

    /** Where a moving stone whose movement area starts at {@code areaMinZ} shoves a player whose travel ended at z. */
    private static double shove(double z, double areaMinZ) {
        return z - (Math.min(z + HALF_WIDTH - areaMinZ, 0.5) + 0.01);
    }

    /** A piston facing north at {@link #PISTON} with stone in front of it; the piston's state. */
    private static int pistonWithStone(JavaTickFixture f) {
        int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "north");
        f.player.compensatedWorld.updateBlock(PISTON, piston);
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y, 1), REGISTRY.block("minecraft:stone").defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return piston;
    }

    /** The extend block event, which Level#blockEvent reaches before the client tick's LocalPlayer#tick. */
    private static void blockEvent(JavaTickFixture f, int state) {
        blockEvent(f, PISTON, state, 2);
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int state, int direction) {
        var packet = new ClientboundBlockEvent(pos, 0, direction, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
        var pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null)
            assertEquals(
                    pass.pose(),
                    result.getPlayer().pose,
                    "a later piston move must retain the pose chosen at the travel endpoint");
    }
}
