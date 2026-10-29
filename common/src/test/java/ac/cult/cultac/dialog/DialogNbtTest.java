package ac.cult.cultac.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.data.nbt.NbtValue.Kind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class DialogNbtTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void aDialogIsAMultiActionDialogThatStaysOpenAndDoesNotPause() {
        Map<String, NbtValue> nbt =
                DialogNbt.encode(sample(), "cultac:dialog/7/").values();
        assertEquals(text("minecraft:multi_action"), nbt.get("type"));
        assertEquals(DialogNbt.component(Component.text("Title")), nbt.get("title"));
        assertEquals(bool(true), nbt.get("can_close_with_escape"));
        assertEquals(bool(false), nbt.get("pause"));
        assertEquals(text("none"), nbt.get("after_action"));
        assertEquals(new NbtValue.Numeric(Kind.INT, 3), nbt.get("columns"));
    }

    @Test
    void eachButtonClicksBackWithItsIndexAndTheExitWithTheNext() {
        Map<String, NbtValue> nbt =
                DialogNbt.encode(sample(), "cultac:dialog/7/").values();
        List<NbtValue> actions = ((NbtValue.Sequence) nbt.get("actions")).values();
        assertEquals(
                List.of("cultac:dialog/7/0", "cultac:dialog/7/1"),
                actions.stream().map(DialogNbtTest::clickId).toList());
        assertEquals("cultac:dialog/7/2", clickId(nbt.get("exit_action")));
        assertEquals(text("minecraft:dynamic/custom"), action(actions.get(0)).get("type"));
        Map<String, NbtValue> second = compound(actions.get(1));
        assertEquals(new NbtValue.Numeric(Kind.INT, 110), second.get("width"));
        assertEquals(DialogNbt.component(Component.text("Why")), second.get("tooltip"));
    }

    @Test
    void theHeadNamesItsPlayerByUuidAndName() {
        List<NbtValue> body =
                ((NbtValue.Sequence) DialogNbt.encode(sample(), "p/").values().get("body")).values();
        Map<String, NbtValue> head = compound(body.get(1));
        assertEquals(text("minecraft:item"), head.get("type"));
        Map<String, NbtValue> item = compound(head.get("item"));
        assertEquals(text("minecraft:player_head"), item.get("id"));
        Map<String, NbtValue> profile =
                compound(compound(item.get("components")).get("minecraft:profile"));
        assertEquals(text("Notch"), profile.get("name"));
        List<Long> id = ((NbtValue.PrimitiveArray) profile.get("id")).values();
        assertEquals(
                NOTCH, new UUID(id.get(0) << 32 | id.get(1) & 0xFFFFFFFFL, id.get(2) << 32 | id.get(3) & 0xFFFFFFFFL));
    }

    @Test
    void theHeadsTextIsFittedToItsWidestLineSoItSitsBesideTheHead() {
        Component card = Component.empty()
                .append(Component.text("Notch", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("Online 12m", NamedTextColor.GRAY));
        assertEquals(new NbtValue.Numeric(Kind.INT, 60), headTextWidth(card, 300));
        assertEquals(
                new NbtValue.Numeric(Kind.INT, 49),
                headTextWidth(Component.text("Notch_2", NamedTextColor.WHITE, TextDecoration.BOLD), 300));
        assertEquals(new NbtValue.Numeric(Kind.INT, 40), headTextWidth(card, 40));
    }

    @Test
    void inputsCarryTheirKeysAndTheSliderStepsByWholeNumbers() {
        List<NbtValue> inputs =
                ((NbtValue.Sequence) DialogNbt.encode(sample(), "p/").values().get("inputs")).values();
        Map<String, NbtValue> choice = compound(inputs.get(0));
        assertEquals(text("minecraft:single_option"), choice.get("type"));
        assertEquals(text("confidence"), choice.get("key"));
        Map<String, NbtValue> slider = compound(inputs.get(1));
        assertEquals(text("minecraft:number_range"), slider.get("type"));
        assertEquals(text("%s: last %s days"), slider.get("label_format"));
        assertEquals(new NbtValue.Numeric(Kind.FLOAT, 1f), slider.get("step"));
        assertEquals(new NbtValue.Numeric(Kind.FLOAT, 7f), slider.get("initial"));
        Map<String, NbtValue> box = compound(inputs.get(2));
        assertEquals(text("minecraft:text"), box.get("type"));
        assertEquals(new NbtValue.Numeric(Kind.INT, 256), box.get("max_length"));
    }

    @Test
    void textKeepsItsColourDecorationsHoverAndChildren() {
        Component styled = Component.text("Label ", NamedTextColor.WHITE)
                .append(Component.text("Notch", TextColor.color(0x123456), TextDecoration.BOLD))
                .hoverEvent(HoverEvent.showText(Component.text("tip")));
        Map<String, NbtValue> nbt = DialogNbt.component(styled).values();
        assertEquals(text("Label "), nbt.get("text"));
        assertEquals(text("white"), nbt.get("color"));
        assertEquals(text("show_text"), compound(nbt.get("hover_event")).get("action"));
        Map<String, NbtValue> child =
                compound(((NbtValue.Sequence) nbt.get("extra")).values().get(0));
        assertEquals(text("#123456"), child.get("color"));
        assertEquals(bool(true), child.get("bold"));
        assertFalse(child.containsKey("italic"));
    }

    @Test
    void onlyTextComponentsAreDrawn() {
        assertThrows(IllegalArgumentException.class, () -> DialogNbt.component(Component.translatable("key")));
    }

    @Test
    void theEncodedDialogIsValidNetworkNbt() {
        NbtValue.Compound nbt = DialogNbt.encode(sample(), "cultac:dialog/1/");
        assertEquals(nbt, BinaryNbt.read(BinaryNbt.write(nbt)));
    }

    private static Dialog sample() {
        return Dialog.titled(Component.text("Title"))
                .text(Component.text("Step 1"))
                .head(NOTCH, "Notch", Component.text("Online"), 300)
                .input(new Dialog.Choice(
                        "confidence",
                        Component.text("Confidence"),
                        Dialog.WIDE,
                        List.of(
                                new Dialog.Option("high", Component.text("High"), true),
                                new Dialog.Option("extreme", Component.text("Extreme"), false))))
                .input(new Dialog.Slider("span", Component.text("Span"), "%s: last %s days", Dialog.WIDE, 1, 30, 7))
                .input(new Dialog.TextBox("note", Component.text("Note"), 300, 256))
                .button(Component.text("Next"), Dialog.WIDE, click -> {})
                .button(Component.text("Other"), Component.text("Why"), 110, click -> {})
                .exit(Component.text("Back"), Dialog.WIDE, click -> {})
                .columns(3)
                .build();
    }

    private static NbtValue headTextWidth(Component text, int width) {
        Dialog dialog = Dialog.titled(Component.text("Title"))
                .head(NOTCH, "Notch", text, width)
                .button(Component.text("Next"), Dialog.WIDE, click -> {})
                .build();
        List<NbtValue> body =
                ((NbtValue.Sequence) DialogNbt.encode(dialog, "p/").values().get("body")).values();
        return compound(compound(body.get(0)).get("description")).get("width");
    }

    private static String clickId(NbtValue button) {
        return ((NbtValue.Text) action(button).get("id")).value();
    }

    private static Map<String, NbtValue> action(NbtValue button) {
        return compound(compound(button).get("action"));
    }

    private static Map<String, NbtValue> compound(NbtValue value) {
        return ((NbtValue.Compound) value).values();
    }

    private static NbtValue text(String value) {
        return new NbtValue.Text(value);
    }

    private static NbtValue bool(boolean value) {
        return new NbtValue.Numeric(Kind.BYTE, (byte) (value ? 1 : 0));
    }
}
