package ac.cult.cultac.cloud.capture;

/** The client's chunk packets' ids in one protocol of 768 to 777, each -1 where CultAC has none. */
public record ChunkIds(int levelChunk, int blockUpdate, int sectionUpdate, int forget) {}
