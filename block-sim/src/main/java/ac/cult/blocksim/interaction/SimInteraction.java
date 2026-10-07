package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.SimItemStack;

/** Success metadata controls vanilla's item component side effects and hand replacement. */
public record SimInteraction(InteractionKind kind, Swing swing, boolean itemInteraction, SimItemStack transformedStack) {
    public enum Swing { NONE, PREDICTED, SERVER_ONLY }
    public static final SimInteraction SUCCESS = new SimInteraction(InteractionKind.SUCCESS, Swing.PREDICTED, true, null);
    public static final SimInteraction SUCCESS_SERVER = new SimInteraction(InteractionKind.SUCCESS, Swing.SERVER_ONLY, true, null);
    public static final SimInteraction CONSUME = new SimInteraction(InteractionKind.CONSUME, Swing.NONE, true, null);
    public static final SimInteraction PASS = new SimInteraction(InteractionKind.PASS, Swing.NONE, false, null);
    public static final SimInteraction FAIL = new SimInteraction(InteractionKind.FAIL, Swing.NONE, false, null);
    public static final SimInteraction TRY_WITH_EMPTY_HAND = new SimInteraction(InteractionKind.TRY_WITH_EMPTY_HAND, Swing.NONE, false, null);

    public boolean consumesAction() { return kind.consumesAction(); }
    public SimInteraction withoutItem() { return new SimInteraction(kind, swing, false, null); }
    public SimInteraction transformedTo(SimItemStack stack) { return new SimInteraction(kind, swing, true, stack); }
}
