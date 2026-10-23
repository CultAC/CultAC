package ac.cult.cultac.command.commands;

import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

final class HistoryCommandLog {
    private HistoryCommandLog() {}

    static void logBoth(Sender sender, Component msg) {
        sender.sendMessage(msg);
        // Console log in plain text so operators still see progress if the
        // command channel (RCON connection) closes mid-run.
        LogUtil.info(plain(msg));
    }

    private static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        flatten(c, sb);
        return sb.toString();
    }

    private static void flatten(Component c, StringBuilder sb) {
        if (c instanceof TextComponent tc) sb.append(tc.content());
        for (Component child : c.children()) flatten(child, sb);
    }
}
