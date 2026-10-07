/*
 * Offer framing adapted from PacketEvents PacketWrapper.readMerchantOffer,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.MerchantPrices;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/** Owned merchant inventory values for all supported wire versions. */
public final class ModelMerchantValues {
    public record Offer(SimItemStack costA, SimItemStack costB, SimItemStack result, boolean outOfStock) {}

    public record Offers(int windowId, List<Offer> offers) {
        public Offers {
            offers = List.copyOf(offers);
        }
    }

    private final ModelItemValues items;

    public ModelMerchantValues(ModelItemValues items) {
        this.items = java.util.Objects.requireNonNull(items);
    }

    public Offers read(ByteBuf input) {
        int window = Wire.readVarInt(input), count = Wire.readLength(input, input.readableBytes());
        var offers = new ArrayList<Offer>(count);
        for (int index = 0; index < count; index++) {
            var costA = items.itemCost(input, false);
            var result = items.item(input, false);
            if (result.isEmpty()) throw new MalformedPacketException("Empty merchant result");
            var costB = items.itemCost(input, true);
            boolean exhausted = input.readBoolean();
            int uses = input.readInt(), maxUses = input.readInt();
            input.readInt(); // XP is not consumed by inventory prediction.
            int special = input.readInt();
            float multiplier = input.readFloat();
            int demand = input.readInt();
            costA = costA.copyWithCount(
                    MerchantPrices.count(costA.count(), demand, multiplier, special, costA.maxStackSize()));
            offers.add(new Offer(costA, costB, result, exhausted || uses >= maxUses));
        }
        // The caller leaves the unused UI level, XP and restock fields unread, as the existing catalog does.
        return new Offers(window, offers);
    }
}
