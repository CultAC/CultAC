package ac.cult.cultac.utils.blockplace;

import ac.cult.blocksim.SimAction;
import ac.cult.blocksim.SimInput;
import ac.cult.blocksim.SimResult;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.BreakSession;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.latency.BlockSimulatorInventory;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.List;
import java.util.Map;

/** Compensated action boundary. The bundled simulator owns all client action behavior. */
public final class ClientBlockActions {
    private ClientBlockActions() {}

    /** Prepared host changes allow validation before any compensated state is mutated. */
    public record Prediction(
            SimInput input,
            SimResult result,
            List<Map.Entry<BlockPos, Integer>> writes,
            Map<Integer, SimItemStack> inventory) {
        public Prediction {
            writes = List.copyOf(writes);
            inventory = Map.copyOf(inventory);
        }
    }

    public static void useOn(CultPlayer player, BlockPlace place) {
        var pos = place.getPlacedAgainstBlockLocation();
        var cursor = place.getCursor();
        var hit = new Vec3(pos.getX() + cursor.x, pos.getY() + cursor.y, pos.getZ() + cursor.z);
        // UseItemOn supplies a hit position. Match the existing queued-action camera reconstruction.
        double dx = hit.x() - player.x, dy = hit.y() - (player.y + player.getEyeHeight()), dz = hit.z() - player.z;
        float yaw = player.xRot, pitch = player.yRot;
        if (Math.hypot(dx, dz) >= 1e-7 || Math.abs(dy) >= 1e-7) {
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        }
        var action = new SimAction.UseOn(
                hand(place.getHand()),
                new BlockHit(
                        position(pos),
                        ac.cult.blocksim.engine.Direction.valueOf(
                                place.getDirection().name()),
                        hit,
                        place.isInside()));
        apply(player, predict(player, action, yaw, pitch), place);
    }

    public static void use(CultPlayer player, Hand hand) {
        if (player.getInventory().getHandItem(hand).is("minecraft:trident")) {
            // TridentItem.use only starts charging. Its fixed 72000-tick duration
            // also excludes ItemStack's instant-use inventory/cooldown effects.
            // The existing ActionManager packet handler owns use-state inference.
            return;
        }
        apply(player, predict(player, new SimAction.Use(hand(hand)), player.xRot, player.yRot), null);
    }

    /** The existing dig caller has already resolved instant/completed destruction. */
    public static boolean breakBlock(CultPlayer player, BlockPos pos) {
        var prediction = predict(player, new SimAction.DestroyBlock(position(pos)), player.xRot, player.yRot);
        apply(player, prediction, null);
        return prediction.result().decline() == null && prediction.result().accepted();
    }

    /** Read-only action preparation, also usable by the off-git shadow comparison. */
    public static Prediction predict(CultPlayer player, SimAction action, float yaw, float pitch) {
        if (player.registryState == null) player.registryState = new ClientComponentRegistries();
        var source =
                player.user.getCultConnection().dispatcher().runtime().data().version();
        var client = player.isBedrockMovement()
                ? source
                : ProtocolVersion.of(player.getClientVersion().getProtocolVersion());
        var binding = player.registryState.blockSimulatorActions(
                source, client, player.compensatedWorld.clientFeatures(), DataTables.defaults());
        var items = binding.registries().items();
        boolean destroying = action instanceof SimAction.DestroyBlock;
        var inventory = destroying
                ? BlockSimulatorInventory.captureHands(player.getInventory(), player.canInstabuild, items)
                : BlockSimulatorInventory.capture(player.getInventory(), player.canInstabuild, items);
        var mode = SimPlayer.GameMode.valueOf(player.gamemode.name());
        var state = new SimPlayer.State(
                new Vec3(player.x, player.y, player.z),
                yaw,
                pitch,
                player.isSneaking,
                player.canInstabuild,
                mode != SimPlayer.GameMode.ADVENTURE && mode != SimPlayer.GameMode.SPECTATOR,
                player.canUseGameMasterBlocks(),
                mode,
                player.isInvulnerable,
                player.food,
                5.0F);
        SimPlayer.ActiveUse active = null;
        if (player.packetStateData.isSlowedByUsingItem()) {
            var hand = hand(player.packetStateData.itemInUseHand);
            // These action entry points only read use presence and its hand; no use timer is inferred.
            active = new SimPlayer.ActiveUse(
                    hand,
                    inventory.get(hand == ac.cult.blocksim.interaction.Hand.MAIN_HAND ? inventory.selected() : 40),
                    0);
        }
        var collision = JavaCollisionState.current(player);
        var owner = new SimPlayer(
                state,
                inventory,
                active,
                new SimPlayer.Sight(
                        new Vec3(player.x, player.y + player.getEyeHeight(), player.z),
                        player.compensatedEntities.getSelf().getBlockInteractionRange()),
                new EntityCollisionContext(
                        player.boundingBox.minY,
                        player.isSneaking,
                        collision == null ? 0.0 : collision.fallDistance(),
                        player.getInventory().getBoots().getItem()
                                == ac.cult.cultac.utils.inventory.ItemTypes.LEATHER_BOOTS,
                        false),
                new SimPlayer.Movement(player.isGliding));
        var world = new BlockSimulatorWorldView(
                player.compensatedWorld, player.checkManager.getListener(PacketWorldBorder.class), binding.states());
        SmoketestPredictionSafety.detachedAdapter(world);
        var input = new SimInput(
                world,
                owner,
                player.checkManager.getCompensatedCooldown().blockSimulatorSnapshot(),
                null,
                BreakSession.State.initial(items.empty()),
                binding.tags());
        var result = binding.simulator().simulate(action, input);
        var writes = result.writes().stream()
                .map(write -> Map.entry(
                        new BlockPos(
                                write.pos().x(), write.pos().y(), write.pos().z()),
                        binding.states().toHost(write.newState())))
                .toList();
        return new Prediction(
                input,
                result,
                writes,
                destroying
                        ? Map.of()
                        : BlockSimulatorInventory.resolveChanges(
                                inventory, result.player().inventory()));
    }

    public static void apply(CultPlayer player, Prediction prediction, BlockPlace place) {
        var result = prediction.result();
        if (result.decline() != null) return;
        for (var write : prediction.writes()) {
            if (place == null) player.compensatedWorld.updateBlock(write.getKey(), write.getValue());
            else place.applyResolvedPrediction(write.getKey(), write.getValue());
        }
        result.blockEntities()
                .forEach((pos, data) -> player.compensatedWorld.applyPredictedBlockEntityData(
                        new BlockPos(pos.x(), pos.y(), pos.z()), data));
        var storage = player.getInventory().inventory.getInventoryStorage();
        prediction
                .inventory()
                .forEach((slot, stack) -> storage.setItem(BlockSimulatorInventory.storageSlot(slot), stack));
        player.checkManager
                .getCompensatedCooldown()
                .applyBlockSimulatorChanges(prediction.input().cooldowns(), result.cooldowns());
        player.food = result.player().foodLevel();
        var active = result.player().activeUse();
        if (active != null)
            player.packetStateData.itemInUseHand = Hand.valueOf(active.hand().name());
        player.packetStateData.setSlowedByUsingItem(active != null);
    }

    private static ac.cult.blocksim.interaction.Hand hand(Hand hand) {
        return ac.cult.blocksim.interaction.Hand.valueOf(hand.name());
    }

    private static ac.cult.blocksim.engine.BlockPos position(BlockPos pos) {
        return new ac.cult.blocksim.engine.BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }
}
