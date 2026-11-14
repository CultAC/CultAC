package ac.cult.cultac.checks.impl.prediction.stage.world;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A shove the engine places only within an envelope moves LocalPlayer#moveTowardsClosestSpace's probes anywhere in it:
 * every push some offset of the envelope gives is one of the tick's pushes.
 */
class BlockPushEnvelopeTest {
    private static final Vec3 START = new Vec3(0.5, 64, 0.5);
    private static final double STEP = 0.01;
    private CultPlayer player;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        channel = new EmbeddedChannel();
        player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Envelope_Test"), channel));
        Field version = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        version.setAccessible(true);
        version.set(player, ClientVersion.V_1_21_11);
        player.gamemode = GameMode.SURVIVAL;
        SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
        player.checkManager.getSimulationProcessor().handleRespawn();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
        player.x = player.lastX = START.x;
        player.y = player.lastY = START.y;
        player.z = player.lastZ = START.z;
        player.boundingBox = GetBoundingBox.getBoundingBoxFromPosAndSize(START.x, START.y, START.z, 0.6F, 1.8F);
    }

    @AfterEach
    void close() {
        player.onRemove();
        channel.runPendingTasks();
        channel.finishAndReleaseAll();
    }

    @Test
    void aShoveAlongOneAxisHoldsEveryPushOfItsPath() throws Exception {
        stone(0, 64, 0);
        stone(1, 64, 0);
        stone(0, 64, -1);
        stone(-1, 65, 1);
        assertHoldsEverySampledPush(new SimpleCollisionBox(-0.51, 0, 0, 0.51, 0, 0));
    }

    @Test
    void aShoveAcrossTwoAxesHoldsEveryPushOfItsArea() throws Exception {
        stone(0, 64, 0);
        stone(1, 64, 0);
        stone(0, 64, 1);
        stone(-1, 64, -1);
        stone(1, 65, -1);
        assertHoldsEverySampledPush(new SimpleCollisionBox(-0.3, 0, -0.25, 0.45, 0, 0.4));
    }

    @Test
    void aVerticalShoveHoldsThePushesOfEveryLayerItCrosses() throws Exception {
        stone(0, 65, 0);
        stone(1, 65, 0);
        stone(0, 63, 0);
        stone(-1, 63, 0);
        assertHoldsEverySampledPush(new SimpleCollisionBox(0, -0.51, 0, 0, 0.51, 0));
    }

    private void assertHoldsEverySampledPush(SimpleCollisionBox envelope) throws Exception {
        SimulationContext context = mock(SimulationContext.class);
        when(context.getStart()).thenReturn(START);
        when(context.getPose()).thenReturn(Pose.STANDING);
        when(context.getScale()).thenReturn(1.0F);
        WorldStageBuilder builder = new WorldStageBuilder();
        Method within = WorldStageBuilder.class.getDeclaredMethod(
                "blockPushesWithin", CultPlayer.class, SimulationContext.class, SimpleCollisionBox.class);
        within.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<BlockPush> held = new HashSet<>((List<BlockPush>) within.invoke(builder, player, context, envelope));
        Method at = WorldStageBuilder.class.getDeclaredMethod(
                "blockPushAt", CultPlayer.class, SimulationContext.class, double.class, double.class, double.class);
        at.setAccessible(true);
        Set<BlockPush> sampled = new HashSet<>();
        for (double x = envelope.minX; x <= envelope.maxX + 1e-9; x += STEP) {
            for (double y = envelope.minY; y <= envelope.maxY + 1e-9; y += STEP) {
                for (double z = envelope.minZ; z <= envelope.maxZ + 1e-9; z += STEP) {
                    sampled.add((BlockPush) at.invoke(builder, player, context, x, y, z));
                }
            }
        }
        assertTrue(sampled.size() > 1, "the envelope crosses pushes: " + sampled);
        assertTrue(held.containsAll(sampled), "held " + held + ", sampled " + sampled);
        Set<BlockPush> corners = new HashSet<>();
        for (double x : new double[] {envelope.minX, envelope.maxX}) {
            for (double y : new double[] {envelope.minY, envelope.maxY}) {
                for (double z : new double[] {envelope.minZ, envelope.maxZ}) {
                    corners.add((BlockPush) at.invoke(builder, player, context, x, y, z));
                }
            }
        }
        assertTrue(corners.size() > 1 || !corners.containsAll(sampled), "corners " + corners);
    }

    private void stone(int x, int y, int z) {
        player.compensatedWorld.applyBlockChangeRawDANGER(
                x, y, z, DataTables.defaults().registry().block("minecraft:stone_bricks").defaultState());
    }
}
