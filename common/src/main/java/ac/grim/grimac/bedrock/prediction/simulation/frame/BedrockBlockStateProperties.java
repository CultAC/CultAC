package ac.grim.grimac.bedrock.prediction.simulation.frame;

public final class BedrockBlockStateProperties {
    private BedrockBlockStateProperties() {
    }

    public static boolean bedrockBoolean(Object value, String stateName) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        throw new IllegalArgumentException("Bedrock state " + stateName + " must be boolean-like");
    }

    public static double bedrockDoubleOrZero(Object value, String stateName) {
        if (value == null) {
            return 0.0D;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            return Double.parseDouble(text);
        }
        throw new IllegalArgumentException("Bedrock state " + stateName + " must be numeric");
    }
}
