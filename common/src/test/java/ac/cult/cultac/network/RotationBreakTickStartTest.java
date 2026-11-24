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

    // A 1.21.11 capture: seq 481 PosRot, seq 482 START_DESTROY_BLOCK beyond the block range, seq 482 PosRot,
    // seq 483 START_DESTROY_BLOCK on the next block, seq 483 PosRot.
    private static final Dig OUT_OF_RANGE_DIG = new Dig(
            new Vec3(1002.3012328790371, 62.121296840539195, 993.8831461881388),
            111.9f,
            13.8f,
            new BlockPos(998, 61, 992),
            Direction.UP,
            4,
            new Vec3(1002.129997280572, 62.0, 993.8147958262203),
            111.6f,
            21.15f);

    private static final Dig NEXT_DIG = new Dig(
            OUT_OF_RANGE_DIG.sent(),
            OUT_OF_RANGE_DIG.yaw(),
            OUT_OF_RANGE_DIG.pitch(),
            new BlockPos(999, 61, 992),
            Direction.UP,
            5,
            new Vec3(1001.8828703, 62.42, 993.7170025),
            111.3f,
            27.0f);

    @Test
    void aDigAfterAFlaggedDigWhoseOwnLookHitsStaysClean() throws Exception {
        assertEquals(1, replay(OUT_OF_RANGE_DIG, NEXT_DIG));
    }

    @Test
    void aDigAfterAFlaggedDigBehindThePlayerFlags() throws Exception {
        Dig behind = new Dig(
                NEXT_DIG.tickStart(),
                NEXT_DIG.startYaw(),
                NEXT_DIG.startPitch(),
                new BlockPos(1005, 61, 995),
                NEXT_DIG.face(),
                NEXT_DIG.sequence(),
                NEXT_DIG.sent(),
                NEXT_DIG.yaw(),
                NEXT_DIG.pitch());
        assertEquals(2, replay(OUT_OF_RANGE_DIG, behind));
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

    private static double replay(Dig... digs) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            RotationBreak check = f.player.checkManager.getCheck(RotationBreak.class);
            check.setEnabled(true);
            f.player.setExperimentalChecks(true);

            Dig first = digs[0];
            int dx = -(int) Math.floor(first.tickStart().x);
            int dy = JavaTickFixture.FLOOR_Y - (int) Math.floor(first.tickStart().y);
            int dz = -(int) Math.floor(first.tickStart().z);
            for (Dig dig : digs) {
                f.player.compensatedWorld.updateBlock(shifted(dig, dx, dy, dz), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();

            Vec3 start = first.tickStart().add(dx, dy, dz);
            f.place(start, Vec3.ZERO);
            move(f, start, first.startYaw(), first.startPitch());
            for (Dig dig : digs) {
                var action = new ServerboundPlayerAction(
                        PlayerAction.START_DESTROY_BLOCK, shifted(dig, dx, dy, dz), dig.face(), dig.sequence());
                new PacketPlayerDigging()
                        .onPlayerAction(f.event(ServerboundPackets.PLAYER_ACTION, action), f.player, action);
                move(f, dig.sent().add(dx, dy, dz), dig.yaw(), dig.pitch());
            }
            return check.getViolations();
        }
    }

    private static BlockPos shifted(Dig dig, int dx, int dy, int dz) {
        return new BlockPos(dig.target().getX() + dx, dig.target().getY() + dy, dig.target().getZ() + dz);
    }

    private static void move(JavaTickFixture f, Vec3 to, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, true, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
    }
}
