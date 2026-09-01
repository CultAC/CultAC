package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import net.minecraft.SharedConstants;
import org.bukkit.block.BlockFace;

@CheckData(name = "InvalidPlaceB", stableKey = "grim.scaffolding.invalid_place_b", description = "Sent impossible block face id")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "26.2 UseItemOn decodes the hit face via FriendlyByteBuf.readEnum(Direction); an out-of-range varint throws in the vanilla decoder before any check.")
public class InvalidPlaceB extends BlockPlaceCheck {
    private static final Verbose V = Verbose.of("direction={sint}");

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public InvalidPlaceB(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {

        int faceId = getFaceId(place.getDirection());
        if (faceId == 255 && SERVER_VERSION.isOlderThanOrEquals(ClientVersion.V_1_8)) { // PE ServerVersion.V_1_8
            return;
        }

        if (faceId < 0 || faceId > 5) {
            // ban
            int direction = faceId;
            if (flag(V.write(verbose()).sint(direction)) && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
        }
    }

    private static int getFaceId(BlockFace face) {
        return switch (face) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
            default -> -1;
        };
    }

}
