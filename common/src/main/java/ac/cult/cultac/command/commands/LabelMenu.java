package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.UUID;

/**
 * The label menu a platform draws for {@code /cult label <player>}: verdict, checks, confidence and span, then a review
 * whose Confirm posts the labels. A platform without one installs none, and the command is not offered there.
 */
interface LabelMenu {
    /** Whether {@code sender} can be shown the menu. */
    boolean showable(Sender sender);

    /** Opens a fresh menu on {@code target} for {@code sender}, who labels as {@code labellerId}. */
    void open(Sender sender, UUID labellerId, LabelClient client, CloudArguments.Target target);
}
