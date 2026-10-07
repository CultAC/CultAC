/*
 * Equipment wire/component ID mapping adapted from PacketEvents EquipmentSlot,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.value;

import java.util.List;
import java.util.Locale;

/** Wire order differs from the equippable component's slot IDs. */
public enum EquipmentSlot {
    MAINHAND(0),
    OFFHAND(5),
    FEET(1),
    LEGS(2),
    CHEST(3),
    HEAD(4),
    BODY(6),
    SADDLE(7);

    public static final List<EquipmentSlot> VALUES = List.of(values());
    private final int componentId;
    private final String name;

    EquipmentSlot(int componentId) {
        this.componentId = componentId;
        this.name = name().toLowerCase(Locale.ROOT);
    }

    public int componentId() {
        return componentId;
    }

    public String getName() {
        return name;
    }

    public static EquipmentSlot byName(String name) {
        for (var slot : VALUES) if (slot.name.equals(name)) return slot;
        throw new IllegalArgumentException("Invalid equipment slot " + name);
    }
}
