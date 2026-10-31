package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A size 2 Shulker attached to the wall west of a cobweb opens east, after the player in ClientLevel#tickEntities. Its
 * Shulker#onPeekAmountChange moves the player with Entity#move(MoverType.SHULKER), which from 1.21.5 records the
 * movement in Entity#movementThisTick; the next travel's Entity#applyEffectsFromBlocks replays it before its own. When
 * the peek carries the player's west face out of the cobweb's cell, only that recorded movement still meets the web:
 * its makeStuckInBlock slows the next peek's push to a quarter.
 */
class JavaShulkerPeekRecordedMoveTest {
    private static final int SHULKER = 100;
    private static final float SIZE = 2.0F;
    private static final Vec3 SHULKER_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final BlockPos WEB = new BlockPos(1, FLOOR_Y, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_5", "V_1_21_11", "V_26_3"})
    void theRecordedPeekMoveStillMeetsTheCobwebItLeft(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(WEB, BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
            var shulker = f.player.compensatedEntities.getEntity(SHULKER);
            shulker.onScale(SIZE, 0);
            shulker.onSizeHandled(0);
            shulker.refreshDimensions(f.player);
            var client = new Client(version);
            f.place(new Vec3(client.x, FLOOR_Y, SHULKER_AT.z), new Vec3(0.0, -0.0784000015258789, 0.0));
            f.input(NONE);
            f.pongThrough(attachFace(f, Direction.WEST));
            for (int tick = 0; tick < 3; tick++) assertExact(f, client.tick(), version + " standing " + tick);
            // The peek stops at 0.4, the tick after the one that carries the player out of the web.
            f.pongThrough(peek(f, (byte) 40));
            client.raw = 40;
            for (int tick = 0; tick < 9; tick++) assertExact(f, client.tick(), version + " opening " + tick);
            assertTrue(client.leftWebByPeek, "the scenario must carry the player's west face out of the web");
            assertTrue(client.slowedByRecordedMove == version.isNewerThanOrEquals(ClientVersion.V_1_21_9));
        }
    }

    private static void assertExact(JavaTickFixture f, Report report, String name) {
        var result = f.tick(new Vec3(report.x, FLOOR_Y, SHULKER_AT.z), report.onGround);
        assertTrue(exact(result), name + " x " + report.x + " ground " + report.onGround + ": " + describe(result));
    }

    /** The shulker's DATA_ATTACH_FACE_ID; its proof transaction. */
    private static int attachFace(JavaTickFixture f, Direction face) {
        var entry = new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_ATTACH_FACE, face, ByteBuffer.wrap(new byte[] {
            (byte) WatchableIndexUtil.SHULKER_ATTACH_FACE, 11, (byte) face.ordinal()
        }));
        return metadata(f, new EntityMetadata(SHULKER, List.of(entry)));
    }

    /** The shulker's DATA_PEEK_ID; its proof transaction. */
    private static int peek(JavaTickFixture f, byte peek) {
        return metadata(
                f,
                new EntityMetadata(
                        SHULKER, List.of(EntityMetadata.Entry.byteValue(WatchableIndexUtil.SHULKER_PEEK, peek))));
    }

    private static int metadata(JavaTickFixture f, EntityMetadata packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    record Report(double x, boolean onGround) {}

    /** The client: a player without input on the floor, its west face 0.01 inside the cobweb's cell. */
    private static final class Client {
        private final ClientVersion version;
        double x = 2.0 - 0.01 + (double) 0.3F;
        byte raw;
        float peek;
        boolean stuck;
        double velocityX;
        // Whether the last Entity#move cleared deltaMovement with the stuck multiplier.
        boolean stopped;
        boolean leftWebByPeek;
        boolean slowedByRecordedMove;
        // Entity#movementThisTick: the start of the one recorded peek move, if any.
        Double recordedFrom;

        Client(ClientVersion version) {
            this.version = version;
        }

        /** One client tick: the x and onGround the client reports. */
        Report tick() {
            // The idle travel's Entity#move clears the multiplier. It lands unless a stuck peek move cleared
            // deltaMovement after the last travel's gravity, leaving this move no Y to collide with.
            boolean onGround = !stopped;
            if (!stuck) x += velocityX;
            velocityX = stuck ? 0.0 : velocityX * (double) (0.6F * 0.91F);
            stopped = false;
            stuck = false;
            // Entity#applyEffectsFromBlocks over the recorded peek move, then the travel's own. Before 1.21.9
            // BlockGetter#forEachBlockIntersectedBetween visits only the box at the end of a move this short.
            boolean inWeb = inWeb(x)
                    || recordedFrom != null
                            && version.isNewerThanOrEquals(ClientVersion.V_1_21_9)
                            && inWeb(recordedFrom);
            if (inWeb) stuck = true;
            if (inWeb && !inWeb(x)) slowedByRecordedMove = true;
            recordedFrom = null;
            if (version.isOlderThan(ClientVersion.V_26_3)) {
                Report report = new Report(x, onGround);
                shulkerTick();
                return report;
            }
            boolean pushed = shulkerTick();
            return new Report(x, onGround && !pushed);
        }

        /** Shulker#tick: updatePeekAmount and onPeekAmountChange's Entity#move(MoverType.SHULKER) east. */
        private boolean shulkerTick() {
            // LivingEntity#aiStep's pushEntities runs first, with the box of the peek before this tick's update: once
            // a slowed peek leaves the player inside the shell, Entity#push moves the player 0.05 away from its centre.
            if (x - 0.3F < 0.5 + SIZE * 0.5 + (double) physical(peek) * SIZE) {
                double xa = SHULKER_AT.x - x;
                double dd = Math.sqrt(Math.abs(xa));
                velocityX -= xa / dd * Math.min(1.0, 1.0 / dd) * 0.05F;
            }
            float old = peek;
            float target = raw * 0.01F;
            if (peek == target) return false;
            peek = peek > target
                    ? CultMath.clamp(peek - 0.05F, target, 1.0F)
                    : CultMath.clamp(peek + 0.05F, 0.0F, target);
            float push = (physical(peek) - physical(old)) * SIZE;
            // Shulker#getProgressDeltaAabb: x from 1.5 + 2 * old to 1.5 + 2 * new physical peek.
            double west = 0.5 + SIZE * 0.5 + (double) physical(old) * SIZE;
            double east = 0.5 + SIZE * 0.5 + (double) physical(peek) * SIZE;
            if (push <= 0.0F || !(x - 0.3F < east && x + 0.3F > west)) return false;
            // Entity#move scales a move by the stuck multiplier, then clears it.
            double move = stuck ? push * 0.25 : push;
            if (stuck) velocityX = 0.0;
            stopped = stuck;
            stuck = false;
            recordedFrom = x;
            if (inWeb(x) && !inWeb(x + move)) leftWebByPeek = true;
            x += move;
            return true;
        }

        /** Entity#checkInsideBlocks' deflated box meets the cobweb's cell. */
        private static boolean inWeb(double x) {
            return x - 0.3F + 1.0E-5 < WEB.getX() + 1 && x + 0.3F - 1.0E-5 > WEB.getX();
        }

        private float physical(float amount) {
            return 0.5F - VanillaMath.sin(version, (0.5F + amount) * (float) Math.PI) * 0.5F;
        }
    }
}
