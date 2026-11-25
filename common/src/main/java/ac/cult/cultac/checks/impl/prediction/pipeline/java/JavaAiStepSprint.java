package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
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

        DesyncStatus stop = version == ClientVersion.V_1_21_4
                ? shouldStopSprinting(gliding, blind, slowly, underwater, using)
                : DesyncStatus.FALSE;
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
     * 1.21.4's LocalPlayer#aiStep runs shouldStopSprinting's setSprinting(false) before the sprint starts, which
     * canStartSprinting lets through while moving slowly under water. A set flag the tick still ends with, announcing
     * no STOP_SPRINTING, was therefore restored by LivingEntity#setSprinting(true), which replaces whatever
     * minecraft:sprinting modifier the client held with vanilla's. TRUE when shouldStopSprinting certainly held on such
     * a tick, UNKNOWN when it may have, FALSE otherwise and on every other release, whose stop checks follow the start.
     */
    public static DesyncStatus restartsModifier(CultPlayer player, SimulationContext context) {
        if (context.getVersion() != ClientVersion.V_1_21_4
                || player.isBedrockMovement()
                || context.getVehicle() != null
                || !player.sprintState.flagBeforeAiStep()
                || !player.isSprinting) return DesyncStatus.FALSE;
        Boolean glidingBefore = player.packetStateData.glidingBeforeClientAction;
        boolean gliding = glidingBefore != null ? glidingBefore : context.isGliding();
        boolean blind = player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS);
        ClientItemUseState.Snapshot use = context.getItemUse();
        DesyncStatus using = use == null
                ? DesyncStatus.FALSE
                : use.definitelyUsing()
                        ? DesyncStatus.TRUE
                        : use.possiblyUsing() ? DesyncStatus.UNKNOWN : DesyncStatus.FALSE;
        return shouldStopSprinting(
                gliding,
                blind,
                ClientKeyInput.movingSlowly(player, context),
                context.getWorldData().isUnderwater(),
                using);
    }

    /** 1.21.4's shouldStopSprinting: isFallFlying, hasBlindness, isMovingSlowly, or isUsingItem out of water. */
    private static DesyncStatus shouldStopSprinting(
            boolean gliding, boolean blind, DesyncStatus slowly, boolean underwater, DesyncStatus using) {
        return or(DesyncStatus.fromBoolean(gliding || blind), or(slowly, underwater ? DesyncStatus.FALSE : using));
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
     * Whether this tick's aiStep removed the modifier for a passenger of {@code vehicle}, at the reconcile, so a
     * dismount does not carry a modifier the client dropped while riding.
     *
     * <ul>
     *   <li>TRUE for 1.21.4's shouldStopSprinting, which calls setSprinting(false) whatever the flag for a vehicle
     *       other than a camel or with hasBlindness. A passenger never crouches, glides or uses an item there.
     *   <li>UNKNOWN for a start on a camel the player may control: Camel#canSprint is the only vehicleCanSprint, and
     *       a start the tick did not announce was stopped in the same aiStep. Whether it ran reads the rider's own
     *       baseTick fluids at its seat, which no context models for the reconcile, so only blindness, a clear forward
     *       key or the flag rule it out.
     *   <li>FALSE otherwise: no other passenger can start.
     * </ul>
     */
    public static DesyncStatus clearsModifierRiding(CultPlayer player, PacketEntity vehicle) {
        if (player.isBedrockMovement()) return DesyncStatus.FALSE;
        boolean blind = player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS);
        boolean camel = EntityTypeUtil.isCamelFamily(vehicle.type);
        if (player.getClientVersion() == ClientVersion.V_1_21_4 && (!camel || blind)) return DesyncStatus.TRUE;
        if (!camel
                || blind
                || !player.compensatedEntities.vehicles.mayLocalPlayerControl(vehicle)
                || !player.sprintState.modifier()
                || player.sprintState.flagBeforeAiStep()
                || player.isClientSprinting()) return DesyncStatus.FALSE;
        // Every release's canStartSprinting needs forward impulse.
        if (JavaTravelInputs.reportsKeys(player) && player.packetStateData.knownInput.forwardImpulse() <= 0)
            return DesyncStatus.FALSE;
        return DesyncStatus.UNKNOWN;
    }

    /**
     * Whether a START_SPRINTING arriving now cannot follow a LocalPlayer#aiStep. Its flag ends set only through
     * canStartSprinting, which needs ClientInput#hasForwardImpulse in every release, or as a flag the server set before
     * aiStep that a swimming player's stop check keeps; PLAYER_INPUT precedes the command within the client tick.
     * From 1.21.5 canStartSprinting also fails a player crouching out of water ({@link #crouchedAtStart}).
     */
    public static boolean startImpossible(CultPlayer player) {
        if (!JavaTravelInputs.reportsKeys(player)) return false;
        boolean serverFlag = player.sprintState.serverFlagApplied();
        if (player.packetStateData.knownInput.forwardImpulse() <= 0) return !serverFlag || !maySwim(player);
        return !serverFlag
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)
                && !player.sprintState.announced()
                && crouchedAtStart(player);
    }

    /**
     * Whether Entity#updateSwimming may leave the player swimming before this tick's aiStep: a swimmer keeps the swim in
     * water, and a set flag starts one with the eye in water where the previous tick left the player.
     */
    private static boolean maySwim(CultPlayer player) {
        SimulationProcessor simulation = player.checkManager.getSimulationProcessor();
        return player.swimState.swimming() && simulation.mayBeLastInWater() || simulation.isLastWorldEyeInWater();
    }

    /**
     * LocalPlayer#crouching as this tick's aiStep set it before canStartSprinting, from the previous tick's shift and the
     * place that tick left the player: the crouching box fits, and shift is held or the standing box does not fit. With
     * the flag clear before aiStep, Entity#baseTick's updateSwimming left it not swimming; LocalPlayer#isUnderWater is
     * the eye the previous tick's baseTick read. A shove before the travel leaves the place open.
     */
    private static boolean crouchedAtStart(CultPlayer player) {
        SimulationProcessor simulation = player.checkManager.getSimulationProcessor();
        return !simulation.isLastWorldEyeInWater()
                && !FlightToggle.flyingBeforeAiStep(player)
                && !player.inVehicle()
                && simulation.nextAiStepStartsAtPlayer()
                && player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, player.x, player.y, player.z)
                && (simulation.isLastSneaking()
                        || !player.isInBed
                                && !player.canPlayerFitWithinBlocksAndEntitiesWhen(
                                        Pose.STANDING, player.x, player.y, player.z));
    }

    /**
     * Whether the sprint the client announced at the end of {@code context}'s tick is one its aiStep cannot have left
     * set, as the tick's sendIsSprintingIfNeeded makes the announcement equal the flag after aiStep's stop checks.
     *
     * <ul>
     *   <li>shouldStopRunSprinting ends a sprint without forward impulse; only a swimming player keeps it.
     *   <li>From 1.21.5 a sprint aiStep started itself passed canStartSprinting, which a player moving slowly out of
     *       water fails.
     * </ul>
     *
     * A Java player on foot with reported keys.
     */
    public static boolean announcementImpossible(CultPlayer player, SimulationContext context) {
        if (!player.sprintState.announced()
                || context.getVehicle() != null
                || player.inVehicle()
                || !JavaTravelInputs.reportsKeys(player)) return false;
        WorldData world = context.getWorldData();
        if (player.packetStateData.knownInput.forwardImpulse() <= 0) return !world.isSwimming();
        return context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)
                && player.sprintState.aiStepStartedSprint()
                && !world.isInWater()
                && !world.isUnderwater()
                && !world.isEyeInWater()
                && ClientKeyInput.movingSlowly(player, context) == DesyncStatus.TRUE;
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
