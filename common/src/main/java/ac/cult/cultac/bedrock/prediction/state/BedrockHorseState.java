package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;

/** Immutable mount carry, retained independently of the rider's movement and camera. */
public record BedrockHorseState(
        PacketEntityHorse actor, boolean jumping, float pendingJump, boolean standing,
        boolean allowStandSliding, long metadataRevision, int release, boolean forwardJump, float standAmount
) {
    public static BedrockHorseState initial(PacketEntityHorse actor) {
        return new BedrockHorseState(actor, false, 0.0F, actor.isRearing, false,
                actor.horseFlagsRevision, -1, false, actor.standAnim);
    }

    public BedrockHorseState forFrame(long revision, boolean acknowledgedStanding, int release) {
        return new BedrockHorseState(actor, jumping, pendingJump,
                revision == metadataRevision ? standing : acknowledgedStanding,
                allowStandSliding, revision, release, false, standAmount);
    }

    public BedrockHorseState requestJump() {
        if (release < 0) return this;
        float scale = release >= 90 ? 1.0F : (release * 0.4F) / 90.0F + 0.4F;
        return new BedrockHorseState(actor, jumping, scale, true, true, metadataRevision,
                -1, forwardJump, standAmount);
    }

    public BedrockHorseState launched() {
        return new BedrockHorseState(actor, true, 0.0F, standing, allowStandSliding,
                metadataRevision, -1, forwardJump, standAmount);
    }

    public BedrockHorseState withForwardJump(boolean forward) {
        return new BedrockHorseState(actor, jumping, pendingJump, standing,
                allowStandSliding, metadataRevision, release, forward, standAmount);
    }

    public boolean canLaunch(boolean grounded) {
        return grounded && !jumping && (pendingJump > 0.0F || release >= 0);
    }

    public boolean movementSuppressed(boolean grounded) {
        return grounded && standing && !jumping && !allowStandSliding && release < 0;
    }

    public BedrockHorseState afterTravel(boolean previouslyGrounded, boolean grounded) {
        boolean landed = !previouslyGrounded && grounded;
        boolean nextStanding = standing && !landed;
        float animation = nextStanding
                ? Math.min(1.0F, standAmount + (1.0F - standAmount) * 0.4F + 0.05F)
                : Math.max(0.0F, standAmount + (0.8F * standAmount * standAmount * standAmount - standAmount) * 0.6F - 0.05F);
        return new BedrockHorseState(actor, !landed && jumping,
                landed ? 0.0F : pendingJump, nextStanding,
                allowStandSliding && nextStanding, metadataRevision, -1, forwardJump, animation);
    }
}
