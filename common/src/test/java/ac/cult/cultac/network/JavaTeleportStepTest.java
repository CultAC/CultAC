package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Recorded clients frozen by a server (movement_speed and jump_strength multiplied to nothing) that walk into a half
 * block riser they stand flush against. The walk steps onto the riser and the server teleports the player back to
 * where it stood. The next client tick repeats the step on the stale onGround (Entity#collide), ends within 2.0E-4 of
 * the step's position, which xLast still holds, and so sends no position (LocalPlayer#sendPosition); the tick after
 * that lands on the riser it overlaps.
 */
class JavaTeleportStepTest {
    private static final int STONE_SLAB =
            DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
    // The default state faces north: the upper half covers the block's -z half.
    private static final int STONE_STAIRS =
            DataTables.defaults().registry().block("minecraft:stone_stairs").defaultState();
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_HELD =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final AttributeModifier FREEZE_SPEED =
            new AttributeModifier("test:freeze_speed", -0.9999D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier FREEZE_JUMP =
            new AttributeModifier("test:freeze_jump", -1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier SPRINTING = new AttributeModifier(
            "minecraft:sprinting", 0.30000001192092896D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    /** A sprinting 26.3 client sends StatusOnly after the teleport's acceptance. */
    @Test
    void aFrozenSprintRepeatsAStairStepAfterATeleportAndLandsOnIt() throws Exception {
        Vec3 stood = new Vec3(-11.128131489107535, 74.5, -17.19999998807907);
        float yaw = 168.30001831054688F;
        float pitch = 12.000009536743164F;
        try (var f = new JavaTickFixture()) {
            stairRiser(f, -12, -11);
            standFrozen(f, stood, yaw, pitch);
            answer(f, send(f, 3, stood, yaw, pitch), true);
            assertExact("accept", f, move(f, stood, yaw, pitch, false));
            assertExact("stand", f, status(f, true));

            f.input(SPRINT_FORWARD);
            command(f, PlayerCommandAction.START_SPRINTING);
            assertExact("step", f, move(f, new Vec3(-11.128134072713554, 75.0, -17.200012463359208), yaw, pitch, true));

            attributes(f, new AttributeSnapshot("minecraft:movement_speed", 0.10000000149011612D, List.of(FREEZE_SPEED, SPRINTING)));
            answer(f, send(f, 4, stood, yaw, pitch), true);
            assertExact("repeated step", f, status(f, false));
            assertStep(f);

            assertExact("air", f, move(f, new Vec3(-11.133301284186262, 75.0, -17.224963020898386), yaw, pitch, true));
            f.input(SPRINT_HELD);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            assertExact("land", f, move(f, new Vec3(-11.138003446761942, 75.0, -17.24766802891339), yaw, pitch, true));
        }
    }

    /** A 26.3 client turning its camera sends Rot after the acceptance; its walk is shorter than 1.0E-5. */
    @Test
    void aFrozenWalkUnder1e5RepeatsAStairStepAfterATeleportAndSendsRot() throws Exception {
        Vec3 stood = new Vec3(-11.25820577998942, 74.5, -17.19999998807907);
        float yaw = -213.59327697753906F;
        float pitch = 33.83153533935547F;
        try (var f = new JavaTickFixture()) {
            stairRiser(f, -12, -11);
            standFrozen(f, stood, -185.718F, 34.30803F);
            answer(f, send(f, 3, stood, -185.71800231933594F, 34.30802917480469F), true);
            assertExact("accept", f, move(f, stood, -178.68960571289062F, 35.73753356933594F, false));
            f.input(NONE);
            float[][] turns = {
                {-169.15957641601562F, 37.16703796386719F},
                {-167.37269592285156F, 37.5244140625F},
                {-179.64259338378906F, 37.16703796386719F},
                {-200.84686279296875F, 34.069786071777344F},
                {-205.4927520751953F, 33.83153533935547F},
                {-208.82826232910156F, 33.593284606933594F},
                {-212.87852478027344F, 33.83153533935547F},
                {-212.99765014648438F, 33.83153533935547F},
            };
            for (float[] turn : turns) assertExact("turn", f, rot(f, turn[0], turn[1], true));

            f.input(FORWARD);
            assertExact("step", f, move(f, new Vec3(-11.258211201901451, 75.0, -17.200008151587713), yaw, pitch, true));

            Pending teleport = send(f, 4, stood, yaw, pitch);
            float[][] lookingAround = {
                {-214.90365600585938F, 32.163780212402344F},
                {-214.90365600585938F, 28.59002685546875F},
                {-211.806396484375F, 25.373647689819336F},
                {-205.85012817382812F, 22.038143157958984F},
                {-202.39549255371094F, 20.370389938354492F},
                {-201.3233642578125F, 20.132139205932617F},
            };
            for (float[] turn : lookingAround) assertExact("on the step", f, rot(f, turn[0], turn[1], true));
            answer(f, teleport, true);
            assertExact("repeated step", f, rot(f, -213.3550262451172F, 33.83153533935547F, false));
            assertStep(f);

            assertExact(
                    "air",
                    f,
                    move(f, new Vec3(-11.268437075115646, 75.0, -17.216729144142743), -211.4490203857422F, 35.975791931152344F, true));
            send(f, 5, stood, yaw, pitch);
            assertExact(
                    "land",
                    f,
                    move(f, new Vec3(-11.277746497112382, 75.0, -17.231954241126164), -203.11026000976562F, 40.859920501708984F, true));
        }
    }

    /** A 26.2 client echoes the teleport with PosRot, then sends StatusOnly; its walk is shorter than 1.0E-5. */
    @Test
    void aFrozenWalkUnder1e5RepeatsASlabStepAfterAnEchoedTeleport() throws Exception {
        Vec3 stood = new Vec3(-10.338904473747842, 74.0, -16.69999998807907);
        float yaw = 160.80030822753906F;
        float pitch = 3.7500076293945312F;
        try (var f = new JavaTickFixture(ProtocolVersion.V26_2, ClientVersion.V_26_2)) {
            slabRiser(f, -11, -10);
            standFrozen(f, stood, 162.75029F, 4.8000083F);
            assertExact("turn", f, rot(f, 161.55029296875F, 4.0500078201293945F, true));
            assertExact("turn", f, rot(f, 160.95030212402344F, 3.7500076293945312F, true));
            assertExact("settle", f, move(f, stood, yaw, pitch, true));

            f.input(FORWARD);
            assertExact("step", f, move(f, new Vec3(-10.338907697303496, 74.5, -16.700009242736513), yaw, pitch, true));

            answer(f, send(f, 3, stood, yaw, pitch), false);
            echo(f, stood, yaw, pitch);
            assertExact("repeated step", f, status(f, false));
            assertStep(f);

            assertExact("land", f, move(f, new Vec3(-10.345354808629207, 74.5, -16.718518557673082), yaw, pitch, true));
        }
    }

    private record Pending(int id, int leading, Vec3 target, float yaw, float pitch) {}

    /** A riser whose top is 0.5 above a slab floor: stairs at z -18 face north over slabs at z -17. */
    private static void stairRiser(JavaTickFixture f, int... xs) {
        loadAround(f);
        for (int x : xs) {
            f.player.compensatedWorld.updateBlock(new BlockPos(x, 74, -18), STONE_STAIRS);
            f.player.compensatedWorld.updateBlock(new BlockPos(x, 74, -17), STONE_SLAB);
        }
        settleBlocks(f);
    }

    /** A riser whose top is 0.5 above a full block floor: slabs at z -18 beside stone at y 73. */
    private static void slabRiser(JavaTickFixture f, int... xs) {
        loadAround(f);
        for (int x : xs) {
            f.player.compensatedWorld.updateBlock(new BlockPos(x, 74, -18), STONE_SLAB);
            f.player.compensatedWorld.updateBlock(new BlockPos(x, 73, -18), BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(x, 73, -17), BlockIds.STONE.defaultState());
        }
        settleBlocks(f);
    }

    private static void loadAround(JavaTickFixture f) {
        for (int z = -2; z <= -1; z++) f.player.compensatedWorld.ensureValidationChunkLoaded(-1, z);
    }

    private static void settleBlocks(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void standFrozen(JavaTickFixture f, Vec3 at, float yaw, float pitch) throws Exception {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        attributes(
                f,
                new AttributeSnapshot("minecraft:movement_speed", 0.10000000149011612D, List.of(FREEZE_SPEED)),
                new AttributeSnapshot("minecraft:jump_strength", 0.41999998688697815D, List.of(FREEZE_JUMP)));
        f.place(at, new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(true));
        f.player.xRot = f.player.lastTickXRot = yaw;
        f.player.yRot = pitch;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", false);
    }

    private static void attributes(JavaTickFixture f, AttributeSnapshot... snapshots) {
        f.player.compensatedEntities.updateAttributes(f.player.entityID, List.of(snapshots));
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
    }

    /** A player_position at an absolute target and rotation, not yet answered. */
    private static Pending send(JavaTickFixture f, int id, Vec3 target, float yaw, float pitch) {
        var packet = new ClientboundPlayerPosition(
                id, new Vec3d(target.x, target.y, target.z), new Vec3d(0, 0, 0), yaw, pitch, Set.of());
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        return new Pending(id, leading, target, yaw, pitch);
    }

    /** The acceptance: with the position from 26.3 on, ID-only before. */
    private static void answer(JavaTickFixture f, Pending teleport, boolean withPosition) {
        f.pongThrough(teleport.leading());
        Vec3d at = withPosition ? new Vec3d(teleport.target().x, teleport.target().y, teleport.target().z) : null;
        var packet = new ServerboundAcceptTeleportation(teleport.id(), at, teleport.yaw(), teleport.pitch());
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the teleport acceptance was rejected");
    }

    /** The PosRot a 26.2 client's ClientPacketListener#handleMovePlayer sends with the acceptance. */
    private static void echo(JavaTickFixture f, Vec3 at, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(at.x, at.y, at.z, yaw, pitch, false, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static PredictionResult move(JavaTickFixture f, Vec3 to, float yaw, float pitch, boolean onGround) {
        return tick(f, new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, onGround, false, true, true));
    }

    private static PredictionResult rot(JavaTickFixture f, float yaw, float pitch, boolean onGround) {
        return tick(f, new ServerboundMovePlayer(0, 0, 0, yaw, pitch, onGround, false, false, true));
    }

    private static PredictionResult status(JavaTickFixture f, boolean onGround) {
        return tick(f, new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, onGround, false, false, false));
    }

    private static PredictionResult tick(JavaTickFixture f, ServerboundMovePlayer packet) {
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static void assertExact(String tick, JavaTickFixture f, PredictionResult result) {
        assertTrue(exact(result), tick + " " + describe(result));
    }

    /** The repeated step is the collision step of the walk, not a jump: the frozen jump_strength cannot jump. */
    private static void assertStep(JavaTickFixture f) {
        Vec3 accepted = f.player.checkManager.getSimulationProcessor().getLastPrediction().getAcceptedClosestToTarget();
        assertTrue(accepted instanceof PredVector step && step.hasReason("step") && !step.isJump(), "repeated step " + accepted);
    }
}
