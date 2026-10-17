package ac.cult.cultac.cloud.findings;

/** One finding the cloud sent back about a player: its check and category, confidence, detail and the tick it names. */
public record Finding(long at, String check, String category, double confidence, String detail, long tick) {}
