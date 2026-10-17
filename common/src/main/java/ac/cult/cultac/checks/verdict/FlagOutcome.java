package ac.cult.cultac.checks.verdict;

/** The effects of one deferred flag; exactly one method runs, once, on the player's packet thread. */
public interface FlagOutcome {
    /** Applies the flag: violations, punishments, storage and alert. */
    void commit();

    /** Drops the flag. */
    void discard();
}
