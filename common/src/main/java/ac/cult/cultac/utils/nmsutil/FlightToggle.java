package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;

/**
 * LocalPlayer#aiStep's double-tap flight toggle: a jump key going down while Player#jumpTriggerTime is armed by a
 * press within the previous 7 ticks. {@link PacketStateData#jumpTriggerTimes} holds each timer value the client
 * can have at its next toggle check, bit t for value t; the tick's player_abilities rules out the values it
 * contradicts.
 */
public final class FlightToggle {
    private static final int UNARMED = 1;
    private static final int PRESSED = 1 << 7;

    private FlightToggle() {}

    /** The jump key went down this client tick; LocalPlayer#aiStep reads the previous tick's keys as wasJumping. */
    public static boolean freshJumpPress(PacketStateData data) {
        return data.knownInput.jump() && !data.lastTickJump;
    }

    /** This client tick's keys toggle flight in LocalPlayer#aiStep unless the press is blocked. */
    public static boolean canToggle(CultPlayer player) {
        return pressReachesToggle(player) && (player.packetStateData.jumpTriggerTimes & ~UNARMED) != 0;
    }

    /**
     * This client tick's flight stop is the toggle ahead of travel: a press that every timer value the client can
     * have arms, with no auto-jump or vehicle to block it. Player#updateSwimming never lets a flyer swim.
     */
    public static boolean stopsBeforeTravel(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        return pressReachesToggle(player)
                && (data.jumpTriggerTimes & UNARMED) == 0
                && !data.autoJumpPossible
                && !vehicleCanBlock(player);
    }

    /** Abilities#flying at the start of LocalPlayer#aiStep, before this client tick's toggle sent player_abilities. */
    public static boolean flyingBeforeAiStep(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        return data.flightStopThisClientTick || player.isFlying && !data.flyingStartBeforeTravel;
    }

    /** Runs the toggle's timer update and Player#aiStep's decrement for a client tick LocalPlayer#aiStep ran. */
    public static void onClientTick(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        data.swimmingPossible = swimmingPossible(player);
        int times = data.jumpTriggerTimes;
        if (pressReachesToggle(player)) {
            times = afterPress(player, times);
        }
        data.jumpTriggerTimes = (times >>> 1) | (times & UNARMED);
        data.lastTickJump = data.knownInput.jump();
        data.autoJumpPossible = autoJumpCanFire(player);
        data.eyeInWaterBeforeTick = player.checkManager.getSimulationProcessor().isLastWorldEyeInWater();
    }

    /**
     * A new LocalPlayer starts unarmed, without an auto-jump, with its input at Input.EMPTY and no fluid on its eyes.
     * It can be swimming only when it copies the old player's entity data.
     */
    public static void reset(PacketStateData data, boolean keepEntityData) {
        data.jumpTriggerTimes = UNARMED;
        data.lastTickJump = false;
        data.autoJumpPossible = false;
        data.eyeInWaterBeforeTick = false;
        if (!keepEntityData) data.swimmingPossible = false;
    }

    private static int afterPress(CultPlayer player, int times) {
        PacketStateData data = player.packetStateData;
        boolean armed = (times & ~UNARMED) != 0;
        // Only the toggle sends a flight start on a press, and it needs an armed timer.
        if (armed && data.flyingStartBeforeTravel) return UNARMED;

        boolean blocked = data.autoJumpPossible || data.swimmingPossible || vehicleCanBlock(player);
        int next = 0;
        if ((times & UNARMED) != 0) {
            next |= PRESSED;
            if (data.autoJumpPossible) next |= UNARMED;
        }
        if (armed) {
            // A flight stop is the toggle, or LocalPlayer#aiStep landing after a blocked toggle.
            if (data.flightStopThisClientTick) next |= UNARMED;
            if (blocked) next |= times & ~UNARMED;
        }
        // A press no vanilla timer explains leaves the client unarmed until it presses again.
        return next == 0 ? UNARMED : next;
    }

    // Entity#baseTick runs updateSwimming before aiStep, at the tick's start. A swimmer keeps swimming while it
    // sprints in water; anyone else starts only sprinting with its eyes under water (wasEyeInWater is the previous
    // baseTick's eye fluid) and water at its feet. Player#updateSwimming stops it in flight, and a passenger never
    // swims. ClientPacketListener#handleSetEntityData can overwrite the previous state with the server's.
    private static boolean swimmingPossible(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        if (flyingBeforeAiStep(player) || player.inVehicle() || !player.sprintState.flagBeforeAiStep()) return false;
        PredictionResult prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        if (prediction == null) return true;
        if (!prediction.getSimulationContext().getWorldData().getInWater().getStates().contains(true)) return false;
        if (data.swimmingPossible) return true;
        Vec3 start = prediction.getSimulationContext().getStart();
        return data.eyeInWaterBeforeTick
                && player.compensatedWorld.getWaterFluidLevelAt(
                                (int) Math.floor(start.x), (int) Math.floor(start.y), (int) Math.floor(start.z))
                        > 0;
    }

    // LocalPlayer#move runs updateAutoJump after the move, with the client option open.
    private static boolean autoJumpCanFire(CultPlayer player) {
        return player.checkManager.getListener(JavaAutoJump.class).canFireIn(player.packetStateData.acceptedClientTick);
    }

    // LocalPlayer#aiStep toggles in a vehicle only through jumpableVehicle(): a horse or nautilus the player controls
    // (saddled, first passenger) that canJump. A camel also needs !refuseToMove() after its root tick, so any camel
    // may block.
    private static boolean vehicleCanBlock(CultPlayer player) {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        if (vehicle == null) return false;
        boolean controlled = vehicle.passengers.indexOf(player.compensatedEntities.getSelf()) == 0;
        boolean saddled = vehicle instanceof PacketEntityHorse horse && horse.hasSaddle
                || vehicle instanceof PacketEntityNautilus nautilus && nautilus.hasSaddle;
        return !controlled || !saddled || vehicle instanceof PacketEntityCamel;
    }

    // LocalPlayer#aiStep checks the jump press only with Abilities#mayfly and outside spectator.
    private static boolean pressReachesToggle(CultPlayer player) {
        return player.canFly && player.gamemode != GameMode.SPECTATOR && freshJumpPress(player.packetStateData);
    }
}
