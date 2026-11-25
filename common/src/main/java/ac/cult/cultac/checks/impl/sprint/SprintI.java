package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.api.storage.verbose.Verbose;

/**
 * LocalPlayer#aiStep holds the only setSprinting(true), and after KeyboardInput#tick it stops a sprint whose
 * ClientInput#hasForwardImpulse fails, which the keys give only for forward without backward. A swimming player alone
 * keeps the sprint without it, on the ground or holding shift (shouldStopSwimSprinting; inline before 1.21.5).
 *
 * <p>LocalPlayer#tick sends PLAYER_INPUT and then sendIsSprintingIfNeeded's command, so a tick that ran aiStep and
 * reached that send ends with the keys and the sprint of one aiStep. A server flag (ClientPacketListener
 * #handleSetEntityData) reaches the client between ticks and passes the same stop before anything announces it; the
 * minecraft:sprinting modifier of an attribute update never touches the flag.
 */
@CheckData(
        name = "SprintI",
        stableKey = "cult.sprint.no_forward",
        description = "Sprinting without holding forward",
        experimental = true)
public class SprintI extends Check implements PostPredictionListener, ClientTickEndListener {
    private static final Verbose V = Verbose.of("forward={bool}, backward={bool}, swimming={bool}, started={bool}");

    // A START_SPRINTING of this tick whose keys held no forward; a STOP_SPRINTING after it does not undo it.
    private boolean startedWithoutForward;
    // Entity#isSwimming and Entity#onGround as this tick's aiStep read them, from its last prediction.
    private boolean contextThisTick;
    private boolean swimming;
    private boolean onGroundPossible;

    public SprintI(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return JavaTravelInputs.reportsKeys(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        PredictionResult result = complete.getPredictionResult();
        if (complete.isTeleport() || result == null) return;
        SimulationContext context = result.getSimulationContext();
        WorldData world = context == null ? null : context.getWorldData();
        if (world == null) return;
        contextThisTick = true;
        swimming = world.isSwimming();
        onGroundPossible = world.getLastOnGround().determineOptimistically();
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.START_SPRINTING
                && isApplicable()
                && !forward(player.packetStateData.knownInput)) {
            startedWithoutForward = true;
        }
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        boolean started = startedWithoutForward;
        boolean context = contextThisTick;
        startedWithoutForward = false;
        contextThisTick = false;
        if (event.isSynthetic() || !isApplicable() || !announcementCurrent()) return;

        KnownInput keys = player.packetStateData.knownInput;
        boolean forward = forward(keys);
        if (forward && !started) return;
        if (!started && !player.isSprinting) {
            reward();
            return;
        }
        // Without this tick's world the client may still swim as it last did, on any ground.
        boolean swims = context ? swimming : player.swimState.swimming();
        if (swims && (keys.shift() || !context || onGroundPossible)) return;
        flag(V.write(verbose())
                .bool(keys.forward())
                .bool(keys.backward())
                .bool(swims)
                .bool(started));
    }

    /** KeyboardInput#tick's forward impulse is positive only for forward without backward. */
    private static boolean forward(KnownInput keys) {
        return keys.forward() && !keys.backward();
    }

    /**
     * Whether this client tick ran LocalPlayer#aiStep and ended in sendIsSprintingIfNeeded: on foot always, and as a
     * passenger only for the root the tick sent a MoveVehicle for. Any other passenger keeps its last announcement
     * while aiStep clears the flag, and the tick LocalPlayer#tickDeath removes the player in skips aiStep.
     */
    private boolean announcementCurrent() {
        if (!player.localPlayerTicks()) return false;
        PacketEntitySelf self = player.compensatedEntities.getSelf();
        if (self.getRiding() != null) return player.packetStateData.clientTickVehicleMoveThisClientTick;
        return !self.inVehicle();
    }
}
