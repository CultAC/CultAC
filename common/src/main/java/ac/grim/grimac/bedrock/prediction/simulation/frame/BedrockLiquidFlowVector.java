package ac.grim.grimac.bedrock.prediction.simulation.frame;

record BedrockLiquidFlowVector(double x, double y, double z) {
    static final BedrockLiquidFlowVector NONE = new BedrockLiquidFlowVector(0.0D, 0.0D, 0.0D);
}
