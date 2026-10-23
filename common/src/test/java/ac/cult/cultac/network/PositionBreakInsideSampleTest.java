package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.breaking.PositionBreakA;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * VoxelShape#clip reports an inside hit when the point 0.001 of the way along the pick ray is in the
 * outline, with the face opposite the view's dominant axis. An eye just south of a cobweb looking
 * mostly west can therefore legally START on its EAST face.
 */
class PositionBreakInsideSampleTest {
    private static final BlockPos COBWEB = new BlockPos(0, 63, 0);

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    /** The captured boundary: the eye is 0.0002586 past the movement threshold, the 0.0045 sample is inside. */
    @Test
    void anEyeWhoseRaySampleEntersTheBlockMayHitTheFaceOppositeItsView() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.startOnEastFaceFrom(1.0 + 0.0002 + 0.0002586);
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void anEyeBeyondTheRaySampleStillCannotHitTheFarFace() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.startOnEastFaceFrom(1.0 + 0.0002 + 0.0046);
            assertEquals(1, f.check.getViolations());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordReceiveFixture record = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final CultPlayer player = record.player;
        final PositionBreakA check;

        Fixture() {
            record.user.bind(mock(PlatformPlayer.class));
            check = player.checkManager.getCheck(PositionBreakA.class);
        }

        /** No position packet arrived, so the eye box is widened by the 0.0002 movement threshold. */
        void startOnEastFaceFrom(double z) {
            player.x = 0.5;
            player.y = 62.0;
            player.z = z;
            var action = PlayerAction.START_DESTROY_BLOCK;
            player.checkManager.dispatchEarlyReceive(new PacketReceiveEvent<>(
                    player.user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.PLAYER_ACTION,
                    new ServerboundPlayerAction(action, COBWEB, Direction.EAST, 1)));
            player.checkManager.onBlockBreak(new BlockBreak(
                    player,
                    COBWEB,
                    Direction.EAST,
                    Direction.EAST.ordinal(),
                    action,
                    1,
                    BlockIds.COBWEB.defaultState()));
        }

        @Override
        public void close() {
            record.close();
        }
    }
}
