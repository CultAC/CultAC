package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaMovementAttributeTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);

    @Test
    void aJumpUsesTheJumpStrengthAttribute() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:jump_strength", 0.41999998688697815, 0.8);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            // LivingEntity#getJumpPower: (float) jump_strength, here 0.42 * 1.8.
            f.input(JUMP);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.7559999823570251, 0.5), false));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, f.player.y + 0.6624799956035616, 0.5), false));
        }
    }

    @Test
    void aZeroJumpStrengthDoesNotJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:jump_strength", 0.41999998688697815, -1.0);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            f.input(JUMP);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void aZeroJumpStrengthGivesNoSprintJumpBoost() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:jump_strength", 0.41999998688697815, -1.0);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
            f.player.isSprinting = true;

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            f.input(JUMP);
            // LivingEntity#jumpFromGround returns before the sprint boost of 0.2 along the view.
            PredictionResult boosted = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + 0.2F), false);
            assertFalse(exact(boosted), describe(boosted));
        }
    }

    @Test
    void aScaledPlayerLandsOnABlockOnlyItsScaledBoxReaches() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            attribute(f, "minecraft:scale", 1.0, 1.0);
            // At scale 2 the box is 1.2 wide: from x = 1.6 it reaches the block at x = 2, a 0.6 box would not.
            f.place(new Vec3(1.6, FLOOR_Y + 1.05, 0.5), new Vec3(0, -0.0784000015258789, 0));

            f.input(NONE);
            assertExact(f.tick(new Vec3(1.6, FLOOR_Y + 1, 0.5), true));
        }
    }

    @Test
    void aScaledPlayerRidingAPigIsPredicted() throws Exception {
        try (var f = new JavaTickFixture()) {
            // Two blocks high: the ridden pig's step height of 1 cannot climb them.
            for (int x = -1; x <= 1; x++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            attribute(f, "minecraft:scale", 1.0, 1.0);
            f.player
                    .getInventory()
                    .inventory
                    .setHeldItem(ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
            // Pig#getRiddenInput always walks forward, here into the wall the pig stands against.
            Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.55);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.PIG, seat, 0, 0, 0);
            ((PacketEntityRideable) f.player.compensatedEntities.getEntity(100)).hasSaddle = true;
            ride(f, 100);

            f.input(NONE);
            assertExact(f.rideTick(seat, true));
            assertExact(f.rideTick(seat, true));
        }
    }

    @Test
    void aScaledPlayerRidingABoatIsPredicted() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:scale", 1.0, 1.0);
            Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
            ride(f, 100);

            f.input(NONE);
            assertExact(f.rideTick(seat, true));
            assertExact(f.rideTick(seat, true));
        }
    }

    /** A set_passengers seating the player on {@code vehicle}, answered through its ping. */
    private static void ride(JavaTickFixture f, int vehicle) {
        var packet = new ClientboundSetPassengers(vehicle, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void attribute(JavaTickFixture f, String name, double base, double multipliedTotal) {
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot(
                        name,
                        base,
                        List.of(new AttributeModifier(
                                "test:modifier", multipliedTotal, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)))));
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
