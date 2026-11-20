package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.BubbleColumnData;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * FireworkRocketEntity#tick runs after the LocalPlayer's tick and sets the glider's velocity to
 * v + 0.1 * look + (1.5 * look - v) * 0.5 once per attached rocket, with the look of that tick; the next glide
 * starts from it.
 */
class ElytraTransformFireworkTest {
    private static final double GRAVITY = 0.08D;
    private static final float LAST_YAW = 90.0F, LAST_PITCH = -40.0F;
    private static final float YAW = -10.0F, PITCH = 20.0F;
    private static final Vec3 VELOCITY = new Vec3(0.3D, -0.2D, 0.6D);

    /** MovementModifiers starts the glide from the stored velocity each attached rocket boosted. */
    @Test
    void aBoostedStartGlidesFromTheBoost() {
        withPlayer(player -> {
            for (int rockets = 1; rockets <= 2; rockets++) {
                PredVector start = boosted(player, VELOCITY, rockets);
                Vec3 end = glide(player, start);
                assertClose(end, transform(player, start, end));
            }
        });
    }

    @Test
    void aServerVelocityReplacesTheBoostedVelocity() {
        withPlayer(player -> {
            Vec3 unboosted = glide(player, VELOCITY);
            Vec3 boosted = glide(player, boosted(player, VELOCITY, 1));
            PredVector velocity = boosted(player, Vec3.ZERO, 1).withXYZ(VELOCITY.x, VELOCITY.y, VELOCITY.z, "Velocity");

            Vec3 transformed = transform(player, velocity, boosted);
            assertEquals(0.0D, unboosted.distanceTo(transformed), 1.0E-3D);
            assertTrue(boosted.distanceTo(transformed) > 0.5D);
        });
    }

    /** TridentItem#releaseUsing pushes in Minecraft#handleKeybinds, after the rocket boosted the stored velocity. */
    @Test
    void aRiptidePushAddsToTheBoostedVelocity() {
        withPlayer(player -> {
            SimulationContext context = context(player);
            when(context.getRiptideLevel()).thenReturn(3);
            when(context.isRiptideReleaseCertain()).thenReturn(true);
            when(context.getTrig()).thenReturn(new TrigHandler(player));
            when(context.getWorldData().getCarriedOnGround()).thenReturn(DesyncStatus.FALSE);
            PredVector boosted = boosted(player, VELOCITY, 1);
            PredVector pushed = Riptide.release(player, context, List.of(boosted)).getFirst();
            Vec3 push = pushed.subtract(boosted);

            Vec3 end = glide(player, boosted.add(push));
            assertClose(end, transform(player, context, pushed, end));
            assertTrue(glide(player, VELOCITY.add(push)).distanceTo(end) > 0.5D);
        });
    }

    /** A grounded release's Entity#move that consumes a stuck multiplier zeroes the boosted velocity. */
    @Test
    void aStuckRiptideLiftZeroesTheBoostedVelocity() {
        withPlayer(player -> {
            SimulationContext context = context(player);
            when(context.getRiptideLevel()).thenReturn(3);
            when(context.getRiptideLiftStuck()).thenReturn(new Vec3(0.25D, 0.05F, 0.25D));

            Vec3 stopped = glide(player, Vec3.ZERO);
            PredVector lifted = boosted(player, Vec3.ZERO, 1).withXYZ(0, 0, 0, "riptide lift");
            Vec3 boosted = glide(player, boosted(player, Vec3.ZERO, 1));

            Vec3 transformed = transform(player, context, lifted, boosted);
            assertEquals(0.0D, stopped.distanceTo(transformed), 1.0E-5D);
            assertTrue(boosted.distanceTo(transformed) > 0.5D);
        });
    }

