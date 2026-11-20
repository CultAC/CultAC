package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.elytra.ElytraF;
import ac.cult.cultac.checks.impl.elytra.ElytraI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A glide started on the tick of a riptide release on the ground. A spin of TridentItem#releaseUsing runs in
 * Minecraft#handleKeybinds and its lift leaves onGround false; LivingEntity#baseTick then refreshes the fluids at the
 * lifted box, and LocalPlayer#aiStep's Player#tryToStartFallFlying reads both before LivingEntity#travelFallFlying.
 */
class JavaRiptideGlideStartTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 CARRIED = new Vec3(0, (0.0 - 0.08) * 0.98F, 0);
    private static final float LIFT = 1.1999999F;

    @ParameterizedTest
    @CsvSource({"true, -90", "true, -30", "false, -90", "false, -30"})
    void aSpinOnTheGroundStartsTheGlide(boolean inWater, float pitch) throws Exception {
        try (var f = new JavaTickFixture()) {
            stand(f, START, CARRIED);
            Vec3 pushed = release(f, pitch, inWater);
            assertStarted(f, startGlide(f));
            Vec3 glided = glide(f, cut(CARRIED.add(pushed)), pitch);
            assertExact(f.tick(START.add(new Vec3(0, LIFT, 0)).add(glided), false));
        }
    }

    /** Without the spin the player stays on the ground, where LivingEntity#canGlide fails. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aGlideAlongTheGroundAfterAReleaseFlags(boolean inWater) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 here = START.add(new Vec3(0, 0, 0.25));
            elytra(f);
            f.place(START, new Vec3(0, CARRIED.y, 0.25));
            f.input(NONE);
            assertExact(f.tick(here, true));
            release(f, 0.0F, inWater);
            startGlide(f);
            Vec3 glided = glide(f, cut(new Vec3(0, CARRIED.y, 0.25 * 0.6F * 0.91F)), 0.0F);
            PredictionResult result = f.tick(here.add(new Vec3(glided.x, 0, glided.z)), true);
            assertFalse(exact(result), describe(result));
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "3, 1"})
    void theLiftLeavesShallowWaterForTheGlide(int depth, double violations) throws Exception {
        try (var f = new JavaTickFixture()) {
            fill(f, BlockIds.WATER, depth);
            stand(f, START, Vec3.ZERO);
            release(f, -90.0F, true);
            startGlide(f);
            assertEquals(violations, f.player.checkManager.getCheck(ElytraI.class).violations, 0.0);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, false", "3, true"})
    void theLiftLeavesShallowLavaForTheGlide(int depth, boolean rejected) throws Exception {
        try (var f = new JavaTickFixture()) {
            fill(f, BlockIds.LAVA, depth);
            elytra(f);
            f.place(START, Vec3.ZERO);
            f.input(NONE);
            f.tick(START, true);
            release(f, -90.0F, false);
            assertEquals(rejected, startGlide(f).isCancelled());
            assertEquals(!rejected, f.player.isGliding);
        }
    }

    private static void fill(JavaTickFixture f, BlockDefinition fluid, int depth) {
        for (int y = 0; y < depth; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + y, 0), fluid.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void elytra(JavaTickFixture f) {
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
    }

    private static void stand(JavaTickFixture f, Vec3 position, Vec3 velocity) {
        elytra(f);
        f.place(position, velocity);
        f.input(NONE);
        assertExact(f.tick(position, true));
    }

    /**
     * A riptide release with the jump key the glide start reads pressed this tick: {@code inWater} when the last move
     * left Entity#wasTouchingWater set, otherwise a release in rain. Returns the push of the spin.
     */
    private static Vec3 release(JavaTickFixture f, float pitch, boolean inWater) {
        f.player.xRot = 0.0F;
        f.player.yRot = pitch;
        f.input(JUMP);
        f.player.packetStateData.riptideLevel = 3;
        f.player.packetStateData.riptideReleaseCertain = inWater;
        f.player.riptideSpinAttackTicks = 20;
        float strength = 3.0F * ((1.0F + 3) / 4.0F);
        float xRot = pitch * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(0.0F) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(0.0F) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (strength / dist), yd * (strength / dist), zd * (strength / dist));
    }

    /** LocalPlayer#aiStep's START_FALL_FLYING through the command listener and the glide-start checks. */
    private static PacketReceiveEvent<ServerboundPlayerCommand> startGlide(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        f.player.checkManager.getCheck(ElytraF.class).onPlayerCommand(event, f.player, packet);
        f.player.checkManager.getCheck(ElytraI.class).onPlayerCommand(event, f.player, packet);
        return event;
    }

    private static void assertStarted(JavaTickFixture f, PacketReceiveEvent<ServerboundPlayerCommand> event) {
        assertFalse(event.isCancelled());
        assertTrue(f.player.isGliding);
        assertEquals(0.0, f.player.checkManager.getCheck(ElytraF.class).violations, 0.0);
        assertEquals(0.0, f.player.checkManager.getCheck(ElytraI.class).violations, 0.0);
    }

    /** LivingEntity#aiStep zeroes a horizontal speed under 0.003 and each axis under 0.003. */
    private static Vec3 cut(Vec3 v) {
        double x = v.x, z = v.z;
        if (x * x + z * z < 9.0E-6) x = z = 0;
        if (Math.abs(x) < 0.003) x = 0;
        if (Math.abs(z) < 0.003) z = 0;
        return new Vec3(x, Math.abs(v.y) < 0.003 ? 0 : v.y, z);
    }

    private static Vec3 glide(JavaTickFixture f, Vec3 v, float pitch) {
        return ElytraTransform.getElytraMovement(f.player, v, ReachUtils.getLook(f.player, 0.0F, pitch), pitch, 0.08);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
