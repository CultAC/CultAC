package ac.cult.cultac.bedrock.prediction.input;

public record BedrockInputIntent(
    JumpIntent jump,
    PoseIntent pose,
    FlyIntent fly,
    GlideIntent glide,
    VerticalIntent vertical,
    SprintIntent sprint,
    ItemUseIntent itemUse,
    RiptideIntent riptide,
    boolean swimmingRequested
) {
    public static BedrockInputIntent from(BedrockInputFrame frame) {
        return new BedrockInputIntent(
            new JumpIntent(frame.inputData().contains("START_JUMPING")),
            new PoseIntent(
                BedrockPoseInputData.has(frame, BedrockPoseInputData.START_SWIMMING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_SWIMMING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.START_CRAWLING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_CRAWLING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.START_SNEAKING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_SNEAKING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.SWIMMING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.HORIZONTAL_POSE)
            ),
            new FlyIntent(
                BedrockPoseInputData.has(frame, BedrockPoseInputData.START_FLYING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_FLYING)
            ),
            new GlideIntent(
                BedrockPoseInputData.has(frame, BedrockPoseInputData.START_GLIDING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.START_GLIDING_ACTION),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_GLIDING),
                BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_GLIDING_ACTION)
            ),
            new VerticalIntent(
                frame.inputData().contains("WANT_UP"),
                frame.inputData().contains("ASCEND"),
                frame.inputData().contains("ASCEND_BLOCK"),
                frame.inputData().contains("DESCEND"),
                frame.inputData().contains("SNEAK_DOWN"),
                frame.inputData().contains("WANT_DOWN")
            ),
            new SprintIntent(
                frame.inputData().contains("SPRINTING"),
                frame.inputData().contains("START_SPRINTING"),
                frame.inputData().contains("STOP_SPRINTING"),
                frame.inputData().contains("SPRINT_DOWN")
            ),
            new ItemUseIntent(
                frame.inputData().contains("START_USING_ITEM"),
                frame.inputData().contains("STOP_USING_ITEM"),
                frame.inputData().contains("RELEASE_USING_ITEM")
            ),
            new RiptideIntent(
                frame.inputData().contains("RIPTIDE_CHARGE_START"),
                frame.inputData().contains("START_SPIN_ATTACK"),
                frame.inputData().contains("STOP_SPIN_ATTACK"),
                frame.inputData().contains("SERVER_USING_ITEM")
            ),
            frame.swimmingRequested()
        );
    }

    public record JumpIntent(boolean start) {
    }

    public record PoseIntent(
        boolean startSwimming,
        boolean stopSwimming,
        boolean startCrawling,
        boolean stopCrawling,
        boolean startSneaking,
        boolean stopSneaking,
        boolean swimming,
        boolean horizontalPose
    ) {
    }

    public record FlyIntent(boolean start, boolean stop) {
    }

    public record GlideIntent(
        boolean start,
        boolean startAction,
        boolean stop,
        boolean stopAction
    ) {
        public boolean stopRequest() {
            return stop || stopAction;
        }
    }

    public record VerticalIntent(
        boolean wantUp,
        boolean ascend,
        boolean ascendBlock,
        boolean descend,
        boolean sneakDown,
        boolean wantDown
    ) {
        public boolean upwardClimbInput(boolean jumping) {
            return upwardInput(jumping) || ascend || ascendBlock;
        }

        public boolean upwardInput(boolean jumping) {
            return jumping || wantUp;
        }

        public boolean descendInput() {
            return descend || sneakDown || wantDown;
        }
    }

    public record SprintIntent(
        boolean sprinting,
        boolean start,
        boolean stop,
        boolean down
    ) {
        public boolean currentTickActorSprinting(boolean currentSprinting) {
            if (sprinting) {
                return true;
            }
            if (start) {
                return true;
            }
            if (stop) {
                return false;
            }
            return currentSprinting;
        }

        public boolean nextActorSprinting(boolean currentSprinting) {
            if (sprinting) {
                return true;
            }
            if (stop) {
                return false;
            }
            if (start) {
                return true;
            }
            return currentSprinting;
        }

        public boolean afterActionEdges(boolean currentSprinting) {

            boolean next = currentSprinting;
            if (start) {
                next = true;
            }
            if (stop) {
                next = false;
            }
            return next;
        }
    }

    public record ItemUseIntent(
        boolean start,
        boolean stop,
        boolean release
    ) {
    }

    public record RiptideIntent(
        boolean chargeStart,
        boolean startSpinAttack,
        boolean stopSpinAttack,
        boolean serverUsingItem
    ) {
        public RiptideIntent(boolean chargeStart, boolean startSpinAttack, boolean stopSpinAttack) {
            this(chargeStart, startSpinAttack, stopSpinAttack, false);
        }
    }
}
