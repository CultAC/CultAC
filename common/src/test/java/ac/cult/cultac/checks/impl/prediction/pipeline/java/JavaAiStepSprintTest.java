package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep's setSprinting(false) with the flag clear, read from the tick's context. Each case starts with a
 * server snapshot's minecraft:sprinting on a player whose flag is clear, on dry open ground, holding forward and
 * sprint.
 */
class JavaAiStepSprintTest {
    private static final KnownInput SPRINT_FORWARD = new KnownInput(true, false, false, false, false, false, true);

    @Test
    void theHeldSprintKeyStartsAndAnUnannouncedStopClears() {
        assertEquals(DesyncStatus.TRUE, verdict(ClientVersion.V_26_3, tick -> {}));
        assertEquals(DesyncStatus.TRUE, verdict(ClientVersion.V_1_21_2, tick -> {}));
    }

    @Test
    void nothingClearsWithoutAModifierOrWithTheFlagSet() {
        assertEquals(
                DesyncStatus.FALSE,
                verdict(ClientVersion.V_26_3, tick -> tick.player.sprintState.onServerModifier(null)));
        assertEquals(DesyncStatus.FALSE, verdict(ClientVersion.V_26_3, tick -> tick.sprinting = DesyncStatus.TRUE));
    }

    @Test
    void aDoubleTapStartIsOnlyPossible() {
        assertEquals(
                DesyncStatus.UNKNOWN,
                verdict(
                        ClientVersion.V_26_3,
                        tick -> tick.keys = new KnownInput(true, false, false, false, false, false, false)));
    }

    @Test
    void noForwardImpulseHungerOrBlindnessRefusesTheStart() {
        assertEquals(
                DesyncStatus.FALSE,
                verdict(
                        ClientVersion.V_26_3,
                        tick -> tick.keys = new KnownInput(true, true, false, false, false, false, true)));
        assertEquals(DesyncStatus.FALSE, verdict(ClientVersion.V_26_3, tick -> tick.player.food = 6));
        assertEquals(
                DesyncStatus.FALSE,
                verdict(
                        ClientVersion.V_26_3,
                        tick -> tick.player
                                .compensatedEntities
                                .getSelf()
                                .addPotionEffect(MovementEffect.BLINDNESS, 0)));
    }

    @Test
    void aUseTheClientMayHoldLeavesTheStartPossible() {
        var possible = new ClientItemUseState.Snapshot(1, 0, 1, 1, 1, 0.2F, true, false, false, true, true);
        var certain = new ClientItemUseState.Snapshot(1, 0, 1, 0.2F, 0.2F, 0.2F, false, false, true, true, true);
        assertEquals(DesyncStatus.UNKNOWN, verdict(ClientVersion.V_26_3, tick -> tick.use = possible));
        assertEquals(DesyncStatus.FALSE, verdict(ClientVersion.V_26_3, tick -> tick.use = certain));
    }

    @Test
    void shallowWaterRefusesTheStartUnlessTheEyeIsUnderIt() {
        assertEquals(DesyncStatus.FALSE, verdict(ClientVersion.V_26_3, tick -> tick.inWater = true));
        assertEquals(DesyncStatus.TRUE, verdict(ClientVersion.V_26_3, tick -> tick.inWater = tick.underwater = true));
    }

    @Test
    void lastTicksShiftCrouchesThePlayerOutOfTheStart() {
        assertEquals(DesyncStatus.FALSE, verdict(ClientVersion.V_26_3, tick -> tick.lastSneaking = true));
    }

    @Test
    void oneTwentyOneFourStopsWheneverShouldStopSprintingHolds() {
        // isMovingSlowly, without forward impulse or the sprint key.
        assertEquals(DesyncStatus.TRUE, verdict(ClientVersion.V_1_21_4, tick -> {
            tick.lastSneaking = true;
            tick.keys = KnownInput.DEFAULT;
        }));
        assertEquals(DesyncStatus.TRUE, verdict(ClientVersion.V_1_21_4, tick -> {
            tick.gliding = true;
            tick.keys = KnownInput.DEFAULT;
        }));
        // isUsingItem only out from under water.
        var using = new ClientItemUseState.Snapshot(1, 0, 1, 0.2F, 0.2F, 0.2F, false, false, true, true, true);
        assertEquals(DesyncStatus.TRUE, verdict(ClientVersion.V_1_21_4, tick -> tick.use = using));
        // Other releases keep the modifier for the same crouch.
        assertEquals(DesyncStatus.FALSE, verdict(ClientVersion.V_1_21_5, tick -> {
            tick.lastSneaking = true;
            tick.keys = KnownInput.DEFAULT;
        }));
    }

    /** The context state one case varies. */
    private static final class Tick {
        final CultPlayer player;
        KnownInput keys = SPRINT_FORWARD;
        DesyncStatus sprinting = DesyncStatus.FALSE;
        ClientItemUseState.Snapshot use = ClientItemUseState.Snapshot.NONE;
        boolean inWater;
        boolean underwater;
        boolean lastSneaking;
        boolean gliding;

        Tick(CultPlayer player) {
            this.player = player;
        }
    }

    private static DesyncStatus verdict(ClientVersion version, Consumer<Tick> setup) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "AiStep_Sprint"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
            player.sprintState.onClientTick(false);
            Tick tick = new Tick(player);
            setup.accept(tick);
            player.packetStateData.knownInput = tick.keys;

            WorldData world = mock(WorldData.class);
            when(world.isInWater()).thenReturn(tick.inWater);
            when(world.isUnderwater()).thenReturn(tick.underwater);
            SimulationContext context = mock(SimulationContext.class);
            when(context.getVersion()).thenReturn(version);
            when(context.getWorldData()).thenReturn(world);
            when(context.getIsSprinting()).thenReturn(tick.sprinting);
            when(context.getItemUse()).thenReturn(tick.use);
            when(context.getPose()).thenReturn(Pose.STANDING);
            when(context.getStart()).thenReturn(new Vec3(0.5D, 64.0D, 0.5D));
            when(context.isLastSneaking()).thenReturn(tick.lastSneaking);
            when(context.isGliding()).thenReturn(tick.gliding);
            return JavaAiStepSprint.clearsModifier(player, context);
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
