package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.grim.grimac.api.storage.verbose.Verbose;

/** MultiActionsI, J, K, L and S, which check name version 3 reports as one check, InventoryD. */
final class InventoryActions {
    /** InventoryD's verbose, which names the action. */
    static final Verbose V = Verbose.of("action=interact")
            .or("action=attack")
            .or("action=spectateEntity")
            .or("action=stab")
            .or("action=use")
            .or("action=place")
            .or("action=dig")
            .or("action=swing"); // shape index == ACTION_* value

    static final int ACTION_INTERACT = 0;
    static final int ACTION_ATTACK = 1;
    static final int ACTION_SPECTATE_ENTITY = 2;
    static final int ACTION_STAB = 3;
    static final int ACTION_USE = 4;
    static final int ACTION_PLACE = 5;
    static final int ACTION_DIG = 6;
    static final int ACTION_SWING = 7;

    private InventoryActions() {}

    /** Whether {@code check} is reported as InventoryD, where only its verbose tells which action it saw. */
    static boolean reportedTogether(Check check) {
        return "InventoryD".equals(check.getCheckName());
    }
}
