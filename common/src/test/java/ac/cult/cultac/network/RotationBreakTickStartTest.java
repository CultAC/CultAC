package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.breaking.RotationBreak;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick picks its hit result with pick(1.0F) before handleKeybinds sends the dig, so the dig's ray starts at
 * the eye the client tick started at; LocalPlayer#sendPosition sends that tick's look and moved position after the
 * dig. The digs are typefast's 26.3 capture from issue #79, moved by whole blocks onto the fixture's floor.
 */
class RotationBreakTickStartTest {
    // seq 4628 PosRot, seq 4701 START_DESTROY_BLOCK, seq 4705 PosRot: the look hits from the tick's first eye only.
    private static final Dig FEET_LEVEL_DIG = new Dig(
            new Vec3(60.63143521181032, 84.0, 184.94494961128746),
            -2056.1487f,
            44.237926f,
            new BlockPos(59, 84, 185),
            Direction.UP,
            34,
            new Vec3(60.47985051580859, 84.0, 184.81884542428588),
            -2075.2717f,
            45.714138f);

    // seq 9728 PosRot, seq 9798 START_DESTROY_BLOCK, seq 9800 PosRot.
    private static final Dig SIDE_FACE_DIG = new Dig(
            new Vec3(63.1230786878772, 83.0, 178.82321911071017),
            -1917.2491f,
            27.664295f,
            new BlockPos(65, 83, 178),
            Direction.WEST,
            77,
            new Vec3(63.25001266439123, 83.0, 178.5977850246453),
            -1910.4053f,
            23.034397f);

    @Test
    void aDigRaysFromTheEyeItsTickStartedAt() throws Exception {
        assertEquals(0, replay(FEET_LEVEL_DIG));
        assertEquals(0, replay(SIDE_FACE_DIG));
    }

    @Test
    void aDigOnlyTheMovedEyeLooksAtStillFlags() throws Exception {
        Dig walkedBack = new Dig(
                FEET_LEVEL_DIG.sent(),
                FEET_LEVEL_DIG.startYaw(),
                FEET_LEVEL_DIG.startPitch(),
                FEET_LEVEL_DIG.target(),
                FEET_LEVEL_DIG.face(),
                FEET_LEVEL_DIG.sequence(),
                FEET_LEVEL_DIG.tickStart(),
                FEET_LEVEL_DIG.yaw(),
                FEET_LEVEL_DIG.pitch());
        assertEquals(1, replay(walkedBack));
    }

    /** The movement ending the tick before the dig, the dig, and the movement its own tick sends. */
    private record Dig(
            Vec3 tickStart,
            float startYaw,
            float startPitch,
            BlockPos target,
            Direction face,
            int sequence,
            Vec3 sent,
            float yaw,
            float pitch) {}

    private static double replay(Dig dig) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            RotationBreak check = f.player.checkManager.getCheck(RotationBreak.class);
            check.setEnabled(true);
            f.player.setExperimentalChecks(true);

            int dx = -(int) Math.floor(dig.tickStart().x);
            int dy = JavaTickFixture.FLOOR_Y - (int) Math.floor(dig.tickStart().y);
            int dz = -(int) Math.floor(dig.tickStart().z);
            BlockPos target = new BlockPos(
                    dig.target().getX() + dx,
                    dig.target().getY() + dy,
                    dig.target().getZ() + dz);
            f.player.compensatedWorld.updateBlock(target, BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 start = dig.tickStart().add(dx, dy, dz);
            f.place(start, Vec3.ZERO);
            move(f, start, dig.startYaw(), dig.startPitch());
            var action =
                    new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, target, dig.face(), dig.sequence());
            new PacketPlayerDigging()
                    .onPlayerAction(f.event(ServerboundPackets.PLAYER_ACTION, action), f.player, action);
            move(f, dig.sent().add(dx, dy, dz), dig.yaw(), dig.pitch());
            return check.getViolations();
        }
    }

    private static void move(JavaTickFixture f, Vec3 to, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, true, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
    }
}
