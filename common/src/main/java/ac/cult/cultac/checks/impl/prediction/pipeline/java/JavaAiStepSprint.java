package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.FlightToggle;

/**
 * LocalPlayer#aiStep's LivingEntity#setSprinting(false) on a tick that starts and ends with the sprint flag clear. It
 * removes minecraft:sprinting even though the flag does not change and nothing is announced, so it drops a modifier an
 * attribute snapshot installed on a player that was not sprinting. Three calls reach it (1.21.2 to 26.3):
 *
 * <ul>
 *   <li>The start: canStartSprinting and the held sprint key run setSprinting(true) before the stop checks. A tick that
 *       announces no START_SPRINTING after it ran a stop check's setSprinting(false) in the same aiStep. The double-tap
 *       start reads the client's sprintWindow option, and 1.21.2-1.21.4's ground start its trigger timer and last
 *       impulse, which the server never sees, so those starts are only possible.
 *   <li>1.21.4 only: setSprinting(false) whenever shouldStopSprinting holds after KeyboardInput#tick.
 *   <li>After travel, a riptide spin's Player#attack through causeExtraKnockback.
 * </ul>
 *
 * Player#attack before the tick reaches causeExtraKnockback too; ClientSprintState#onAttack owns that one.
 *
 * Every input is the state this tick's aiStep reads: the keys PLAYER_INPUT reported, the flag before aiStep, effects,
 * food and abilities at their bundled proofs, the use the tick's snapshot holds, and the water, pose and crouching the
 * context's world stage modelled for Entity#baseTick and aiStep.
 */
public final class JavaAiStepSprint {
    private static final float ENOUGH_IMPULSE = 0.8F;

    private JavaAiStepSprint() {}

    /**
     * TRUE when this tick's on-foot aiStep certainly ran setSprinting(false) with the flag clear, UNKNOWN when it may
     * have, FALSE otherwise or when no modifier can be held.
     */
    public static DesyncStatus clearsModifier(CultPlayer player, SimulationContext context) {
        if (!flagStaysClear(player, context)
                || context.getIsSprinting() == DesyncStatus.TRUE
                || context.isSpinSprintResetRequired()) return DesyncStatus.FALSE;
        ClientVersion version = context.getVersion();
        // Without the reported keys a sprint-key start may have run and stopped.
        if (version.isOlderThan(ClientVersion.V_1_21_2) || !JavaTravelInputs.usesReportedKeys(player, context))
            return DesyncStatus.UNKNOWN;

        WorldData world = context.getWorldData();
        boolean underwater = world.isUnderwater();
        boolean blind = player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS);
        // FoodData#hasEnoughFood or Abilities#mayfly.
        boolean food = player.food > 6 || player.canFly;
        // LivingEntity#isFallFlying before this aiStep's tryToStartFallFlying sent START_FALL_FLYING.
        Boolean glidingBefore = player.packetStateData.glidingBeforeClientAction;
        boolean gliding = glidingBefore != null ? glidingBefore : context.isGliding();
        DesyncStatus slowly = ClientKeyInput.movingSlowly(player, context);
        ClientItemUseState.Snapshot use = context.getItemUse();
        DesyncStatus using = use == null
                ? DesyncStatus.FALSE
                : use.definitelyUsing()
                        ? DesyncStatus.TRUE
                        : use.possiblyUsing() ? DesyncStatus.UNKNOWN : DesyncStatus.FALSE;

