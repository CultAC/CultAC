package ac.cult.cultac.checks.verdict;

import ac.cult.cultac.checks.Check;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Lets a remote party decide a flag that no local FlagEvent handler cancelled. Installed per player through
 * {@code CultPlayer#setFlagArbiter}; absent on Paper and on unbridged proxies.
 */
public interface FlagArbiter {
    /**
     * Called on the player's packet thread after the local FlagEvent fired uncancelled.
     *
     * @param prospectiveViolations the violations the check holds once this flag commits
     * @return true when a remote verdict decides the flag; the arbiter then runs exactly one of
     *     {@link FlagOutcome#commit()} or {@link FlagOutcome#discard()}, later and on the player's packet thread,
     *     never from inside this call. False leaves the flag to commit immediately.
     */
    boolean defer(
            @NotNull Check check,
            @NotNull Supplier<String> verbose,
            double prospectiveViolations,
            @NotNull FlagOutcome outcome);
}
