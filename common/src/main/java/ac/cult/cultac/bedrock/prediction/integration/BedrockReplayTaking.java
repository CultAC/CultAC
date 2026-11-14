package ac.cult.cultac.bedrock.prediction.integration;

/** How the client takes a timed update whose frame it still holds. */
enum BedrockReplayTaking {
    /** Recorded on its frame, and every frame from the oldest marked one runs again before the next tick. */
    REPLAYS,
    /** Recorded on its frame, which is marked: the frames since keep the states they ran with. */
    MARKS,
    /** What its frame already ended with: neither recorded nor marked. */
    IGNORED
}
