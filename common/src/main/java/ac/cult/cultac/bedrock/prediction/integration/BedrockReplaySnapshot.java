package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

final class BedrockReplaySnapshot {
    private BedrockReplaySnapshot() { }

    static BedrockMovementState detach(BedrockMovementState state) { return attach(state, null); }

    static BedrockMovementState attach(BedrockMovementState state, BedrockMovementState live) {
        if (state.isHorse()) {
            var h = state.horse();
            state = state.withHorse(new BedrockHorseState(live == null ? null : live.horse().actor(),
                    h.jumping(), h.pendingJump(), h.standing(), h.allowStandSliding(), h.metadataRevision(),
                    h.release(), h.forwardJump(), h.standAmount()));
        } else if (state.isBoat()) {
            var b = state.boat();
            state = state.withBoat(new BedrockBoatState(live == null ? null : live.boat().actor(),
                    b.properties(), b.angularVelocity(), b.submergedTicks(), b.paddleTick(), b.left(), b.right(), b.analogPaddles()));
        } else if (live != null) {
            state = state.withAcknowledgedPlayerDimensions(live.acknowledgedPlayerDimensions());
        }
        return state;
    }

    static BedrockSimulation.Input withState(BedrockSimulation.Input input, BedrockMovementState state) {
        return new BedrockSimulation.Input(state, input.frame(), input.intent(), input.snapshot(), input.canStep(),
                input.maxUpStep(), input.mobJumpComponent(), input.actorMovementTick(), input.acceptedTeleport(),
                input.control(), input.glideBoost());
    }
}
