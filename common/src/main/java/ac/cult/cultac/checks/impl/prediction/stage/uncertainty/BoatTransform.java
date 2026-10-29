package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.WaterCurrent;
import java.util.ArrayList;
import java.util.List;

public class BoatTransform implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return context.getVehicle() == null || !EntityTypeUtil.isBoat(context.getVehicle().type);
    }

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        PredVector velocity = handleUncertainty(player, valid, result, context, lastResult, trace.position(), end);
        if (velocity == trace.position()) return trace;
        // AbstractBoat#tick stores the floatBoat and controlBoat result in deltaMovement before Entity#move,
        // so that, not the tick's starting velocity, is what the move carries into the next tick.
        MovementTrace moved = trace.withPosition(velocity).withPreCollisionVelocity(velocity);
        if (usesProvenWaterEntryPositionSnap(player, context)) {
            // floatBoat's water-entry setPos is position only. That branch explicitly clears stored Y before
            // controlBoat and Entity#move, so its height correction must never become next tick's buoyancy.
            Vec3 snap = new Vec3(0, velocity.y, 0);
            Vec3 stuck = velocity.collisionStuckSpeedMultiplier(context);
            double unscaledY = stuck == null ? snap.y : snap.y / stuck.y;
            Vec3 base = new Vec3(0, unscaledY, 0);
            moved = moved.withPosition(velocity.withY(unscaledY, "boat setPos before move"))
                    .withPreCollisionVelocity(new Vec3(velocity.x, 0, velocity.z))
                    .addPositionOnlyDelta(snap)
                    .addCollisionBaseOffset(base);
        }
        return moved;
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        if (context.getVehicle() == null || !EntityTypeUtil.isBoat(context.getVehicle().type)) return start;
        if (!player.packetStateData.isVehicleMovementFromClientTick()) {
            // MCP-Reborn ClientPacketListener#handleMoveVehicle only snaps the
            // local-authoritative root with Entity#absSnapTo and immediately
            // echoes ServerboundMoveVehiclePacket.fromEntity(entity). That
            // packet-handler path never executes AbstractBoat#tick,
            // AbstractBoat#floatBoat, or AbstractBoat#controlBoat, so it cannot
            // create a new controlled-boat movement delta on its own.
            return start;
        }

        BoatTickResult tick = selectBoatTick(player, context, start, BoatTickState.capture(player), end);
        return start.with(tick.vector(), "boat inputs");
    }

    public static void commitAcceptedBoatTickState(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (context.getVehicle() == null || !EntityTypeUtil.isBoat(context.getVehicle().type)) return;

        BoatTickResult tick = selectBoatTick(
                player, context, result.getInitialStartingVel(), BoatTickState.capture(player), result.getTarget());
        BoatTickState state = tick.state();
        if (JavaFallDistance.runsFallDamageCallback(context.getVersion(), true)) {
            Vec3 stuck = context.getRequiredCurrentMoveStuckSpeed();
            boolean consumesStuck = context.consumesStuckSpeed() || stuck != null && stuck.lengthSqr() > 1.0E-7;
            state = state.withLastYd(state.nullifyNextY() || consumesStuck ? 0 : tick.vector().y);
        }
        var pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null && pass.result().boatLastYd() != null)
            state = state.withLastYd(pass.result().boatLastYd());
        state.apply(player);
        if (player.compensatedEntities.vehicles.reportsInterpolationRotation(context.getVehicle()))
            context.getVehicle()
                    .setClientPhysicalRotation(tick.postControlYaw(), context.getVehicle().clientPhysicalPitch);
    }

    /**
     * The yaw AbstractBoat#controlBoat leaves a driven boat with for {@code result}: the yaw it ended the last tick with
     * turned by this tick's deltaRotation. NaN when that last yaw is not known.
     */
    public static float predictedBoatYaw(CultPlayer player, PredictionResult result) {
        if (Float.isNaN(player.boatYawBeforeTurn)) return Float.NaN;
        BoatTickResult tick = selectBoatTick(
                player,
                result.getSimulationContext(),
                result.getInitialStartingVel(),
                BoatTickState.capture(player),
                result.getTarget());
        return player.boatYawBeforeTurn + tick.state().deltaRotation();
    }

    public static String debugBoatTick(CultPlayer player, SimulationContext context, Vec3 vector) {
        if (context.getVehicle() == null || !EntityTypeUtil.isBoat(context.getVehicle().type)) {
            return null;
        }

        BoatTickState state = BoatTickState.capture(player);
        String waterEntryProbe = debugWaterEntryProbe(player, context, state);
        Vec3 withCurrent = applyBoatWaterCurrent(player, context, vector);
        FloatBoatResult floated = floatBoat(player, context, withCurrent, state);
        BoatControlResult controlled = controlBoat(player, context, floated.vector(), floated.state());
        return "Boat tick debug: start=" + vector
                + " withCurrent=" + withCurrent
                + " floated=" + floated.vector()
                + " controlled=" + controlled.vector()
                + " yawStart=" + controlled.startYaw()
                + " yawEnd=" + controlled.postControlYaw()
                + " appliedDeltaRotation=" + controlled.deltaRotation()
                + " state[oldStatus=" + state.oldStatus()
                + ", status=" + state.status()
                + ", waterLevel=" + state.waterLevel()
                + ", landFriction=" + state.landFriction()
                + ", deltaRotation=" + state.deltaRotation()
                + ", lastYd=" + state.lastYd()
                + ", oldStatusMayBeInAir=" + state.oldStatusMayBeInAir()
                + ", waterEntryProbe=" + waterEntryProbe
                + "]";
    }

    public static boolean usesProvenWaterEntryPositionSnap(CultPlayer player, SimulationContext context) {
        if (context.getVehicle() == null || !EntityTypeUtil.isBoat(context.getVehicle().type)) {
            return false;
        }

        BoatTickState state = BoatTickState.capture(player);
        if (state.oldStatus() != BoatEntityStatus.IN_AIR
                || state.status() == BoatEntityStatus.IN_AIR
                || state.status() == BoatEntityStatus.ON_LAND) {
            return false;
        }

        SimpleCollisionBox boatBox = context.getFromMaximumExtent();
        double boatHeight = boatBox.maxY - boatBox.minY;
        double targetY = getWaterLevelAbove(player, boatBox, state.lastYd()) - boatHeight + 0.101D;
        SimpleCollisionBox movedBox = boatBox.copy().offset(0.0D, targetY - context.getStart().y, 0.0D);

        // MCP-Reborn AbstractBoat#floatBoat only zeroes deltaMovement.y when
        // the IN_AIR -> water snap path actually executes the noCollision-
        // guarded setPos/setDeltaMovement block. Without that proven snap, the
        // later Entity#move still consumes the full carried stuck-speed vector.
        return waterEntrySnapHasNoCollision(player, context, movedBox);
    }

    private static Vec3 applyBoatWaterCurrent(CultPlayer player, SimulationContext context, Vec3 vector) {
        // MCP-Reborn AbstractBoat#tick calls super.tick() before floatBoat(); Entity#baseTick
        // applies EntityFluidInteraction water current there. AbstractBoat#getStatus() and
        // Entity#baseTick run before interpolation, so current must sample the same
        // pre-interpolation fluid box captured with the boat status.
        // Boats are Entity, not Player, so EntityFluidInteraction.Tracker#applyCurrentTo
        // normalizes accumulated current instead of averaging it like LocalPlayer movement.
        SimpleCollisionBox fluidBox = player.boatData.fluidInteractionBox == null
                ? context.getFromMaximumExtent()
                : player.boatData.fluidInteractionBox;
        return applyBoatBaseTickWaterCurrent(player, fluidBox, vector);
    }

    private static Vec3 applyBoatBaseTickWaterCurrent(CultPlayer player, SimpleCollisionBox fluidBox, Vec3 vector) {
        Vec3 current = WaterCurrent.calculateNonPlayerWaterCurrent(player, fluidBox, vector);
        return current == null ? vector : vector.add(current);
    }

    private static FloatBoatResult floatBoat(
            CultPlayer player, SimulationContext context, Vec3 vector, BoatTickState state) {
        SimpleCollisionBox boatBox = context.getFromMaximumExtent();
        double boatY = context.getStart().y;
        double boatHeight = boatBox.maxY - boatBox.minY;
        boolean doubleGravity = context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
        double gravity = doubleGravity ? 0.04D : (double) 0.04F;
        double d1 = player.compensatedEntities.getEntityInControl().hasGravity ? -gravity : 0;
        double d2 = 0.0D;
        float invFriction = 0.05F;

        if (state.oldStatus() == BoatEntityStatus.IN_AIR
                && state.status() != BoatEntityStatus.IN_AIR
                && state.status() != BoatEntityStatus.ON_LAND) {
            double waterLevel = boatY + boatHeight;
            double targetY = getWaterLevelAbove(player, boatBox, state.lastYd()) - boatHeight + 0.101D;
            Vec3 snapVector = new Vec3(vector.x, targetY - boatY, vector.z);
            SimpleCollisionBox movedBox = boatBox.copy().offset(0.0D, targetY - boatY, 0.0D);

            // MCP-Reborn AbstractBoat#floatBoat only applies the water-entry
            // position snap and Y-velocity nullification when Level#noCollision
            // accepts the boat's moved bounding box. CollisionGetter#noCollision
            // checks block, entity, and border collisions; a boat cannot snap down
            // through a collidable passenger-external entity.
            if (waterEntrySnapHasNoCollision(player, context, movedBox)) {
                return new FloatBoatResult(snapVector, state.withWaterEntrySnap(waterLevel));
            }

            return new FloatBoatResult(vector, state.withWaterEntryBlocked(waterLevel));
        } else {
            if (state.status() == BoatEntityStatus.IN_WATER) {
                d2 = (state.waterLevel() - boatY) / boatHeight;
                invFriction = 0.9F;
            } else if (state.status() == BoatEntityStatus.UNDER_FLOWING_WATER) {
                d1 = -7.0E-4D;
                invFriction = 0.9F;
            } else if (state.status() == BoatEntityStatus.UNDER_WATER) {
                d2 = 0.01F;
                invFriction = 0.45F;
            } else if (state.status() == BoatEntityStatus.IN_AIR) {
                invFriction = 0.9F;
            } else if (state.status() == BoatEntityStatus.ON_LAND) {
                invFriction = state.landFriction();
            }

            vector = new Vec3(vector.x * invFriction, vector.y + d1, vector.z * invFriction);

            if (d2 > 0.0D) {
                // floatBoat uses the DOUBLE default gravity even when NoGravity makes getGravity zero.
                double buoyancy = doubleGravity ? 0.04D / 0.65D : 0.06153846016296973D;
                vector = new Vec3(vector.x, (vector.y + d2 * buoyancy) * 0.75D, vector.z);
            }
        }

        // MCP-Reborn AbstractBoat#floatBoat multiplies deltaRotation by the same
        // status friction applied to horizontal velocity.
        return new FloatBoatResult(vector, state.withDeltaRotation(state.deltaRotation() * invFriction));
    }

    private static BoatTickResult selectBoatTick(
            CultPlayer player, SimulationContext context, Vec3 vector, BoatTickState state, Vec3 target) {
        BoatTickResult selected = computeBoatTick(player, context, vector, state);
        if (state.oldStatusMayBeInAir()
                && state.oldStatus() != BoatEntityStatus.IN_AIR
                && state.status() != BoatEntityStatus.IN_AIR
                && state.status() != BoatEntityStatus.ON_LAND) {
            // MCP-Reborn ClientPacketListener#setValuesFromPositionPacket snaps
            // a boat without recomputing AbstractBoat.status. If local control
            // begins before the next boat tick, the first tick can still copy
            // a pre-packet IN_AIR status into oldStatus and take the exact
            // AbstractBoat#floatBoat water-entry snap branch.
            BoatTickResult airOldStatus =
                    computeBoatTick(player, context, vector, state.withOldStatus(BoatEntityStatus.IN_AIR));
            if (airOldStatus.vector().distanceToSqr(target) < selected.vector().distanceToSqr(target)) {
                selected = airOldStatus;
            }
        }
        if (state.oldStatusMayBeInAir()
                && state.oldStatus() == BoatEntityStatus.IN_AIR
                && state.status() != BoatEntityStatus.IN_AIR
                && state.status() != BoatEntityStatus.ON_LAND) {
            // MCP-Reborn stores AbstractBoat.oldStatus/status on the client boat
            // entity, while ClientPacketListener#handleMoveVehicle can absSnapTo a
            // local-authoritative boat between ticks without recomputing status. Only
            // that packet-phase snap leaves the previous hidden boat tick ambiguous;
            // otherwise AbstractBoat#tick has already copied the exact prior status
            // into oldStatus before sampling the current box.
            BoatTickResult noSnapOldStatus =
                    computeBoatTick(player, context, vector, state.withOldStatus(state.status()));
            if (noSnapOldStatus.vector().distanceToSqr(target)
                    < selected.vector().distanceToSqr(target)) {
                selected = noSnapOldStatus;
            }
        }
        return selected;
    }

    private static BoatTickResult computeBoatTick(
            CultPlayer player, SimulationContext context, Vec3 vector, BoatTickState state) {
        vector = applyBoatWaterCurrent(player, context, vector);
        // MCP-Reborn AbstractBoat#getWaterLevelAbove uses AbstractBoat.lastYd.
        // checkFallDamage stores that from getDeltaMovement().y, not from the
        // actual position delta; water-entry snaps can move Y while lastYd is 0.
        FloatBoatResult floated = floatBoat(player, context, vector, state);
        BoatTickState postFloatState = floated.state();
        BoatControlResult controlled = controlBoat(player, context, floated.vector(), postFloatState);
        return new BoatTickResult(
                controlled.vector(),
                postFloatState.withDeltaRotation(controlled.deltaRotation()),
                controlled.postControlYaw());
    }

    private static boolean waterEntrySnapHasNoCollision(
            CultPlayer player, SimulationContext context, SimpleCollisionBox movedBox) {
        return findFirstWaterEntryIntersection(player, context, movedBox) == null;
    }

    private static String debugWaterEntryProbe(CultPlayer player, SimulationContext context, BoatTickState state) {
        if (state.oldStatus() != BoatEntityStatus.IN_AIR
                || state.status() == BoatEntityStatus.IN_AIR
                || state.status() == BoatEntityStatus.ON_LAND) {
            return "n/a";
        }

        SimpleCollisionBox boatBox = context.getFromMaximumExtent();
        double boatHeight = boatBox.maxY - boatBox.minY;
        double targetY = getWaterLevelAbove(player, boatBox, state.lastYd()) - boatHeight + 0.101D;
        SimpleCollisionBox movedBox = boatBox.copy().offset(0.0D, targetY - context.getStart().y, 0.0D);
        SimpleCollisionBox blocker = findFirstWaterEntryIntersection(player, context, movedBox);
        return blocker == null ? "clear" : blocker.toString();
    }

    private static SimpleCollisionBox findFirstWaterEntryIntersection(
            CultPlayer player, SimulationContext context, SimpleCollisionBox movedBox) {
        List<SimpleCollisionBox> collisions = new ArrayList<>();
        Collisions.getCollisionBoxes(player, movedBox, collisions, false, movedBox.minY);

        for (SimpleCollisionBox collision : collisions) {
            if (collision.isIntersected(movedBox)) {
                return collision;
            }
        }

        return null;
    }

    public static float getWaterLevelAbove(CultPlayer player, SimpleCollisionBox axisalignedbb, double lastYd) {
        int i = (int) Math.floor(axisalignedbb.minX);
        int j = (int) Math.ceil(axisalignedbb.maxX);
        int k = (int) Math.floor(axisalignedbb.maxY);
        int l = (int) Math.ceil(axisalignedbb.maxY - lastYd);
        int i1 = (int) Math.floor(axisalignedbb.minZ);
        int j1 = (int) Math.ceil(axisalignedbb.maxZ);

        label39:
        for (int k1 = k; k1 < l; ++k1) {
            float f = 0.0F;

            for (int l1 = i; l1 < j; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    double level = player.compensatedWorld.getWaterFluidLevelAt(l1, k1, i2);

                    f = (float) Math.max(f, level);

                    if (f >= 1.0F) {
                        continue label39;
                    }
                }
            }

            if (f < 1.0F) {
                return (float) k1 + f;
            }
        }

        return (float) (l + 1);
    }

    private static BoatControlResult controlBoat(
            CultPlayer player, SimulationContext context, Vec3 vector, BoatTickState state) {
        KnownInput inputs = context.getBoatInputs();
        float deltaRotation = applyBoatControlDelta(state.deltaRotation(), inputs);
        float startYaw;
        float postControlYaw;
        if (player.compensatedEntities.vehicles.reportsInterpolationRotation(context.getVehicle())) {
            startYaw = context.getVehicle().clientPhysicalYaw;
            postControlYaw = startYaw + deltaRotation;
        } else if (player.packetStateData.isVehicleMovementFromClientTick()) {
            // ServerboundMoveVehiclePacket#fromEntity serializes the root boat
            // after AbstractBoat#controlBoat has already added deltaRotation.
            postControlYaw = context.getXRot();
            startYaw = postControlYaw - deltaRotation;
        } else {
            startYaw = getControlledBoatYaw(context);
            postControlYaw = startYaw + deltaRotation;
        }
        return new BoatControlResult(
                thrust(player, inputs, vector, postControlYaw), deltaRotation, startYaw, postControlYaw);
    }

    private static Vec3 thrust(CultPlayer player, KnownInput inputs, Vec3 vector, float postControlYaw) {
        float f = 0.0F;
        if (inputs.right() != inputs.left() && !inputs.forward() && !inputs.backward()) {
            f += 0.005F;
        }

        if (inputs.forward()) {
            f += 0.04F;
        }

        if (inputs.backward()) {
            f -= 0.005F;
        }

        // MCP-Reborn AbstractBoat#controlBoat applies left/right input to
        // deltaRotation, then updates the boat entity's yRot, and only then adds
        // forward/backward acceleration using the boat entity yaw. LocalPlayer#tick
        // sends player Rot separately from ServerboundMoveVehiclePacket, so boat
        // thrust must follow the controlled boat yaw, not the passenger look yaw.
        return vector.add(new Vec3(
                player.trigHandler.sin(-postControlYaw * ((float) Math.PI / 180F)) * f,
                0,
                player.trigHandler.cos(postControlYaw * ((float) Math.PI / 180F)) * f));
    }

    /**
     * One AbstractBoat#tick up to Entity#move: {@code velocity} is the delta Entity#move receives, {@code snapY}
     * floatBoat's water-entry setPos height change (0 without one) and {@code yaw} the yaw controlBoat left.
     */
    public record UnreportedTick(Vec3 velocity, double snapY, float yaw) {}

    /**
     * AbstractBoat#tick up to Entity#move for a tick no MoveVehicle reports, from Entity#rideTick's zero delta:
     * Entity#baseTick's water current, floatBoat, and controlBoat turning the boat from {@code yaw}, its own yaw.
     * BoatData must already hold the status this tick sampled; the tick's water level, steering and lastYd are stored
     * there, as commitAcceptedBoatTickState stores a reported tick's.
     */
    public static UnreportedTick unreportedTick(CultPlayer player, SimulationContext context, float yaw) {
        Vec3 current = applyBoatWaterCurrent(player, context, Vec3.ZERO);
        FloatBoatResult floated = floatBoat(player, context, current, BoatTickState.capture(player));
        float deltaRotation = applyBoatControlDelta(floated.state().deltaRotation(), context.getBoatInputs());
        float postControlYaw = yaw + deltaRotation;
        Vec3 controlled = thrust(player, context.getBoatInputs(), floated.vector(), postControlYaw);
        BoatTickState state = floated.state().withDeltaRotation(deltaRotation);
        state.apply(player);
        // The water-entry branch's setPos is position only; it also cleared the Y delta Entity#move receives.
        return state.nullifyNextY()
                ? new UnreportedTick(new Vec3(controlled.x, 0, controlled.z), controlled.y, postControlYaw)
                : new UnreportedTick(controlled, 0, postControlYaw);
    }

    private static float getControlledBoatYaw(SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        if (vehicle instanceof PacketEntityTrackXRot xRotVehicle) {
            return xRotVehicle.interpYaw;
        }

        return context.getXRot();
    }

    private static float applyBoatControlDelta(float deltaRotation, KnownInput inputs) {
        // MCP-Reborn AbstractBoat#controlBoat decrements deltaRotation for left
        // input and increments it for right input before applying forward
        // acceleration; holding both applies both.
        if (inputs.left()) {
            --deltaRotation;
        }

        if (inputs.right()) {
            ++deltaRotation;
        }

        return deltaRotation;
    }

    private record BoatTickResult(Vec3 vector, BoatTickState state, float postControlYaw) {}

    private record FloatBoatResult(Vec3 vector, BoatTickState state) {}

    private record BoatControlResult(Vec3 vector, float deltaRotation, float startYaw, float postControlYaw) {}

    public record BoatTickState(
            BoatEntityStatus oldStatus,
            BoatEntityStatus status,
            double waterLevel,
            float landFriction,
            float deltaRotation,
            boolean nullifyNextY,
            double lastYd,
            boolean oldStatusMayBeInAir) {
        public static BoatTickState capture(CultPlayer player) {
            return new BoatTickState(
                    player.boatData.oldStatus,
                    player.boatData.status,
                    player.boatData.waterLevel,
                    player.boatData.landFriction,
                    player.boatData.deltaRotation,
                    player.boatData.nullifyNextY,
                    player.boatData.lastYd,
                    player.boatData.oldStatusMayBeInAir);
        }

        private BoatTickState withOldStatus(BoatEntityStatus oldStatus) {
            return new BoatTickState(
                    oldStatus,
                    status,
                    waterLevel,
                    landFriction,
                    deltaRotation,
                    nullifyNextY,
                    lastYd,
                    oldStatusMayBeInAir);
        }

        private BoatTickState withDeltaRotation(float deltaRotation) {
            return new BoatTickState(
                    oldStatus,
                    status,
                    waterLevel,
                    landFriction,
                    deltaRotation,
                    nullifyNextY,
                    lastYd,
                    oldStatusMayBeInAir);
        }

        private BoatTickState withLastYd(double lastYd) {
            return new BoatTickState(
                    oldStatus,
                    status,
                    waterLevel,
                    landFriction,
                    deltaRotation,
                    nullifyNextY,
                    lastYd,
                    oldStatusMayBeInAir);
        }

        private BoatTickState withWaterEntrySnap(double waterLevel) {
            return new BoatTickState(
                    oldStatus,
                    BoatEntityStatus.IN_WATER,
                    waterLevel,
                    landFriction,
                    deltaRotation,
                    true,
                    0.0D,
                    oldStatusMayBeInAir);
        }

        private BoatTickState withWaterEntryBlocked(double waterLevel) {
            return new BoatTickState(
                    oldStatus,
                    BoatEntityStatus.IN_WATER,
                    waterLevel,
                    landFriction,
                    deltaRotation,
                    false,
                    lastYd,
                    oldStatusMayBeInAir);
        }

        public void apply(CultPlayer player) {
            player.boatData.status = status;
            player.boatData.waterLevel = waterLevel;
            player.boatData.deltaRotation = deltaRotation;
            player.boatData.nullifyNextY = nullifyNextY;
            player.boatData.lastYd = lastYd;
            player.boatData.oldStatusMayBeInAir = false;
        }
    }
}
