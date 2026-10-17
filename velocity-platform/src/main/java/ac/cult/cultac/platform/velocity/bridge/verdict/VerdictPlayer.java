package ac.cult.cultac.platform.velocity.bridge.verdict;

import ac.cult.cultac.checks.verdict.FlagArbiter;

/** The proxy player a {@link SessionArbiter} decides flags for. */
interface VerdictPlayer {
    /** Runs the task on the player's client channel loop, inline when already on it. */
    void onLoop(Runnable task);

    /** Runs the task on the player's client channel loop after the delay. */
    void schedule(Runnable task, long delayMillis);

    void install(FlagArbiter arbiter);

    /** Removes the arbiter if it is still the installed one. */
    void uninstall(FlagArbiter arbiter);

    /** Queues the task on the player's packet thread; never runs it inline. */
    void resolveLater(Runnable task);
}