    /**
     * LivingEntity#aiStep's bubble callbacks change the stored velocity before the rocket boosts it, so the boost
     * halves the column's push.
     */
    @Test
    void theRocketBoostsTheBubbleColumnPush() {
        withPlayer(player -> {
            PredictionResult last = mock(PredictionResult.class);
            SimulationContext lastContext = mock(SimulationContext.class);
            WorldData lastWorld = mock(WorldData.class);
            when(last.getSimulationContext()).thenReturn(lastContext);
            when(lastContext.getWorldData()).thenReturn(lastWorld);
            when(lastWorld.getBubbleColumn()).thenReturn(new BubbleColumnData(1, 0, 0, 0));
            PredVector start = boosted(player, VELOCITY, 1);
            Vec3 pushedFirst = boosted(player, VELOCITY.add(0, 0.1D, 0), 1);
            Vec3 boostedFirst = start.add(0, 0.1D, 0);

            Vec3 vanilla = glide(player, pushedFirst);
            assertClose(vanilla, transform(player, context(player), last, start, vanilla));
            Vec3 reordered = glide(player, boostedFirst);
            assertTrue(reordered.distanceTo(transform(player, context(player), last, start, reordered)) > 0.04D);
        });
    }

    /** Mth#sin and Mth#cos read one table, so the glide leaves the table of every later tick as it was. */
    @Test
    void aGlideLeavesTheTrigTableUnchanged() {
        withPlayer(player -> {
            Vec3 end = glide(player, VELOCITY);
            transform(player, new PredVector(VELOCITY), end);
            assertTrue(player.isVanillaMath());

            transform(player, boosted(player, VELOCITY, 1), end);
            assertTrue(player.isVanillaMath());
        });
    }

    /** The start MovementModifiers adds for {@code rockets} boosts with the last tick's look. */
    private static PredVector boosted(CultPlayer player, Vec3 velocity, int rockets) {
        Vector3dm lastLook = ReachUtils.getLook(player, LAST_YAW, LAST_PITCH);
        Vec3 boosted = velocity;
        for (int i = 0; i < rockets; i++) boosted = boost(boosted, lastLook);
        return new ElytraTransform.Boosted(new PredVector(velocity), boosted, rockets);
    }

    private static Vec3 boost(Vec3 velocity, Vector3dm look) {
        return velocity.add(
                look.getX() * 0.1D + (look.getX() * 1.5D - velocity.x) * 0.5D,
                look.getY() * 0.1D + (look.getY() * 1.5D - velocity.y) * 0.5D,
                look.getZ() * 0.1D + (look.getZ() * 1.5D - velocity.z) * 0.5D);
    }

    private static Vec3 glide(CultPlayer player, Vec3 velocity) {
        return ElytraTransform.getElytraMovement(
                player, velocity, ReachUtils.getLook(player, YAW, PITCH), PITCH, GRAVITY);
    }

    private static Vec3 transform(CultPlayer player, PredVector start, Vec3 end) {
        return transform(player, context(player), start, end);
    }

    private static SimulationContext context(CultPlayer player) {
        WorldData worldData = mock(WorldData.class);
        SimulationContext context = mock(SimulationContext.class);
        when(context.usesFallFlyingMovement()).thenReturn(true);
        when(context.getWorldData()).thenReturn(worldData);
        when(context.getVersion()).thenReturn(ClientVersion.V_26_1);
        when(context.getXRot()).thenReturn(YAW);
        when(context.getYRot()).thenReturn(PITCH);
        when(context.getLastXRot()).thenReturn(LAST_YAW);
        when(context.getLastYRot()).thenReturn(LAST_PITCH);
        return context;
    }

    private static Vec3 transform(CultPlayer player, SimulationContext context, PredVector start, Vec3 end) {
        return transform(player, context, null, start, end);
    }

    private static Vec3 transform(
            CultPlayer player, SimulationContext context, PredictionResult last, PredVector start, Vec3 end) {
        PredictionResult result = mock(PredictionResult.class);
        when(result.getSimulationContext()).thenReturn(context);
        when(result.getPossibleGravity(anyDouble(), anyDouble())).thenReturn(List.of(GRAVITY));
        return new ElytraTransform()
                .handleUncertainty(player, mock(ValidMovements.class), result, context, last, start, end);
    }

    private static void assertClose(Vec3 expected, Vec3 actual) {
        assertEquals(0.0D, expected.distanceTo(actual), 1.0E-9D, () -> expected + " != " + actual);
    }

    private static void withPlayer(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Firework_Boost"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            test.accept(player);
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
