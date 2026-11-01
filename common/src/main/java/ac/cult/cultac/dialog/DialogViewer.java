package ac.cult.cultac.dialog;

import java.util.UUID;

/** Someone dialogs are shown to; {@link Dialogs#viewer} gives the one for a player. */
public interface DialogViewer {
    UUID id();

    /** Shows {@code dialog} in place of the one this viewer has open. */
    void show(Dialog dialog);

    void close();

    /** Runs {@code task} on the viewer's thread, the one their buttons run on. */
    void execute(Runnable task);

    /** Whether the viewer is still connected. */
    boolean online();
}
