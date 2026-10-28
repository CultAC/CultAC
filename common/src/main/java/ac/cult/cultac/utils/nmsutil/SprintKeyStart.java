package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.enums.Pose;

/**
 * LocalPlayer#aiStep's start from the held sprint key. With the sprint flag clear, canStartSprinting and the key run
 * setSprinting(true) before the stop checks, so a tick that announces no START_SPRINTING stopped it again in the same
 * aiStep (ClientSprintState#onClientTick).
 *
 * <p>From 1.21.2 to 26.3 the key starts sprinting on foot unless the player lacks forward impulse, is blind, has
 * food at or below 6 without mayfly, is in water, uses an item, is fall flying, is crouching or is crawling
 * (canStartSprinting, and before 1.21.5 the key start's own water check). The start is certain only when each of these
 * is ruled out. The double-tap start reads the client's sprintWindow option, which the server never sees, so it is
 * never certain here.
 */
public final class SprintKeyStart {
    private SprintKeyStart() {}

    /** Whether this client tick's LocalPlayer#aiStep certainly ran setSprinting(true) from the held sprint key. */
    public static boolean certain(CultPlayer player) {
        // From 1.21.2 PLAYER_INPUT reports the keys KeyboardInput#tick read for this aiStep, sprint included.
        if (player.isBedrockMovement()
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || !player.cameraEntity.isSelf()
                || player.inVehicle()) return false;
        PacketStateData data = player.packetStateData;
        KnownInput keys = data.knownInput;
        // ClientInput#hasForwardImpulse; before 1.21.5 forwardImpulse >= 0.8, which a forward key reaches unslowed.
        if (!keys.sprint() || keys.forwardImpulse() <= 0) return false;
        // isSprintingPossible: Player#isMobilityRestricted and hasEnoughFoodToDoExhaustiveManoeuvres.
        if (player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS) || player.food <= 6 && !player.canFly)
            return false;
        // isUsingItem (isSlowDueToUsingItem from 1.21.11) and isFallFlying, which this aiStep may only set later.
        if (player.clientItemUse.possiblyUsing()
                || player.isGliding
                || Boolean.TRUE.equals(data.glidingBeforeClientAction)) return false;
        // isInShallowWater (isInWater before 1.21.9), which Entity#baseTick refreshed at the movement start.
        if (possiblyInWater(player)) return false;
        // isMovingSlowly: isVisuallyCrawling is the swimming pose out of water, and crouching was decided this aiStep.
        return player.pose != Pose.SWIMMING && !possiblyCrouching(player);
    }

    /**
     * Entity#wasTouchingWater after this tick's baseTick: the last move's flag, or water in any block the player's box
     * overlaps at the movement start, a superset of Entity#updateFluidHeightAndDoFluidPushing's deflated box.
     */
    private static boolean possiblyInWater(CultPlayer player) {
        PredictionResult last = player.checkManager.getSimulationProcessor().getLastPrediction();
        JavaFallDistance.Moved moved = last == null ? null : last.getJavaCommittedMove();
        return moved != null && moved.touchingWater() || RiptideUtil.isTouchingWater(player);
    }

    /**
     * LocalPlayer#aiStep's crouching check before KeyboardInput#tick: never while flying, else the previous tick's
     * shift key or a standing box that no longer fits.
     */
    private static boolean possiblyCrouching(CultPlayer player) {
        if (FlightToggle.flyingBeforeAiStep(player)) return false;
        return player.packetStateData.lastTickShift
                || !player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, player.x, player.y, player.z);
    }
}
