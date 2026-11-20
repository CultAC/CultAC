package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A client standing on the floor of a pool three blocks deep, holding forward and shift. LocalPlayer#aiStep crouches
 * it unless Entity#updateSwimming swam it, and LocalPlayer#modifyInput scales a crouching input by sneaking_speed.
 */
class JavaLiquidSneakInputTest {
    private static final ServerboundPlayerInput FORWARD_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_LEFT_SNEAK =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_SNEAK_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final List<ClientVersion> CLIENTS = List.of(ClientVersion.V_1_21_2, ClientVersion.V_26_3);
    private static final float YAW = 30.0F;

    @Test
    void aCrouchingWaderMovesAtTheSneakingInput() throws Exception {
        for (ClientVersion client : CLIENTS) {
            for (BlockDefinition fluid : List.of(BlockIds.WATER, BlockIds.LAVA)) {
                walk(client, fluid, 0.3F, false);
            }
        }
    }

    @Test
    void aCrouchingWaderAtTheFullInputIsNoSneakSlow() throws Exception {
        for (ClientVersion client : CLIENTS) {
            for (BlockDefinition fluid : List.of(BlockIds.WATER, BlockIds.LAVA)) {
                walk(client, fluid, 1.0F, true);
            }
        }
    }

    /** A sprint-swimmer holding shift is not crouching, so its input keeps the full scale. */
    @Test
    void aSwimmerHoldingShiftMovesAtTheFullInput() throws Exception {
        for (ClientVersion client : CLIENTS) {
            try (var f = fixture(client, BlockIds.WATER)) {
                rotate(f, 20.0F);
                Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
                Vec3 velocity = Vec3.ZERO;
                f.place(position, velocity);
                f.input(FORWARD_SNEAK_SPRINT);
                var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
                new PacketEntityAction()
                        .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
                for (int tick = 0; tick < 4; tick++) {
                    Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SNEAK_SPRINT, 1.0F, 0.02F, YAW));
                    position = position.add(delta.x, 0.0, delta.z);
                    PredictionResult result = f.tick(position, true);
                    assertTrue(exact(result), client + " tick " + tick + ": " + describe(result));
                    // LivingEntity#travelInWater's sprinting slowDown.
                    velocity = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
                }
            }
        }
    }

    /** Under a one-block ceiling the crouching pose does not fit, so LocalPlayer#aiStep does not crouch the wader. */
    @Test
    void aWaderWithNoRoomToCrouchMovesAtTheFullInput() throws Exception {
        for (ClientVersion client : CLIENTS) {
            try (var f = fixture(client, BlockIds.WATER)) {
                for (int x = -8; x <= 8; x++) {
                    for (int z = -8; z <= 8; z++) {
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, FLOOR_Y + 1, z), BlockIds.STONE.defaultState());
                    }
                }
                for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
                rotate(f, 0.0F);
                Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
                Vec3 velocity = Vec3.ZERO;
                f.place(position, velocity);
                f.input(FORWARD_SNEAK);
                for (int tick = 0; tick < 4; tick++) {
                    Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SNEAK, 1.0F, 0.02F, YAW));
                    position = position.add(delta.x, 0.0, delta.z);
                    PredictionResult result = f.tick(position, true);
                    assertTrue(exact(result), client + " tick " + tick + ": " + describe(result));
                    velocity = new Vec3(delta.x * 0.8F, 0.0, delta.z * 0.8F);
                }
            }
        }
    }

    /**
     * A Depth Strider III client crouch-walking forward and left across a river bed. Each block's level falls by one
     * toward +x, so FlowingFluid#getFlow is +x everywhere under the player and Entity#updateFluidInteraction pushes it
     * by the 0.014 water current; the engine grants that current as a radius, which leaves the crouching input a
     * residual outside the sneaking input square.
     */
    @Test
    void aDepthStriderCrouchAcrossACurrentIsClean() throws Exception {
        for (ClientVersion client : CLIENTS) {
            try (var f = new JavaTickFixture()) {
                setField(CultPlayer.class, f.player, "resolvedClientVersion", client);
                BlockRegistry registry = DataTables.defaults().registry();
                int water = BlockIds.WATER.defaultState();
                for (int x = -8; x <= 8; x++) {
                    int level = Math.max(1, Math.min(7, x + 4));
                    for (int z = -8; z <= 8; z++) {
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, FLOOR_Y, z), registry.with(water, "level", Integer.toString(level)));
                    }
                }
                for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
                f.player.compensatedEntities.updateAttributes(
                        f.player.entityID,
                        List.of(new AttributeSnapshot(
                                "minecraft:water_movement_efficiency",
                                0.0,
                                List.of(new AttributeModifier(
                                        "test:depth_strider", 1.0, AttributeModifier.Operation.ADD_VALUE)))));
                float yaw = -10.0F;
                f.player.xRot = yaw;
                f.player.yRot = 0.0F;
                Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
                Vec3 velocity = Vec3.ZERO;
                f.place(position, velocity);
                f.input(FORWARD_LEFT_SNEAK);
                Vec3 current = new Vec3(0.014, 0.0, 0.0);
                for (int tick = 0; tick < 3; tick++) {
                    // LivingEntity#travelInWater with efficiency 1 on the ground: getSpeed() and slowDown 0.54600006F.
                    Vec3 input = ClientMoveInput.of(f, FORWARD_LEFT_SNEAK, tick == 0 ? 1.0F : 0.3F, 0.1F, yaw);
                    Vec3 delta = velocity.add(current).add(input);
                    position = position.add(delta.x, 0.0, delta.z);
                    PredictionResult result = f.tick(position, true);
                    assertTrue(exact(result), client + " tick " + tick + ": " + describe(result));
                    velocity = new Vec3(delta.x * 0.54600006F, 0.0, delta.z * 0.54600006F);
                }
            }
        }
    }

    /**
     * Shift is new on the first tick, so LocalPlayer#aiStep crouches from the second; {@code scale} is the input scale
     * the client moves at once crouching.
     */
    private static void walk(ClientVersion client, BlockDefinition fluid, float scale, boolean cheats) throws Exception {
        boolean lava = fluid == BlockIds.LAVA;
        // LivingEntity#travelInWater's non-sprinting slowDown and travelInLava's horizontal 0.5.
        double slowDown = lava ? 0.5D : 0.8F;
        try (var f = fixture(client, fluid)) {
            rotate(f, 0.0F);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 velocity = Vec3.ZERO;
            f.place(position, velocity);
            f.input(FORWARD_SNEAK);
            for (int tick = 0; tick < 4; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SNEAK, tick == 0 ? 1.0F : scale, 0.02F, YAW));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                String where = client + (lava ? " lava" : " water") + " tick " + tick + ": " + describe(result);
                if (cheats && tick > 0) {
                    assertTrue(result.hasFlag(NoSneakSlow.class), where);
                } else {
                    assertTrue(exact(result), where);
                }
                velocity = new Vec3(delta.x * slowDown, 0.0, delta.z * slowDown);
            }
        }
    }

    private static JavaTickFixture fixture(ClientVersion client, BlockDefinition fluid) throws Exception {
        var f = new JavaTickFixture();
        setField(CultPlayer.class, f.player, "resolvedClientVersion", client);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), fluid.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static void rotate(JavaTickFixture f, float pitch) {
        f.player.xRot = YAW;
        f.player.yRot = pitch;
    }
}
