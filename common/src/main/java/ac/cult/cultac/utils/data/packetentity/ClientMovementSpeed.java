package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.utils.data.ClientAttributeInstance;
import ac.cult.cultac.utils.data.ClientFrost;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/**
 * The movement_speed a vehicle's LivingEntity#getRiddenSpeed reads, as its client holds it.
 * ClientPacketListener#handleUpdateAttributes replaces the base and every modifier, the server's
 * minecraft:powder_snow included, and no client code changes the modifiers of an entity other than the local player
 * except through {@link ClientFrost} and Strider#setSuffocating.
 */
public final class ClientMovementSpeed {
    private static final AttributeModifier SUFFOCATING = new AttributeModifier(
            "minecraft:suffocating", -0.34F, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    private final ClientAttributeInstance attribute;
    // Entity#DATA_TICKS_FROZEN as the client last received it.
    private int ticksFrozen;

    ClientMovementSpeed(double base) {
        attribute = new ClientAttributeInstance(base, 0.0D, 1024.0D);
    }

    /** The entity type's own default base, before the server sends any. */
    void defaultBase(double base) {
        attribute.replace(base, List.of());
    }

    /** ClientPacketListener#handleUpdateAttributes. */
    public void onServerSnapshot(AttributeSnapshot snapshot) {
        attribute.onServerSnapshot(snapshot);
    }

    /** ClientPacketListener#handleSetEntityData for Entity#DATA_TICKS_FROZEN. */
    public void onTicksFrozen(int ticksFrozen) {
        this.ticksFrozen = ticksFrozen;
    }

    /** LivingEntity#aiStep's frost replacement after a travel to {@code position}; whether the value changed. */
    public boolean replaceClientFrost(CultPlayer player, Vec3 position, MainSupportingBlockData support) {
        return ClientFrost.replace(player, attribute, ticksFrozen, position, support);
    }

    /** Strider#setSuffocating changes this independently of the synchronized cold flag. */
    public void setStriderSuffocating(boolean suffocating) {
        attribute.updateModifier(SUFFOCATING.id(), suffocating ? SUFFOCATING : null);
    }

    /** AttributeInstance#getValue. */
    public double value() {
        return attribute.value();
    }
}
