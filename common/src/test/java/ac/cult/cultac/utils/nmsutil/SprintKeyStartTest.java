package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** LocalPlayer#aiStep's sprint-key start is certain only once every canStartSprinting refusal is ruled out. */
class SprintKeyStartTest {
    private static final KnownInput SPRINT_FORWARD = new KnownInput(true, false, false, false, false, false, true);

    @Test
    void theHeldSprintKeyStartsAForwardPlayerOnOpenGround() {
        assertTrue(startsAfter(player -> {}));
    }

    @Test
    void theStartNeedsTheSprintKeyAndForwardImpulse() {
        assertFalse(startsAfter(player ->
                player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, false)));
        assertFalse(startsAfter(player ->
                player.packetStateData.knownInput = new KnownInput(true, true, false, false, false, false, true)));
        assertFalse(startsAfter(player ->
                player.packetStateData.knownInput = new KnownInput(false, false, true, false, false, false, true)));
    }

    @Test
    void hungerRefusesTheStartUnlessThePlayerMayFly() {
        assertFalse(startsAfter(player -> player.food = 6));
        assertTrue(startsAfter(player -> {
            player.food = 6;
            player.canFly = true;
        }));
    }

    @Test
    void blindnessGlidingAndCrawlingRefuseTheStart() {
        assertFalse(startsAfter(
                player -> player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.BLINDNESS, 0)));
        assertFalse(startsAfter(player -> player.isGliding = true));
        assertFalse(startsAfter(player -> player.packetStateData.glidingBeforeClientAction = true));
        assertFalse(startsAfter(player -> player.pose = Pose.SWIMMING));
    }

    @Test
    void lastTicksShiftKeyOrALowCeilingCanCrouchThePlayer() {
        assertFalse(startsAfter(player -> player.packetStateData.lastTickShift = true));
        assertFalse(startsAfter(player -> place(player, 0, 65, 0, "minecraft:stone")));
    }

    @Test
    void waterAtTheMovementStartRefusesTheStart() {
        assertFalse(startsAfter(player -> place(player, 0, 64, 0, "minecraft:water")));
    }

    private static void place(CultPlayer player, int x, int y, int z, String block) {
        player.compensatedWorld.updateBlock(
                x, y, z, DataTables.defaults().registry().block(block).defaultState());
    }

    private static boolean startsAfter(Consumer<CultPlayer> setup) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Sprint_Key"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.x = 0.5D;
            player.y = 64.0D;
            player.z = 0.5D;
            player.packetStateData.knownInput = SPRINT_FORWARD;
            setup.accept(player);
            return SprintKeyStart.certain(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.runScheduledPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }
}
