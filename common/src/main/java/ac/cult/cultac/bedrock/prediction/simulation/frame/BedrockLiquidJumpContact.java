package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;

record BedrockLiquidJumpContact(
    boolean waterSwimUpApplies,
    boolean waterHeadInWater,
    boolean lavaSwimUpApplies
) {
    static BedrockLiquidJumpContact from(
        BedrockFrameFacts frameFacts,
        Vec3d feetPosition,
        boolean headInWater
    ) {

        boolean waterSwimUpApplies = frameFacts.inWater()
            && frameFacts.context().liquidMovementMedium() != Medium.LAVA;
        boolean waterHeadInWater = headInWater;
        boolean lavaSwimUpApplies = lavaTravel(frameFacts)
            && BedrockLiquidSensing.lavaSwimUpApplies(
                frameFacts.context(),
                feetPosition,
                frameFacts.movementDimensions()
            );
        return new BedrockLiquidJumpContact(waterSwimUpApplies, waterHeadInWater, lavaSwimUpApplies);
    }

    private static boolean lavaTravel(BedrockFrameFacts frameFacts) {
        return frameFacts.inLava()
            || frameFacts.context().liquidMovementMedium() == Medium.LAVA;
    }
}
