package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import java.util.ArrayList;
import java.util.List;

/** Complete states attached to the runner's existing next-tick velocity candidates. */
public record JavaPredictionCarry(PacketEntity actor, double fallDistance, List<State> states, boolean powderContact)
        implements PredictionCarry {
    public JavaPredictionCarry(PacketEntity actor, double fallDistance, List<State> states) {
        this(actor, fallDistance, states, !states.isEmpty() && states.getFirst().powderContact());
    }

    public JavaPredictionCarry(PacketEntity actor, double fallDistance) {
        this(actor, fallDistance, List.of());
    }

    public JavaPredictionCarry {
        requireDistance(fallDistance);
        states = List.copyOf(states);
    }

    /** LocalPlayer#onGameModeChanged changes only deltaMovement.Y of every carried state. */
    public JavaPredictionCarry withVelocityY(double y) {
        var next = new ArrayList<State>(states.size());
        for (var state : states)
            next.add(new State(
                    state.velocity().with(ac.cult.cultac.protocol.value.Direction.Axis.Y, y),
                    state.fallDistance(),
                    state.stuckSpeed(),
                    state.movementPatch(),
                    state.pistonStart(),
                    state.powderContact(),
                    state.playerEyeFluidContact(),
                    state.fluidTagOrder(),
                    state.spinRebound()));
        return withStates(next);
    }

    public JavaPredictionCarry withStates(List<State> states) {
        return new JavaPredictionCarry(actor, fallDistance, states, powderContact);
    }

    /** A received packet replaces ground; Entity#checkSupportingBlock(false, null) also clears support. */
    public JavaPredictionCarry withPacketGround(boolean ground) {
        List<State> next = null;
        for (int i = 0; i < states.size(); i++) {
            State state = states.get(i);
            PistonStart start = state.pistonStart();
            if (start == null) continue;
            boolean supportCleared = start.support() != null
                    && start.support().getBlockPos() == null && !start.support().isOnGround();
            if (start.onGround() == ground && (ground || supportCleared)) continue;
            if (next == null) next = new ArrayList<>(states);
            next.set(i, new State(state.velocity(), state.fallDistance(), state.stuckSpeed(), state.movementPatch(),
                    new PistonStart(start.position(), ground,
                            ground ? start.support() : new MainSupportingBlockData(null, false), start.pistonDeltas()),
                    state.powderContact(), state.playerEyeFluidContact(), state.fluidTagOrder(), state.spinRebound()));
        }
        return next == null ? this : withStates(next);
    }

    /** PositionMoveRotation#calculateAbsolute starts relative coordinates at the position each piston state holds. */
    public TeleportMatch matchRelativeTeleport(TeleportData teleport, Vec3 reported, double threshold) {
        if (!(teleport.isRelativeX() || teleport.isRelativeY() || teleport.isRelativeZ())
                || states.isEmpty()
                || states.stream().anyMatch(state -> state.pistonStart() == null)) return null;
        var matching = new ArrayList<State>();
        Vec3 endpoint = null;
        Vec3 change = teleport.getLocation();
        for (var state : states) {
            Vec3 from = state.pistonStart().position();
            Vec3 to = VectorUtils.clampVector(new Vec3(
                    change.x + (teleport.isRelativeX() ? from.x : 0),
                    change.y + (teleport.isRelativeY() ? from.y : 0),
                    change.z + (teleport.isRelativeZ() ? from.z : 0)));
            if (Math.abs(to.x - reported.x) > (teleport.isRelativeX() ? threshold : 0)
                    || Math.abs(to.y - reported.y) > (teleport.isRelativeY() ? threshold : 0)
                    || Math.abs(to.z - reported.z) > (teleport.isRelativeZ() ? threshold : 0)) continue;
            matching.add(state);
            if (endpoint == null) endpoint = to;
        }
        return new TeleportMatch(
                endpoint,
                new JavaPredictionCarry(
                        actor,
                        matching.isEmpty() ? fallDistance : matching.getFirst().fallDistance(),
                        matching));
    }

    public record TeleportMatch(Vec3 position, JavaPredictionCarry carry) {
        public boolean matched() {
            return !carry.states().isEmpty();
        }
    }

    /** ClientPacketListener#setValuesFromPositionPacket replaces or rotates the whole stored delta movement. */
    public JavaPredictionCarry afterTeleport(TeleportData teleport) {
        var transformed = new ArrayList<State>(states.size());
        for (State state : states) {
            PistonStart anchored = state.pistonStart();
            var next = new State(
                    teleport.applyToVelocity(state.velocity()),
                    state.fallDistance(),
                    state.stuckSpeed(),
                    state.movementPatch(),
                    anchored == null
                            ? null
                            : new PistonStart(
                                    teleport.getLocation(),
                                    anchored.onGround(),
                                    anchored.support(),
                                    anchored.pistonDeltas()),
                    state.powderContact(),
                    state.playerEyeFluidContact(),
                    state.fluidTagOrder(),
                    state.spinRebound());
            if (!transformed.contains(next)) transformed.add(next);
        }
        return new JavaPredictionCarry(actor, fallDistance, transformed, powderContact);
    }

    /** A protocol-773 pass can leave two exact starting positions before the following report distinguishes them. */
    public record PistonStart(
            Vec3 position, boolean onGround, MainSupportingBlockData support, ClientPistonDeltas pistonDeltas) {}

    /** spinRebound records a completed hit; velocity already includes its rebound and every subsequent write. */
    public record State(
            Vec3 velocity,
            double fallDistance,
            Vec3 stuckSpeed,
            JavaMovementPatch movementPatch,
            PistonStart pistonStart,
            boolean powderContact,
            int playerEyeFluidContact,
            ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder fluidTagOrder,
            boolean spinRebound) {
        public State(
                Vec3 velocity,
                double fallDistance,
                Vec3 stuckSpeed,
                JavaMovementPatch movementPatch,
                PistonStart pistonStart,
                boolean powderContact,
                int playerEyeFluidContact,
                ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder fluidTagOrder) {
            this(velocity, fallDistance, stuckSpeed, movementPatch, pistonStart, powderContact,
                    playerEyeFluidContact, fluidTagOrder, false);
        }
        public State(
                Vec3 velocity,
                double fallDistance,
                Vec3 stuckSpeed,
                JavaMovementPatch movementPatch,
                PistonStart pistonStart,
                boolean powderContact,
                int playerEyeFluidContact) {
            this(
                    velocity,
                    fallDistance,
                    stuckSpeed,
                    movementPatch,
                    pistonStart,
                    powderContact,
                    playerEyeFluidContact,
                    null);
        }
        /** This state carrying {@code velocity} instead. */
        public State withVelocity(Vec3 velocity) {
            return new State(velocity, fallDistance, stuckSpeed, movementPatch, pistonStart, powderContact,
                    playerEyeFluidContact, fluidTagOrder, spinRebound);
        }

        /** WATER membership at refresh; the raw cache is the state carried into later queries. */
        public boolean playerEyeInWater() {
            return (playerEyeFluidContact & 1) != 0;
        }

        public State(
                Vec3 velocity,
                double fallDistance,
                Vec3 stuckSpeed,
                JavaMovementPatch movementPatch,
                PistonStart pistonStart,
                boolean powderContact,
                boolean playerEyeInWater) {
            this(
                    velocity,
                    fallDistance,
                    stuckSpeed,
                    movementPatch,
                    pistonStart,
                    powderContact,
                    ac.cult.cultac.utils.nmsutil.ClientFluidQueries.eyeContactFromWater(playerEyeInWater));
        }

        public State(
                Vec3 velocity,
                double fallDistance,
                Vec3 stuckSpeed,
                JavaMovementPatch movementPatch,
                PistonStart pistonStart,
                boolean powderContact) {
            this(velocity, fallDistance, stuckSpeed, movementPatch, pistonStart, powderContact, 0);
        }

        public State(
                Vec3 velocity,
                double fallDistance,
                Vec3 stuckSpeed,
                JavaMovementPatch movementPatch,
                PistonStart pistonStart) {
            this(velocity, fallDistance, stuckSpeed, movementPatch, pistonStart, false);
        }

        public State(Vec3 velocity, double fallDistance, Vec3 stuckSpeed) {
            this(velocity, fallDistance, stuckSpeed, null, null);
        }

        public State withPatch(JavaMovementPatch patch) {
            return new State(
                    velocity,
                    fallDistance,
                    stuckSpeed,
                    patch,
                    pistonStart,
                    powderContact,
                    playerEyeFluidContact,
                    fluidTagOrder,
                    spinRebound);
        }

        public State withFluidTagOrder(ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder order) {
            return new State(
                    velocity,
                    fallDistance,
                    stuckSpeed,
                    movementPatch,
                    pistonStart,
                    powderContact,
                    playerEyeFluidContact,
                    order,
                    spinRebound);
        }

        public State {
            requireDistance(fallDistance);
        }
    }

    private static void requireDistance(double distance) {
        if (!Double.isFinite(distance) || distance < 0)
            throw new IllegalArgumentException("Invalid client fall distance");
    }
}
