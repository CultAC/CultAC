package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.Medium;

record BedrockLiquidJumpContact(
    boolean waterSwimUpApplies,
    boolean waterHeadInWater,
    boolean lavaSwimUpApplies
) {
    static BedrockLiquidJumpContact from(
        BedrockFrameFacts frameFacts,
        Vec3d feetPosition
    ) {

        boolean waterSwimUpApplies = frameFacts.inWater()
            && frameFacts.context().liquidMovementMedium() != Medium.LAVA;
        boolean waterHeadInWater = waterSwimUpApplies
            && BedrockLiquidSensing.waterHeadInWater(
                frameFacts.context(),
                feetPosition,
                frameFacts.movementDimensions()
            );
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
