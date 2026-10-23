package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.reflection.ReflectionUtils;
import java.util.UUID;

/**
 * The label menu on Paper: {@code /cult label <player>} opens it for a Java player on a server that draws dialogs
 * ({@link LabelDialogs}); anyone else is pointed at the label command. {@link LabelDialogs} loads only once a menu
 * opens, so a server without dialogs never resolves its Paper types.
 */
public final class CultLabelMenu {
    private static final String DIALOG_API = "io.papermc.paper.dialog.Dialog";

    private CultLabelMenu() {}

    /** Offers the menu to the label command; call before commands register. */
    public static void install() {
        CultLabel.menu(new LabelMenu() {
            @Override
            public boolean showable(Sender sender) {
                if (!ReflectionUtils.hasClass(DIALOG_API)) return false;
                if (CultAPI.INSTANCE.getPlayerDataManager().isKnownBedrockPlayer(sender.getUniqueId())) return false;
                return LabelDialogs.showable(sender);
            }

            @Override
            public void open(Sender sender, UUID labellerId, LabelClient client, CloudArguments.Target target) {
                LabelDialogs.open(sender, labellerId, client, target);
            }
        });
    }

    /** Forgets the menu of a staff member who left. */
    public static void left(UUID staff) {
        LabelWizard.left(staff);
    }
}
