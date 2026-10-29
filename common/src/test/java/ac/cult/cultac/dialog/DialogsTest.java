package ac.cult.cultac.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.data.nbt.NbtValue.Kind;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class DialogsTest {
    private static final Dialog DIALOG = Dialog.titled(Component.text("Title"))
            .button(Component.text("First"), Dialog.WIDE, click -> {})
            .button(Component.text("Second"), Dialog.WIDE, click -> {})
            .exit(Component.text("Back"), Dialog.WIDE, click -> {})
            .build();

    @Test
    void aClickNamesAButtonOfTheDialogShownUnderItsId() {
        assertSame(DIALOG.buttons().get(1), Dialogs.button(DIALOG, 7, "cultac:dialog/7/1"));
        assertSame(DIALOG.exit(), Dialogs.button(DIALOG, 7, "cultac:dialog/7/2"));
    }

    @Test
    void aClickOnAnOlderDialogOrOnNoButtonFindsNothing() {
        assertNull(Dialogs.button(DIALOG, 7, "cultac:dialog/6/1"));
        assertNull(Dialogs.button(DIALOG, 7, "cultac:dialog/70/1"));
        assertNull(Dialogs.button(DIALOG, 7, "cultac:dialog/7/3"));
        assertNull(Dialogs.button(DIALOG, 7, "cultac:dialog/7/-1"));
        assertNull(Dialogs.button(DIALOG, 7, "cultac:dialog/7/x"));
    }

    @Test
    void theResponseReadsTextAndNumbersFromThePayload() {
        byte[] payload = BinaryNbt.write(new NbtValue.Compound(Map.of(
                "note", new NbtValue.Text("seen twice"),
                "span", new NbtValue.Numeric(Kind.FLOAT, 3f))));
        Response response = Dialogs.response(payload);
        assertEquals("seen twice", response.text("note"));
        assertEquals(3f, response.number("span"));
        assertNull(response.text("span"));
        assertNull(response.number("missing"));
    }

    @Test
    void aClickWithoutPayloadHasNoInputsAndAMalformedOneIsRefused() {
        assertSame(Response.EMPTY, Dialogs.response(new byte[] {0}));
        assertNull(Dialogs.response(new byte[] {10, 8}));
        assertNull(Dialogs.response(BinaryNbt.write(new NbtValue.Text("not a compound"))));
    }
}
