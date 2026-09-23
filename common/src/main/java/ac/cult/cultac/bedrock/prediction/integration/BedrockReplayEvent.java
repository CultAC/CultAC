package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;

/** Value-only, ordered changes to a historical movement frame. */
public sealed interface BedrockReplayEvent permits BedrockReplayAttributeEvent, BedrockReplayEvent.Motion,
        BedrockReplayEvent.Transform, BedrockReplayEvent.Reposition, BedrockReplayEvent.Metadata, BedrockReplayEvent.Boost,
        BedrockReplayEvent.HorseMetadata, BedrockReplayBoatMetadata, BedrockReplayContextEvent {
    default BedrockMovementState state(BedrockMovementState state) { return state; }
    /** Packet-family fallback when the timestamp is older than retained client history. */
    default BedrockReplayEvent ordinary() { return this; }
    default boolean matchesHistory(BedrockMovementState state) { return false; }
    default BedrockSimulation.Input input(BedrockSimulation.Input input, long elapsed) { return input; }

    default BedrockSimulation.Input restoreInput(BedrockSimulation.Input input, BedrockSimulation.Input recorded) { return input; }

    record Motion(Vec3d velocity) implements BedrockReplayEvent {
        @Override public BedrockMovementState state(BedrockMovementState state) {
            return state.withVelocityAndCollisionFlags(velocity, state.collisionFlags());
        }
    }

    record Transform(BedrockMovementCorrection correction) implements BedrockReplayEvent {
        @Override public BedrockMovementState state(BedrockMovementState state) {
            var next = state.withCoordinateFrame(correction.coordinates())
                    .withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(correction.position()), 0)
                    .withVelocityAndCollisionFlags(BedrockVectorAdapter.toBedrock(correction.velocity()),
                            state.collisionFlags().withTeleportOnGround(correction.onGround()));
            if (correction.vehicle()) next = next.withRotation(correction.yaw(), correction.pitch());
            return next.isBoat() && correction.angularVelocity() != null
                    ? next.withBoat(next.boat().withAngularVelocity(correction.angularVelocity())) : next;
        }
    }

    record Reposition(Vec3d position, float yaw, float pitch, boolean onGround,
                      ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame coordinates) implements BedrockReplayEvent {
        @Override public BedrockMovementState state(BedrockMovementState state) {
            return state.withCoordinateFrame(coordinates).withPhysicalFeetPosition(position, 0)
                    .withRotation(yaw, pitch).withVelocityAndCollisionFlags(state.velocity(),
                            state.collisionFlags().withTeleportOnGround(onGround));
        }
    }

    record Metadata(Float width, Float height, Boolean gliding, Boolean crawling, Boolean swimming,
                    Boolean spinning, Boolean sprinting) implements BedrockReplayEvent {
        static Metadata flagsOf(BedrockMovementState state) {
            return new Metadata(null, null, state.gliding(), state.horizontalPose(), state.swimming(),
                    state.riptideSpinActive(), state.sprinting());
        }

        Metadata changedFlags(Metadata before) {
            return new Metadata(null, null,
                    java.util.Objects.equals(gliding, before.gliding) ? null : gliding,
                    java.util.Objects.equals(crawling, before.crawling) ? null : crawling,
                    java.util.Objects.equals(swimming, before.swimming) ? null : swimming,
                    java.util.Objects.equals(spinning, before.spinning) ? null : spinning,
                    java.util.Objects.equals(sprinting, before.sprinting) ? null : sprinting);
        }

        boolean hasFlags() {
            return gliding != null || crawling != null || swimming != null || spinning != null || sprinting != null;
        }

        @Override public BedrockMovementState state(BedrockMovementState state) {
            if (gliding != null) state = state.withAcknowledgedGliding(gliding);
            if (sprinting != null) state = state.withSprinting(sprinting);
            state = state.withAcknowledgedPose(crawling, swimming, spinning);
            if (width != null || height != null) state = state.withPlayerDimensions(new PlayerDimensionsState(
                    width == null ? state.playerDimensions().width() : width,
                    height == null ? state.playerDimensions().height() : height), true);
            return state;
        }
    }

    record HorseMetadata(Boolean standing, Float width, Float height) implements BedrockReplayEvent {
        @Override public BedrockMovementState state(BedrockMovementState state) {
            if (!state.isHorse()) return state;
            var h = state.horse();
            if (standing != null) state = state.withHorse(new ac.cult.cultac.bedrock.prediction.state.BedrockHorseState(
                    h.actor(), h.jumping(), h.pendingJump(), standing, h.allowStandSliding(), h.metadataRevision(),
                    h.release(), h.forwardJump(), h.standAmount()));
            if (width != null || height != null) state = state.withPlayerDimensions(new PlayerDimensionsState(
                    width == null ? state.playerDimensions().width() : width,
                    height == null ? state.playerDimensions().height() : height), true);
            return state;
        }
    }

    record Boost(int duration) implements BedrockReplayEvent {
        public Boost {
            if (duration < -1) duration = 0;
        }
        @Override public BedrockSimulation.Input restoreInput(BedrockSimulation.Input input, BedrockSimulation.Input recorded) {
            return new BedrockSimulation.Input(input.previousState(), input.frame(), input.intent(), input.snapshot(),
                    input.canStep(), input.maxUpStep(), input.mobJumpComponent(), input.actorMovementTick(),
                    input.acceptedTeleport(), input.control(), recorded.glideBoost());
        }
        @Override public BedrockSimulation.Input input(BedrockSimulation.Input input, long elapsed) {
            return new BedrockSimulation.Input(input.previousState(), input.frame(), input.intent(), input.snapshot(),
                    input.canStep(), input.maxUpStep(), input.mobJumpComponent(), input.actorMovementTick(),
                    input.acceptedTeleport(), input.control(), duration == -1 || elapsed < Math.max(1, duration));
        }
    }
}
