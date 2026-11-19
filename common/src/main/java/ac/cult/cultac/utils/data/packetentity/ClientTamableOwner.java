package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A TamableAnimal's synced DATA_FLAGS_ID tame bit and DATA_OWNERUUID_ID, which TamableAnimal#getTeam and
 * OwnableEntity#getOwner read on the client.
 */
public final class ClientTamableOwner {
    private boolean tame;
    private @Nullable UUID owner;

    /** TamableAnimal#isTame. */
    public boolean tame() {
        return tame;
    }

    /** The UUID OwnableEntity#getOwner looks up, or null when DATA_OWNERUUID_ID is empty. */
    public @Nullable UUID owner() {
        return owner;
    }

    /** Applies the entity data in {@code entries}, in the observed {@code version}'s layout. */
    public void update(List<EntityMetadata.Entry> entries, ProtocolVersion version) {
        int flagsIndex = WatchableIndexUtil.tameableFlags(version);
        var flags = WatchableIndexUtil.getIndex(entries, flagsIndex);
        if (flags != null && flags.value() instanceof Byte value) tame = (value & 4) != 0;
        // TamableAnimal defines DATA_OWNERUUID_ID right after DATA_FLAGS_ID.
        var reference = WatchableIndexUtil.getIndex(entries, flagsIndex + 1);
        if (reference != null && reference.value() instanceof Optional<?> value)
            owner = value.orElse(null) instanceof UUID uuid ? uuid : null;
    }
}
