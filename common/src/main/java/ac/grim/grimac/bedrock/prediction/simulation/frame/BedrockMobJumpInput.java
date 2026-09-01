package ac.grim.grimac.bedrock.prediction.simulation.frame;


record BedrockMobJumpInput(
    boolean jumping,
    boolean autoSurfaceSwim,
    boolean swimTransition,
    boolean scaffoldingOrAscendableBlock,
    boolean ladderOrPowderSnowAtFeet,
    boolean nonSwimmerSwimUp,
    boolean lavaSwimUp,
    boolean groundJumpRequest
) {
    private static final double FULL_SWIM_AMOUNT = 1.0D;

    static BedrockMobJumpInput from(
        BedrockTravelInput input,
        ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent intent,
        BedrockFrameFacts frameFacts,
        BedrockLiquidJumpContact liquidJumpContact
    ) {
        BedrockSwimmingMovement.SwimmingState swimming = frameFacts.swimming();
        BedrockClimbState climb = frameFacts.climb();
        boolean jumping = input.inputFrame().jumping();
        boolean inWater = frameFacts.inWater();
        boolean inLava = frameFacts.inLava();
        boolean swimmingAfterActions = swimming.actorStateAfterActions();

        return new BedrockMobJumpInput(
            jumping,
            autoSurfaceSwim(liquidJumpContact, swimmingAfterActions),
            swimTransition(input, swimming, liquidJumpContact),
            scaffoldingOrAscendableBlock(input, climb),
            climb.climbable().ascending() || frameFacts.rawPowderSnowAtFeetAscendable(),
            nonSwimmerSwimUp(liquidJumpContact),
            liquidJumpContact.lavaSwimUpApplies(),
            groundJumpRequest(input, intent, swimming, climb, jumping, inWater, inLava)
        );
    }

    private static boolean autoSurfaceSwim(
        BedrockLiquidJumpContact liquidJumpContact,
        boolean swimmingAfterActions
    ) {
        return liquidJumpContact.waterSwimUpApplies()
            && swimmingAfterActions
            && !liquidJumpContact.waterHeadInWater();
    }

    private static boolean swimTransition(
        BedrockTravelInput input,
        BedrockSwimmingMovement.SwimmingState swimming,
        BedrockLiquidJumpContact liquidJumpContact
    ) {
        return liquidJumpContact.waterSwimUpApplies()
            && swimming.swimAmount() > 0.0D
            && swimming.swimAmount() < FULL_SWIM_AMOUNT;
    }

    private static boolean scaffoldingOrAscendableBlock(
        BedrockTravelInput input,
        BedrockClimbState climb
    ) {
        return (climb.inScaffolding() || input.previousState().climbableContact().ascendableBlock())
            && !BedrockScaffoldingAction.resolve(climb).active();
    }

    private static boolean nonSwimmerSwimUp(BedrockLiquidJumpContact liquidJumpContact) {

        return liquidJumpContact.waterSwimUpApplies();
    }

    private static boolean groundJumpRequest(
        BedrockTravelInput input,
        ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent intent,
        BedrockSwimmingMovement.SwimmingState swimming,
        BedrockClimbState climb,
        boolean jumping,
        boolean inWater,
        boolean inLava
    ) {
        return BedrockLocalPlayerJumpMovement.requestedLaunch(
            input.previousState(),
            input.inputFrame(),
            intent,
            inWater,
            inLava,
            climb.inScaffolding())
            || swimming.actorStateAtStart()
            && jumping
            && intent.jump().start()
            && intent.pose().stopSwimming()
            && !inWater
            && !inLava
            && !climb.inScaffolding();
    }

}
