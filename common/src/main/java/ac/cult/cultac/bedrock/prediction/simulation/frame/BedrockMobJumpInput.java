package ac.cult.cultac.bedrock.prediction.simulation.frame;

record BedrockMobJumpInput(
        boolean jumping,
        boolean autoSurfaceSwim,
        boolean swimTransition,
        boolean scaffoldingOrAscendableBlock,
        boolean ladderOrPowderSnowAtFeet,
        boolean nonSwimmerSwimUp,
        boolean lavaSwimUp,
        boolean groundJumpRequest) {
    private static final double FULL_SWIM_AMOUNT = 1.0D;

    static BedrockMobJumpInput from(
            BedrockTravelInput input, BedrockFrameFacts frameFacts, BedrockLiquidJumpContact liquidJumpContact) {
        BedrockSwimmingMovement.SwimmingState swimming = frameFacts.swimming();
        BedrockClimbState climb = frameFacts.climb();
        boolean jumping = input.inputFrame().jumping();
        boolean inWater = frameFacts.inWater();
        boolean inLava = frameFacts.inLava();
        boolean swimmingAfterActions = swimming.actorStateAfterActions();

        return new BedrockMobJumpInput(
                jumping,
                autoSurfaceSwim(liquidJumpContact, swimmingAfterActions),
                swimTransition(swimming),
                scaffoldingOrAscendableBlock(input, climb),
                climb.climbable().ascending() || frameFacts.rawPowderSnowAtFeetAscendable(),
                nonSwimmerSwimUp(liquidJumpContact),
                liquidJumpContact.lavaSwimUpApplies(),
                groundJumpRequest(input, frameFacts, inWater, inLava));
    }

    private static boolean groundJumpRequest(
            BedrockTravelInput input, BedrockFrameFacts frameFacts, boolean inWater, boolean inLava) {
        // Held jump raises a flying player. It neither launches from the ground
        // nor starts the ground jump cooldown.
        return !frameFacts.context().movementAbilityFlying()
                && BedrockLocalPlayerJumpMovement.requestedLaunch(
                        input.previousState(),
                        input.inputFrame(),
                        inWater,
                        inLava,
                        frameFacts.climb().inScaffolding(),
                        frameFacts.swimming().swimAmount());
    }

    // Held jump is withheld while the swimmer's head is out of water or the swim
    // pose is still blending, in any medium.
    private static boolean autoSurfaceSwim(BedrockLiquidJumpContact liquidJumpContact, boolean swimmingAfterActions) {
        return swimmingAfterActions && !liquidJumpContact.waterHeadInWater();
    }

    private static boolean swimTransition(BedrockSwimmingMovement.SwimmingState swimming) {
        return swimming.swimAmount() > 0.0D && swimming.swimAmount() < FULL_SWIM_AMOUNT;
    }

    private static boolean scaffoldingOrAscendableBlock(BedrockTravelInput input, BedrockClimbState climb) {
        return (climb.inScaffolding()
                        || input.previousState().climbableContact().ascendableBlock())
                && !BedrockScaffoldingAction.resolve(climb).active();
    }

    private static boolean nonSwimmerSwimUp(BedrockLiquidJumpContact liquidJumpContact) {
        return liquidJumpContact.waterSwimUpApplies();
    }
}
