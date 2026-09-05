package ac.cult.cultac.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public enum PacketGroup {
    SERVERBOUND_PLAYER_MOVEMENT(
            PacketFlow.SERVERBOUND,
            ServerboundMovePlayerPacket.class,
            ServerboundMovePlayerPacket.Pos.class,
            ServerboundMovePlayerPacket.PosRot.class,
            ServerboundMovePlayerPacket.Rot.class,
            ServerboundMovePlayerPacket.StatusOnly.class
    ),
    CLIENTBOUND_ENTITY_MOVEMENT(
            PacketFlow.CLIENTBOUND,
            ClientboundMoveEntityPacket.class,
            ClientboundMoveEntityPacket.Pos.class,
            ClientboundMoveEntityPacket.PosRot.class,
            ClientboundMoveEntityPacket.Rot.class
    );

    private final PacketFlow flow;
    private final Class<? extends Packet<?>> familyType;
    private final List<Class<? extends Packet<?>>> packetTypes;

    @SafeVarargs
    PacketGroup(
            PacketFlow flow,
            Class<? extends Packet<?>> familyType,
            Class<? extends Packet<?>>... packetTypes
    ) {
        this.flow = flow;
        this.familyType = familyType;
        this.packetTypes = List.of(packetTypes);
        validate();
    }

    public PacketFlow flow() {
        return flow;
    }

    public Class<? extends Packet<?>> familyType() {
        return familyType;
    }

    public List<Class<? extends Packet<?>>> packetTypes() {
        return packetTypes;
    }

    private void validate() {
        if (familyType == (Class<?>) Packet.class) {
            throw new IllegalStateException(name() + " must not use Packet as its family type");
        }
        if (packetTypes.isEmpty()) {
            throw new IllegalStateException(name() + " must name at least one concrete packet type");
        }

        Set<Class<? extends Packet<?>>> uniqueTypes = new HashSet<>();
        for (Class<? extends Packet<?>> packetType : packetTypes) {
            if (!familyType.isAssignableFrom(packetType)) {
                throw new IllegalStateException(packetType.getName() + " is not in " + familyType.getName());
            }
            if (packetType.isInterface() || Modifier.isAbstract(packetType.getModifiers())) {
                throw new IllegalStateException(name() + " includes non-concrete packet type " + packetType.getName());
            }
            if (!uniqueTypes.add(packetType)) {
                throw new IllegalStateException(name() + " duplicates " + packetType.getName());
            }
        }
    }
}
