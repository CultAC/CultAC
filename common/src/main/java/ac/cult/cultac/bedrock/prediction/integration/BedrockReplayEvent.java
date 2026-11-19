package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimberFlags;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;

/** Value-only, ordered changes to a historical movement frame. */
public sealed interface BedrockReplayEvent
        permits BedrockReplayAttributeEvent,
                BedrockReplayEvent.Motion,
                BedrockReplayEvent.Transform,
                BedrockReplayEvent.Reposition,
                BedrockReplayEvent.Metadata,
                BedrockReplayEvent.Boost,
                BedrockReplayEvent.GeyserBoost,
                BedrockReplayEvent.HorseMetadata,
                BedrockReplayBoatMetadata,
                BedrockReplayContextEvent {
    default BedrockMovementState state(BedrockMovementState state) {
        return state;
    }

    default BedrockMovementState replayState(BedrockMovementState state) {
        return state(state);
    }
    /** Packet-family fallback when the timestamp is older than retained client history. */
    default BedrockReplayEvent ordinary() {
        return this;
    }

    default boolean matchesHistory(BedrockMovementState state) {
        return false;
    }

    default BedrockSimulation.Input input(BedrockSimulation.Input input, long elapsed) {
        return input;
    }

    default BedrockSimulation.Input restoreInput(BedrockSimulation.Input input, BedrockSimulation.Input recorded) {
        return input;
    }

    record Motion(Vec3d velocity) implements BedrockReplayEvent {
        @Override
        public BedrockMovementState state(BedrockMovementState state) {
            return state.withVelocityAndCollisionFlags(velocity, state.collisionFlags());
        }
    }

    record Transform(BedrockMovementCorrection correction) implements BedrockReplayEvent {
        @Override
        public BedrockMovementState state(BedrockMovementState state) {
            var next = state.withCoordinateFrame(correction.coordinates())
                    .withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(correction.position()), 0)
                    .withVelocityAndCollisionFlags(
                            BedrockVectorAdapter.toBedrock(correction.velocity()),
                            state.collisionFlags().withTeleportOnGround(correction.onGround()));
            if (correction.vehicle()) next = next.withRotation(correction.yaw(), correction.pitch());
            return next.isBoat() && correction.angularVelocity() != null
                    ? next.withBoat(next.boat().withAngularVelocity(correction.angularVelocity()))
                    : next;
        }
    }

    record Reposition(
            Vec3d position,
            float yaw,
            float pitch,
            boolean onGround,
            ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame coordinates)
            implements BedrockReplayEvent {
        @Override
        public BedrockMovementState state(BedrockMovementState state) {
            return state.withCoordinateFrame(coordinates)
                    .withPhysicalFeetPosition(position, 0)
                    .withRotation(yaw, pitch)
                    .withVelocityAndCollisionFlags(
                            state.velocity(), state.collisionFlags().withTeleportOnGround(onGround));
        }
    }

    record Metadata(
            Float width,
            Float height,
            Boolean gliding,
            Boolean crawling,
            Boolean swimming,
            Boolean spinning,
            Boolean sprinting,
            PlayerDimensionsState scaledDefinition,
            // The client recomputes these after its next move, so they are neither tracked nor replayed.
            BedrockClimberFlags climberFlags)
            implements BedrockReplayEvent {
        public Metadata(
                Float width,
                Float height,
                Boolean gliding,
                Boolean crawling,
                Boolean swimming,
                Boolean spinning,
                Boolean sprinting) {
            this(width, height, gliding, crawling, swimming, spinning, sprinting, null);
        }

        public Metadata(
                Float width,
                Float height,
                Boolean gliding,
                Boolean crawling,
                Boolean swimming,
                Boolean spinning,
                Boolean sprinting,
                PlayerDimensionsState scaledDefinition) {
            this(width, height, gliding, crawling, swimming, spinning, sprinting, scaledDefinition, null);
        }

        static Metadata trackedValuesOf(BedrockMovementState state) {
            var dimensions = metadataDimensions(state);
            return new Metadata(
                    (float) dimensions.width(),
                    (float) dimensions.height(),
                    state.gliding(),
                    state.horizontalPose(),
                    state.swimming(),
                    state.riptideSpinActive(),
                    state.sprinting(),
                    state.collisionDefinition());
        }

        static PlayerDimensionsState metadataDimensions(BedrockMovementState state) {
            return state.acknowledgedPlayerDimensions() == null
                    ? state.playerDimensions()
                    : state.acknowledgedPlayerDimensions();
        }

        Metadata changedValues(Metadata before) {
            var flags = changedFlags(before);
            boolean resized =
                    !java.util.Objects.equals(width, before.width) || !java.util.Objects.equals(height, before.height);
            return new Metadata(
                    resized ? width : null,
                    resized ? height : null,
                    flags.gliding,
                    flags.crawling,
                    flags.swimming,
                    flags.spinning,
                    flags.sprinting,
                    java.util.Objects.equals(scaledDefinition, before.scaledDefinition) ? null : scaledDefinition);
        }

        boolean hasValues() {
            return hasFlags() || width != null || height != null || scaledDefinition != null;
        }

        Metadata changedFlags(Metadata before) {
            return new Metadata(
                    null,
                    null,
                    java.util.Objects.equals(gliding, before.gliding) ? null : gliding,
                    java.util.Objects.equals(crawling, before.crawling) ? null : crawling,
                    java.util.Objects.equals(swimming, before.swimming) ? null : swimming,
                    java.util.Objects.equals(spinning, before.spinning) ? null : spinning,
                    java.util.Objects.equals(sprinting, before.sprinting) ? null : sprinting);
        }

        boolean hasFlags() {
            return gliding != null || crawling != null || swimming != null || spinning != null || sprinting != null;
        }

        @Override
        public BedrockMovementState state(BedrockMovementState state) {
            state = replayState(state);
            if (climberFlags != null && !state.isVehicle()) state = state.withClimberFlags(climberFlags);
            return width == null && height == null
                    ? state
                    : state.withPlayerDimensions(metadataDimensions(state), true);
        }

        @Override
        public BedrockMovementState replayState(BedrockMovementState state) {
            if (gliding != null) state = state.withAcknowledgedGliding(gliding);
            if (sprinting != null) state = state.withSprinting(sprinting);
            state = state.withAcknowledgedPose(crawling, swimming, spinning);
            // A scale change resizes the client's box when it is received, in live ticks and in replays alike.
            if (scaledDefinition != null && !state.isVehicle()) {
                state = BedrockActorDimensions.withCollisionDefinition(state, scaledDefinition);
            }
            if (width != null || height != null) {
                var dimensions = metadataDimensions(state);
                state = state.withAcknowledgedPlayerDimensions(new PlayerDimensionsState(
                        width == null ? dimensions.width() : width, height == null ? dimensions.height() : height));
            }
            return state;
        }
    }

    record HorseMetadata(Boolean standing, Float width, Float height) implements BedrockReplayEvent {
        @Override
        public BedrockMovementState state(BedrockMovementState state) {
            if (!state.isHorse()) return state;
            var h = state.horse();
            if (standing != null)
                state = state.withHorse(new ac.cult.cultac.bedrock.prediction.state.BedrockHorseState(
                        h.actor(),
                        h.jumping(),
                        h.pendingJump(),
                        standing,
                        h.allowStandSliding(),
                        h.metadataRevision(),
                        h.release(),
                        h.forwardJump(),
                        h.standAmount()));
            if (width != null || height != null)
                state = state.withPlayerDimensions(
                        new PlayerDimensionsState(
                                width == null ? state.playerDimensions().width() : width,
                                height == null ? state.playerDimensions().height() : height),
                        true);
            return state;
        }
    }

    record GeyserBoost(int duration) implements BedrockReplayEvent {
        @Override
        public BedrockMovementState state(BedrockMovementState state) {
            return state.withGeyserBoostTicks(duration == -1 ? -1 : Math.max(1, duration));
        }
    }

    record Boost(int duration) implements BedrockReplayEvent {
        public Boost {
            if (duration < -1) duration = 0;
        }

        @Override
        public BedrockSimulation.Input input(BedrockSimulation.Input input, long elapsed) {
            return new BedrockSimulation.Input(
                    input.previousState(),
                    input.frame(),
                    input.intent(),
                    input.snapshot(),
                    input.canStep(),
                    input.maxUpStep(),
                    input.mobJumpComponent(),
                    input.actorMovementTick(),
                    input.acceptedTeleport(),
                    input.control(),
                    duration == -1 || elapsed < Math.max(1, duration));
        }
    }
}