        DesyncStatus stop = DesyncStatus.FALSE;
        if (version == ClientVersion.V_1_21_4) {
            // shouldStopSprinting: isFallFlying, hasBlindness, isMovingSlowly, or isUsingItem out from under water.
            stop = or(DesyncStatus.fromBoolean(gliding || blind), or(slowly, underwater ? DesyncStatus.FALSE : using));
        }
        DesyncStatus start = start(player, context, version, food && !blind, gliding, slowly, using, use);
        DesyncStatus cleared = or(stop, start);
        // A spin that may hit a RemotePlayer leaves the travel's flag unknown: a start no stop check ended lasts
        // through
        // travel until checkAutoSpinAttack resets it, announcing nothing, so only a tick without a start travels
        // cleared.
        if (context.getIsSprinting() != DesyncStatus.FALSE
                && start != DesyncStatus.FALSE
                && cleared != DesyncStatus.FALSE) return DesyncStatus.UNKNOWN;
        return cleared;
    }

    /**
     * Whether this tick's LivingEntity#checkAutoSpinAttack removed the modifier after travel with the flag clear.
     * Player#attack reaches causeExtraKnockback's setSprinting(false) only on a RemotePlayer it hurt
     * (RemotePlayer#hurtClient) with positive knockback: attack_knockback / 2, plus 0.5 for a sprint attack. TRUE for
     * a candidate whose travel needed that reset, UNKNOWN where the spin may hit a RemotePlayer with positive
     * knockback.
     */
    public static DesyncStatus clearsModifierAfterTravel(CultPlayer player, SimulationContext context) {
        if (!flagStaysClear(player, context)) return DesyncStatus.FALSE;
        if (context.isSpinSprintResetRequired()) return DesyncStatus.TRUE;
        return context.getWorldData().isSpinAttackPlayer() && JavaSpinAttack.extraKnockbackPossible(player, context)
                ? DesyncStatus.UNKNOWN
                : DesyncStatus.FALSE;
    }

    /** The removal the committed tick leaves: by aiStep before {@code context}'s travel, or by the spin after it. */
    public static DesyncStatus clearsModifierThisTick(CultPlayer player, SimulationContext context) {
        return or(context.aiStepSprintClear(player), clearsModifierAfterTravel(player, context));
    }

    /**
     * Whether a modifier may be held while the flag is clear before aiStep and the tick announces no sprint, the only
     * ticks where setSprinting(false) removes it silently. A Java player on foot.
     */
    private static boolean flagStaysClear(CultPlayer player, SimulationContext context) {
        return !player.isBedrockMovement()
                && context.getVehicle() == null
                && player.sprintState.modifier()
                && !player.sprintState.flagBeforeAiStep()
                && !player.isSprinting;
    }

    /**
     * Whether this tick's aiStep certainly ran setSprinting(false) for a passenger of {@code vehicle}: only 1.21.4's
     * shouldStopSprinting reaches it, through hasBlindness or a vehicle other than a camel. A passenger never crouches,
     * glides or uses an item there, and only a camel lets it start.
     */
    public static boolean clearsModifierRiding(CultPlayer player, PacketEntity vehicle) {
        return !player.isBedrockMovement()
                && player.getClientVersion() == ClientVersion.V_1_21_4
                && (!EntityTypeUtil.isCamelFamily(vehicle.type)
                        || player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS));
    }

    /** canStartSprinting and a start path, with {@code possible} the food and blindness gates. */
    private static DesyncStatus start(
            CultPlayer player,
            SimulationContext context,
            ClientVersion version,
            boolean possible,
            boolean gliding,
            DesyncStatus slowly,
            DesyncStatus using,
            ClientItemUseState.Snapshot use) {
        KnownInput keys = player.packetStateData.knownInput;
        // ClientInput#hasForwardImpulse; before 1.21.5 forwardImpulse >= 0.8, or above zero under water.
        if (!possible || keys.forwardImpulse() <= 0) return DesyncStatus.FALSE;
        WorldData world = context.getWorldData();
        boolean inWater = world.isInWater();
        boolean underwater = world.isUnderwater();
        DesyncStatus notSlowly = underwater ? DesyncStatus.TRUE : not(slowly);
        DesyncStatus canStart;
        boolean keyStart;
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            // isSlowDueToUsingItem from 1.21.11, isUsingItem before.
            DesyncStatus item = version.isOlderThan(ClientVersion.V_1_21_11) || use == null
                    ? using
                    : use.definitelyUsing() && !use.canSprint()
                            ? DesyncStatus.TRUE
                            : use.possiblyBlocksSprint() ? DesyncStatus.UNKNOWN : DesyncStatus.FALSE;
            // isSprintingPossible's isInShallowWater from 1.21.9, which flight allows; before, isInWater not under it.
            boolean water = !inWater
                    || underwater
                    || version.isNewerThanOrEquals(ClientVersion.V_1_21_9) && FlightToggle.flyingBeforeAiStep(player);
            canStart = and(DesyncStatus.fromBoolean(water && (!gliding || underwater)), and(not(item), notSlowly));
            keyStart = keys.sprint();
        } else {
            // hasEnoughImpulseToStartSprinting reads forwardImpulse after isMovingSlowly's sneaking-speed slowdown.
            float sneaking =
                    (float) player.compensatedEntities.getSelf().sneakingSpeed.value();
            DesyncStatus impulse = underwater || sneaking >= ENOUGH_IMPULSE ? DesyncStatus.TRUE : not(slowly);
            canStart = and(DesyncStatus.fromBoolean(!gliding), and(not(using), impulse));
            if (version == ClientVersion.V_1_21_4) canStart = and(canStart, notSlowly);
            // The key start also needs isInWater to be false or isUnderWater.
            keyStart = keys.sprint() && (!inWater || underwater);
        }
        return and(canStart, keyStart ? DesyncStatus.TRUE : DesyncStatus.UNKNOWN);
    }

    private static DesyncStatus and(DesyncStatus a, DesyncStatus b) {
        if (a == DesyncStatus.FALSE || b == DesyncStatus.FALSE) return DesyncStatus.FALSE;
        return a == DesyncStatus.TRUE && b == DesyncStatus.TRUE ? DesyncStatus.TRUE : DesyncStatus.UNKNOWN;
    }

    private static DesyncStatus or(DesyncStatus a, DesyncStatus b) {
        if (a == DesyncStatus.TRUE || b == DesyncStatus.TRUE) return DesyncStatus.TRUE;
        return a == DesyncStatus.FALSE && b == DesyncStatus.FALSE ? DesyncStatus.FALSE : DesyncStatus.UNKNOWN;
    }

    private static DesyncStatus not(DesyncStatus status) {
        return switch (status) {
            case TRUE -> DesyncStatus.FALSE;
            case FALSE -> DesyncStatus.TRUE;
            case UNKNOWN -> DesyncStatus.UNKNOWN;
        };
    }
}
