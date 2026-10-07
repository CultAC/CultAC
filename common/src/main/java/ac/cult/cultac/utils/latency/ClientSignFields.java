package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.Map;

/** Wax and root command flags are the only sign inputs to interaction prediction. */
final class ClientSignFields {
    private ClientSignFields() {}

    static NbtValue.Compound load(NbtValue.Compound tag) {
        return new NbtValue.Compound(Map.of(
                "is_waxed", flag(ClientBlockEntityFields.bool(tag.values().get("is_waxed"))),
                "front_has_commands",
                        flag(ac.cult.blocksim.entity.SignEntityComponents.hasCommands(
                                tag.values().get("front_text"))),
                "back_has_commands",
                        flag(ac.cult.blocksim.entity.SignEntityComponents.hasCommands(
                                tag.values().get("back_text")))));
    }

    private static NbtValue flag(boolean value) {
        return new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) (value ? 1 : 0));
    }
}
