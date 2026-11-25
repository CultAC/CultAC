package ac.cult.cultac.bedrock.prediction.input;

import java.util.Set;

/** The input mode a frame was authored in. A frame that names none is read as keyboard and mouse. */
public enum BedrockInputMode {
    MOUSE(null),
    TOUCH("INPUT_MODE_TOUCH"),
    GAMEPAD("INPUT_MODE_GAMEPAD"),
    OTHER("INPUT_MODE_OTHER");

    private final String inputData;

    BedrockInputMode(String inputData) {
        this.inputData = inputData;
    }

    /** The input data entry that names this mode, or null for keyboard and mouse. */
    public String inputData() {
        return inputData;
    }

    public static BedrockInputMode of(Set<String> inputData) {
        if (inputData.contains(TOUCH.inputData)) return TOUCH;
        if (inputData.contains(GAMEPAD.inputData)) return GAMEPAD;
        if (inputData.contains(OTHER.inputData)) return OTHER;
        return MOUSE;
    }
}
