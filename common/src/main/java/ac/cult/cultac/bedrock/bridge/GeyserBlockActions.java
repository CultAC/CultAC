package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.blockplace.NmsBlockBreakResolver;
import java.lang.reflect.Field;
import java.util.function.Supplier;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.physics.Direction;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.BlockBreakHandler;
import org.geysermc.geyser.session.cache.WorldCache;

/** Mirrors the destruction Geyser accepts through its extensible block-break handler. */
final class GeyserBlockActions extends BlockBreakHandler {
    private static final Field SEQUENCE = field(WorldCache.class, "currentSequence");
    private final Supplier<CultPlayer> player;

    private GeyserBlockActions(GeyserSession session, Supplier<CultPlayer> player) {
        super(session);
        this.player = player;
    }

    static void install(GeyserSession session, Supplier<CultPlayer> player) {
        if (session.getBlockBreakHandler() instanceof GeyserBlockActions) return;
        if (session.getBlockBreakHandler().getClass() != BlockBreakHandler.class) {
            throw new IllegalStateException("Geyser block-break handler already replaced");
        }
        try { field(GeyserSession.class, "blockBreakHandler").set(session, new GeyserBlockActions(session, player)); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    @Override protected void handleBlockBreakActions(org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket packet) {
        super.handleBlockBreakActions(packet);
        var owner = player.get();
        if (owner == null) return;
        // Geyser sends these drops without changing its inventory cache.
        for (var action : packet.getPlayerActions()) {
            if (action.getAction() == org.cloudburstmc.protocol.bedrock.data.PlayerActionType.DROP_ITEM) {
                owner.getInventory().dropHeldItem(false);
            }
        }
    }

    @Override protected void destroyBlock(BlockState state, Vector3i position, Direction direction, boolean instamine) {
        boolean accepted = canDestroyBlock(state);
        super.destroyBlock(state, position, direction, instamine);
        CultPlayer owner = player.get();
        if (owner == null || !accepted) return;
        owner.compensatedWorld.advanceClientPredictionSequence();
        owner.compensatedWorld.startPredicting();
        try { NmsBlockBreakResolver.applyBlockBreak(owner, GeyserBedrockBridgeRuntime.worldBlock(session, position)); }
        finally { owner.compensatedWorld.stopPredicting(sequence(session)); }
    }

    static int sequence(GeyserSession session) {
        try { return SEQUENCE.getInt(session.getWorldCache()); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Geyser " + name, failure); }
    }
}
