package ac.cult.cultac.manager.player;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.aim.*;
import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.checks.impl.autoclicker.AutoclickerLimit;
import ac.cult.cultac.checks.impl.badpackets.*;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.breaking.*;
import ac.cult.cultac.checks.impl.chat.*;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.combat.Hitboxes;
import ac.cult.cultac.checks.impl.combat.MultiInteractA;
import ac.cult.cultac.checks.impl.combat.MultiInteractB;
import ac.cult.cultac.checks.impl.combat.Reach;
import ac.cult.cultac.checks.impl.combat.SelfInteract;
import ac.cult.cultac.checks.impl.crash.*;
import ac.cult.cultac.checks.impl.elytra.*;
import ac.cult.cultac.checks.impl.exploit.ExploitA;
import ac.cult.cultac.checks.impl.exploit.ExploitB;
import ac.cult.cultac.checks.impl.exploit.ExploitC;
import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.checks.impl.misc.GhostBlockMitigation;
import ac.cult.cultac.checks.impl.movement.EntityControl;
import ac.cult.cultac.checks.impl.movement.SetbackBlocker;
import ac.cult.cultac.checks.impl.movement.VehiclePredictionRunner;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.multiactions.*;
import ac.cult.cultac.checks.impl.packetorder.*;
import ac.cult.cultac.checks.impl.ping.PingA;
import ac.cult.cultac.checks.impl.ping.TransactionOrder;
import ac.cult.cultac.checks.impl.post.PostCheck;
import ac.cult.cultac.checks.impl.prediction.DebugHandler;
import ac.cult.cultac.checks.impl.prediction.FlagCaller;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.checks.impl.prediction.checks.Phase;
import ac.cult.cultac.checks.impl.prediction.checks.ServerStateNoSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.*;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.runner.ExplosionHandler;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.scaffolding.*;
import ac.cult.cultac.checks.impl.sprint.*;
import ac.cult.cultac.checks.impl.vehicle.*;
import ac.cult.cultac.checks.type.*;
import ac.cult.cultac.events.packets.*;
import ac.cult.cultac.network.PacketHandlerScanner;
import ac.cult.cultac.network.PacketReceiveHandler;
import ac.cult.cultac.network.PacketReceiveRoute;
import ac.cult.cultac.network.PacketRouteBuilder;
import ac.cult.cultac.network.PacketRouteBuilder.ReceiveStage;
import ac.cult.cultac.network.PacketSendHandler;
import ac.cult.cultac.network.PacketSendRoute;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.permissions.PermissionDefaultValue;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.latency.CompensatedCooldown;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import ac.cult.cultac.utils.latency.KeepAliveProcessor;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckManager {
    private static final List<Class<? extends CheckListener>> SEND_DISPATCH_LISTENER_TYPES = List.of(
            CrashD.class,
            ActionManager.class,
            FairReach.class,
            PacketEntityReplication.class,
            PacketChangeGameState.class,
            CompensatedInventory.class,
            PacketPlayerAbilities.class,
            PacketServerTickingState.class,
            PacketWorldBorder.class,
            KeepAliveProcessor.class,
            BadPacketsO.class,
            BadPacketsP.class,
            BadPacketsM.class,
            PostCheck.class,
            ExplosionHandler.class);

    private final CultPlayer player;

    ClassToInstanceMap<CheckListener> packetChecks;
    ClassToInstanceMap<CheckListener> positionCheck;
    ClassToInstanceMap<RotationListener> rotationCheck;
    ClassToInstanceMap<VehicleListener> vehicleCheck;
    ClassToInstanceMap<CheckListener> identityChecks;
    ClassToInstanceMap<CheckListener> prePredictionChecks;

    ClassToInstanceMap<BlockPlaceCheck> blockPlaceCheck;
    ClassToInstanceMap<PostPredictionListener> postPredictionCheck;
    ClassToInstanceMap<PostPredictionListener> pseudoCheck;

    List<BlockPlace> placesToCheck = new EvictingQueue<>(20);

    private final List<CheckListener> blockBreakListeners = new ArrayList<>();
    private final List<CheckListener> postFlyingBlockBreakListeners = new ArrayList<>();
    private final List<BlockBreak> breaksToCheck = new EvictingQueue<>(20);

    public ClassToInstanceMap<CheckListener> allCheckListeners;

    public final Map<Class<? extends Check>, Check> allChecks = new HashMap<>();
    private final PacketHandlerScanner recordScanner;
    private final List<TickEndHandlerRegistration> tickEndHandlers = new ArrayList<>();
    private Map<PacketType<?>, PacketReceiveRoute<Object>> earlyReceiveRoutes = Map.of();
    private final List<DecodedPacketReceiveListener> decodedReceiveListeners = new ArrayList<>();
    private final List<PacketReceiveHandler<ServerboundPacket>> nonAsyncReceiveHandlers = new ArrayList<>();
    private Map<PacketType<?>, PacketReceiveRoute<Object>> prePredictionReceiveRoutes = Map.of();
    private Map<PacketType<?>, PacketReceiveRoute<Object>> receiveRoutes = Map.of();
    private Map<PacketType<?>, PacketSendRoute<Object>> sendRoutes = Map.of();

    public Collection<Check> getAllChecks() {
        return allChecks.values();
    }

    public static List<PacketType<?>> sendDispatchPacketTypes(PacketRouteBuilder registrar) {
        LinkedHashSet<PacketType<?>> packetTypes = new LinkedHashSet<>();
        for (Class<? extends CheckListener> listenerType : SEND_DISPATCH_LISTENER_TYPES) {
            packetTypes.addAll(registrar.packetTypes(listenerType, PacketDirection.CLIENTBOUND));
        }
        return List.copyOf(packetTypes);
    }

    public CheckManager(CultPlayer player) {
        this.player = player;
        this.recordScanner = CultAPI.INSTANCE.getNetworkManager().dispatcher().scanner();

        packetChecks = buildPacketChecks(player);
        positionCheck = buildPositionChecks(player);
        rotationCheck = buildRotationChecks(player);
        vehicleCheck = buildVehicleChecks(player);
        identityChecks = buildIdentityChecks(player);
        postPredictionCheck = buildPostPredictionChecks(player);
        blockPlaceCheck = buildBlockPlaceChecks(player);
        prePredictionChecks = buildPrePredictionChecks(player);
        pseudoCheck = buildPseudoChecks(player);

        allCheckListeners = ImmutableClassToInstanceMap.<CheckListener>builder()
                .putAll(packetChecks)
                .putAll(positionCheck)
                .putAll(rotationCheck)
                .putAll(vehicleCheck)
                .putAll(identityChecks)
                .putAll(postPredictionCheck)
                .putAll(blockPlaceCheck)
                .putAll(prePredictionChecks)
                .putAll(pseudoCheck)
                .build();

        for (CheckListener listener : allCheckListeners.values()) {
            if (listener instanceof BlockBreakListener) {
                blockBreakListeners.add(listener);
            }
            if (listener instanceof PostFlyingBlockBreakListener) {
                postFlyingBlockBreakListeners.add(listener);
            }
        }

        var registrations = new PacketRegistrations(recordScanner);
        registerPacketDispatch(registrations);
        validatePacketDispatchCoverage(registrations);
        buildPacketRoutes(registrations);

        for (CheckListener listener : allCheckListeners.values()) {
            if (listener instanceof Check check) {
                allChecks.put(check.getClass(), check);
                check.reload();
            } else if (listener instanceof CultProcessor processor) {
                processor.reload();
            }
        }

        registerCheckPermissionsOnce();
    }

    private static final AtomicBoolean checkPermissionsRegistered = new AtomicBoolean(false);

    private void registerCheckPermissionsOnce() {
        if (checkPermissionsRegistered.getAndSet(true)) return;

        final String[] permissions = {
            "cult.exempt.", "cult.nosetback.", "cult.nomodifypacket.",
        };

        for (final Check check : allChecks.values()) {
            if (check.getConfigName() == null) continue;
            final String id = check.getConfigName().toLowerCase();
            for (String permissionName : permissions) {
                CultAPI.INSTANCE
                        .getPermissionManager()
                        .registerPermission(permissionName + id, PermissionDefaultValue.FALSE);
            }
        }
    }

    // Keys each listener by its concrete class, preserving list order in the resulting map.
    @SuppressWarnings("unchecked")
    private static <T> ClassToInstanceMap<T> listenerMap(List<? extends T> listeners) {
        ImmutableClassToInstanceMap.Builder<T> builder = ImmutableClassToInstanceMap.builder();
        for (T listener : listeners) {
            // Guava's put(Class<S>, S) ties key and value to the same type variable; the
            // wildcard cast broke that inference on this Guava version, so cast to Class<T>.
            builder.put((Class<T>) listener.getClass(), listener);
        }
        return builder.build();
    }

    // Packet-dispatched listeners. SetbackBlocker must stay last in this map: while it
    // blocks packets, no check after it is allowed to observe the packet.
    private static ClassToInstanceMap<CheckListener> buildPacketChecks(CultPlayer player) {
        return listenerMap(List.<CheckListener>of(
                new SimulationProcessor(player),
                player.actionManager,
                player.pluginChannelManager,
                new Reach(player),
                new Hitboxes(player),
                new FairReach(player),
                player.packetEntityReplication,
                new PacketChangeGameState(player),
                new CompensatedInventory(player),
                new PacketPlayerAbilities(player),
                new PacketServerTickingState(player),
                new PacketWorldBorder(player),
                player.keepAliveProcessor,
                new ChatA(player),
                new ChatB(player),
                new ChatC(player),
                new ChatD(player),
                new ClientBrand(player),
                new NoFallExecutor(player),
                new PingA(player),
                new TransactionOrder(player),
                new BadPacketsO(player),
                new BadPacketsA(player),
                new BadPacketsB(player),
                new BadPacketsC(player),
                new BadPacketsD(player),
                new BadPacketsE(player),
                new BadPacketsF(player),
                new BadPacketsG(player),
                new BadPacketsI(player),
                new BadPacketsJ(player),
                new BadPacketsVehicle(player),
                new BadPacketsK(player),
                new BadPacketsL(player),
                new BadPacketsM(player),
                new BadPacketsN(player),
                new BadPacketsP(player),
                new BadPacketsQ(player),
                new BadPacketsR(player),
                new BadPacketsS(player),
                new BadPacketsT(player),
                new BadPacketsU(player),
                new BadPacketsV(player),
                new BadPacketsW(player),
                new BadPacketsY(player),
                new BadPacketsZ(player),
                player.packetOrderProcessor,
                new PacketOrderB(player),
                new PacketOrderC(player),
                new PacketOrderD(player),
                new PacketOrderO(player),
                new SelfInteract(player),
                new MultiActionsA(player),
                new MultiActionsB(player),
                new MultiActionsC(player),
                new MultiActionsD(player),
                new MultiActionsE(player),
                new VehicleA(player),
                new VehicleB(player),
                new VehicleD(player),
                new VehicleE(player),
                new VehicleF(player),
                new AirLiquidBreak(player),
                new WrongBreak(player),
                new RotationBreak(player),
                new FastBreak(player),
                new NoSwingBreak(player),
                new FarBreak(player),
                new InvalidBreak(player),
                new PositionBreakA(player),
                new PositionBreakB(player),
                new AutoclickerLimit(player),
                new SetbackBlocker(player)));
    }

    private static ClassToInstanceMap<CheckListener> buildPositionChecks(CultPlayer player) {
        return listenerMap(List.<CheckListener>of(new CompensatedCooldown(player)));
    }

    private static ClassToInstanceMap<RotationListener> buildRotationChecks(CultPlayer player) {
        return listenerMap(List.<RotationListener>of(
                // AimProcessor attaches itself to the update before dependent checks consume it.
                new AimProcessor(player), new AimModulo360(player), new AimDuplicateLook(player)));
    }

    private static ClassToInstanceMap<VehicleListener> buildVehicleChecks(CultPlayer player) {
        return listenerMap(List.<VehicleListener>of(new VehiclePredictionRunner(player)));
    }

    // Runtime check identities which deliberately own no callback in the
    // baseline. Keeping them out of callback lists preserves registration and
    // configuration without inventing behavior.
    private static ClassToInstanceMap<CheckListener> buildIdentityChecks(CultPlayer player) {
        return listenerMap(List.<CheckListener>of(new VehicleC(player), new BedrockMovement(player)));
    }

    private static ClassToInstanceMap<PostPredictionListener> buildPostPredictionChecks(CultPlayer player) {
        return listenerMap(List.<PostPredictionListener>of(
                player.getGhostBlockMitigator(),
                new FlagCaller(player),
                new SuperDebug(player),
                new NoSlow(player),
                player.getServerStateNoSlow(),
                new Phase(player),
                new PostCheck(player),
                new NegativeTimerCheck(player),
                new OffsetHandler(player),
                new DebugHandler(player),
                new EntityControl(player), // registered listener is currently a no-op
                new SetbackTeleportUtil(player), // setback state depends on current friction
                player.knockbackHandler,
                player.explosionHandler,
                player.compensatedFireworks,
                player.lastInstanceManager,
                new PacketOrderA(player),
                new PacketOrderE(player),
                new PacketOrderF(player),
                new PacketOrderG(player),
                new PacketOrderH(player),
                new PacketOrderI(player),
                new PacketOrderJ(player),
                new PacketOrderK(player),
                new PacketOrderL(player),
                new PacketOrderM(player),
                new BadPacketsX(player),
                new SprintA(player),
                new SprintB(player),
                new SprintC(player),
                new SprintD(player),
                new SprintE(player),
                new SprintF(player),
                new SprintG(player),
                new MultiInteractA(player),
                new MultiInteractB(player),
                new ElytraA(player),
                new ElytraB(player),
                new ElytraC(player),
                new ElytraD(player),
                new ElytraE(player),
                new ElytraF(player),
                new ElytraG(player),
                new ElytraH(player),
                new ElytraI(player),
                new MultiBreak(player)));
    }

    private static ClassToInstanceMap<BlockPlaceCheck> buildBlockPlaceChecks(CultPlayer player) {
        return listenerMap(List.<BlockPlaceCheck>of(
                new BadPacketsH(player),
                new InvalidPlaceA(player),
                new InvalidPlaceB(player),
                new AirLiquidPlace(player),
                new MultiPlace(player),
                new MultiActionsF(player),
                new MultiActionsG(player),
                new CrashG(player),
                new FarPlace(player),
                new FabricatedPlace(player),
                new PositionPlace(player),
                new RotationPlace(player),
                new PacketOrderN(player),
                new DuplicateRotPlace(player),
                new GhostBlockMitigation(player)));
    }

    private static ClassToInstanceMap<CheckListener> buildPrePredictionChecks(CultPlayer player) {
        return listenerMap(List.<CheckListener>of(
                new TimerCheck(player),
                new TickTimer(player),
                new DumbTimer(player),
                new CrashA(player),
                new CrashB(player),
                new CrashC(player),
                new CrashD(player),
                new CrashE(player),
                new CrashF(player),
                new CrashH(player),
                new CrashI(player),
                new ExploitA(player),
                new ExploitB(player),
                new ExploitC(player),
                new VehicleTimer(player)));
    }

    private static ClassToInstanceMap<PostPredictionListener> buildPseudoChecks(CultPlayer player) {
        return listenerMap(List.<PostPredictionListener>of(
                new InvalidStep(player),
                new NoSneakSlow(player),
                new OmniSprint(player),
                new NoFallPseudo(player),
                new VerticalOffset(player),
                new ElytraPseudo(player),
                new RiptideOffset(player),
                new VehicleOffset(player),
                new BoatPseudo(player),
                new HappyGhastPseudo(player),
                new Speed(player),
                new Strafe(player),
                new Angle(player)));
    }

    private void registerPacketDispatch(PacketRegistrations registrations) {
        // These callbacks were pre-Via in the committed listener. The raw
        // packet identity is unavailable here, but decoded cancellation and
        // relative ordering are preserved ahead of every ordinary processor.
        registerEarlyReceive(registrations, packetChecks.get(ChatA.class));
        registerEarlyReceive(registrations, packetChecks.get(ChatB.class));
        registerEarlyReceive(registrations, packetChecks.get(ChatC.class));
        registerEarlyReceive(registrations, packetChecks.get(ChatD.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsA.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsC.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsF.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsG.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsI.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsK.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsM.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsW.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsY.class));
        registerEarlyReceive(registrations, packetChecks.get(BadPacketsZ.class));
        registerEarlyReceive(registrations, packetChecks.get(PacketOrderB.class));
        registerEarlyReceive(registrations, packetChecks.get(PacketOrderC.class));
        registerEarlyReceive(registrations, packetChecks.get(PacketOrderD.class));
        registerEarlyReceive(registrations, packetChecks.get(SelfInteract.class));
        registerEarlyReceive(registrations, packetChecks.get(MultiActionsA.class));
        registerEarlyReceive(registrations, packetChecks.get(MultiActionsE.class));
        registerEarlyReceive(registrations, packetChecks.get(FastBreak.class));
        registerEarlyReceive(registrations, postPredictionCheck.get(MultiBreak.class));
        registerEarlyReceive(registrations, packetChecks.get(NoSwingBreak.class));

        var packetOrder = packetChecks.getInstance(PacketOrderO.class);
        registerNonAsyncReceive(packetOrder, packetOrder::onNonAsyncPacket);
        registerDecodedReceive(packetChecks.get(BadPacketsJ.class));
        registerDecodedReceive(postPredictionCheck.get(BadPacketsX.class));
        registerDecodedReceive(postPredictionCheck.get(ElytraC.class));

        registerPreReceive(registrations, prePredictionChecks.get(TimerCheck.class));
        registerPreReceive(registrations, prePredictionChecks.get(TickTimer.class));
        registerPreReceive(registrations, prePredictionChecks.get(DumbTimer.class));
        registerPreReceive(registrations, prePredictionChecks.get(CrashA.class));
        registerPreReceive(registrations, prePredictionChecks.get(CrashC.class));
        registerPreReceive(registrations, prePredictionChecks.get(CrashI.class));
        registerPreReceive(registrations, prePredictionChecks.get(VehicleTimer.class));
        registerPreReceive(registrations, packetChecks.get(SetbackBlocker.class));

        registerReceive(registrations, packetChecks.get(SimulationProcessor.class));
        registerReceive(registrations, packetChecks.get(ActionManager.class));
        registerReceive(registrations, packetChecks.get(PluginChannelManager.class));
        registerReceive(registrations, packetChecks.get(Reach.class));
        registerReceive(registrations, packetChecks.get(FairReach.class));
        registerReceive(registrations, packetChecks.get(CompensatedInventory.class));
        registerReceive(registrations, packetChecks.get(PacketPlayerAbilities.class));
        registerReceive(registrations, packetChecks.get(KeepAliveProcessor.class));
        registerReceive(registrations, packetChecks.get(BadPacketsO.class));
        registerReceive(registrations, packetChecks.get(ClientBrand.class));
        registerReceive(registrations, packetChecks.get(NoFallExecutor.class));
        registerReceive(registrations, prePredictionChecks.get(ExploitA.class));
        registerReceive(registrations, prePredictionChecks.get(ExploitB.class));
        registerReceive(registrations, packetChecks.get(BadPacketsD.class));
        registerReceive(registrations, packetChecks.get(BadPacketsE.class));
        registerReceive(registrations, packetChecks.get(BadPacketsJ.class));
        registerReceive(registrations, packetChecks.get(BadPacketsL.class));
        registerReceive(registrations, packetChecks.get(BadPacketsP.class));
        registerReceive(registrations, packetChecks.get(BadPacketsQ.class));
        registerReceive(registrations, packetChecks.get(BadPacketsR.class));
        registerReceive(registrations, packetChecks.get(BadPacketsT.class));
        registerReceive(registrations, packetChecks.get(BadPacketsU.class));
        registerReceive(registrations, packetChecks.get(BadPacketsV.class));
        registerReceive(registrations, blockPlaceCheck.get(BadPacketsH.class));
        registerReceive(registrations, packetChecks.get(PacketOrderProcessor.class));
        registerReceive(registrations, packetChecks.get(MultiActionsC.class));
        registerReceive(registrations, packetChecks.get(MultiActionsD.class));
        registerReceive(registrations, packetChecks.get(VehicleA.class));
        registerReceive(registrations, packetChecks.get(VehicleD.class));
        registerReceive(registrations, packetChecks.get(VehicleE.class));
        registerReceive(registrations, packetChecks.get(VehicleF.class));
        registerReceive(registrations, packetChecks.get(FarBreak.class));
        registerReceive(registrations, packetChecks.get(PositionBreakA.class));
        registerReceive(registrations, packetChecks.get(AutoclickerLimit.class));
        registerReceive(registrations, prePredictionChecks.get(CrashB.class));
        registerReceive(registrations, prePredictionChecks.get(CrashD.class));
        registerReceive(registrations, prePredictionChecks.get(CrashE.class));
        registerReceive(registrations, prePredictionChecks.get(CrashF.class));
        registerReceive(registrations, blockPlaceCheck.get(CrashG.class));
        registerReceive(registrations, prePredictionChecks.get(CrashH.class));

        registerTickEnd(packetChecks.get(ActionManager.class));
        registerTickEnd(packetChecks.get(Reach.class));
        registerTickEnd(vehicleCheck.get(VehiclePredictionRunner.class));
        // MCP-Reborn Minecraft#tick computes the attack hit result before
        // ClientLevel#tickEntities interpolates remote entities, then sends
        // ServerboundClientTickEndPacket. Reach consumes queued attacks first;
        // entity interpolation advances afterward for the next client tick.
        registerTickEnd(packetChecks.get(PacketEntityReplication.class));
        registerTickEnd(packetChecks.get(SimulationProcessor.class));
        registerTickEnd(packetChecks.get(AutoclickerLimit.class));
        registerTickEnd(packetChecks.get(PacketWorldBorder.class));
        registerTickEnd(positionCheck.get(CompensatedCooldown.class));

        registerReceive(registrations, postPredictionCheck.get(NoSlow.class));
        registerReceive(registrations, postPredictionCheck.get(ServerStateNoSlow.class));
        registerReceive(registrations, postPredictionCheck.get(Phase.class));
        registerReceive(registrations, postPredictionCheck.get(PostCheck.class));
        registerReceive(registrations, postPredictionCheck.get(NegativeTimerCheck.class));
        // AimDuplicateLook (rotation listener) declares no packet handlers; registering it for
        // receive dispatch would throw in registerReceive at CheckManager construction.

        for (Class<? extends PostPredictionListener> type : List.of(
                PacketOrderA.class,
                PacketOrderE.class,
                PacketOrderF.class,
                PacketOrderG.class,
                PacketOrderH.class,
                PacketOrderI.class,
                PacketOrderJ.class,
                PacketOrderK.class,
                PacketOrderL.class,
                PacketOrderM.class,
                BadPacketsX.class,
                SprintD.class,
                SprintE.class,
                MultiInteractA.class,
                MultiInteractB.class,
                ElytraB.class,
                ElytraC.class,
                ElytraD.class,
                ElytraE.class,
                ElytraF.class,
                ElytraG.class,
                ElytraH.class,
                ElytraI.class)) {
            registerReceive(registrations, postPredictionCheck.get(type));
        }
        for (Class<? extends BlockPlaceCheck> type : List.of(
                MultiPlace.class, MultiActionsF.class, MultiActionsG.class, PositionPlace.class, PacketOrderN.class)) {
            registerReceive(registrations, blockPlaceCheck.get(type));
        }

        for (Class<? extends CheckListener> listenerType : SEND_DISPATCH_LISTENER_TYPES) {
            registerSend(registrations, allCheckListeners.get(listenerType));
        }
    }

    private void registerPreReceive(PacketRegistrations registrations, CheckListener listener) {
        registerReceive(registrations.prePredictionReceive, registrations.receiveListeners, listener);
    }

    private void registerEarlyReceive(PacketRegistrations packetRegistrations, CheckListener listener) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        packetRegistrations.receiveListeners.add(listener);

        List<PacketHandlerScanner.ReceiveRegistration> registrations = recordScanner.receiveHandlers(listener);
        if (registrations.isEmpty()) {
            if (!recordScanner.hasReceiveHandlerDeclaration(listener.getClass())) {
                throw new IllegalStateException("No receive @CultPacketHandler methods on "
                        + listener.getClass().getName());
            }
            return;
        }

        for (PacketHandlerScanner.ReceiveRegistration registration : registrations) {
            PacketReceiveHandler<Object> handler = (event, player, packet) -> {
                if (shouldDispatch(listener)) {
                    registration.handler().handle(event, player, packet);
                }
            };
            packetRegistrations.receive.receiveRoute(
                    registration.packetType(), PacketListenerPriority.NORMAL, handler, ReceiveStage.EARLY);
        }
    }

    private void registerNonAsyncReceive(CheckListener listener, PacketReceiveHandler<ServerboundPacket> handler) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        nonAsyncReceiveHandlers.add((event, player, packet) -> {
            if (shouldDispatch(listener)) {
                handler.handle(event, player, packet);
            }
        });
    }

    private void registerDecodedReceive(CheckListener listener) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        if (!(listener instanceof DecodedPacketReceiveListener decodedListener)) {
            throw new IllegalStateException(
                    listener.getClass().getName() + " is not a decoded packet receive listener");
        }
        decodedReceiveListeners.add(decodedListener);
    }

    private void registerReceive(PacketRegistrations registrations, CheckListener listener) {
        registerReceive(registrations.receive, registrations.receiveListeners, listener);
    }

    private void registerSend(PacketRegistrations registrations, CheckListener listener) {
        registerSend(registrations.send, registrations.sendListeners, listener);
    }

    private void registerTickEnd(CheckListener listener) {
        if (listener == null) {
            return;
        }
        if (!shouldRegister(listener)) {
            return;
        }
        if (!(listener instanceof ClientTickEndListener tickEndListener)) {
            throw new IllegalStateException(listener.getClass().getName() + " is not a client tick-end listener");
        }
        tickEndHandlers.add(new TickEndHandlerRegistration(tickEndListener));
    }

    private void registerReceive(
            PacketRouteBuilder handlers, Set<CheckListener> registeredListeners, CheckListener listener) {
        if (listener == null) {
            return;
        }
        if (!shouldRegister(listener)) {
            return;
        }
        registeredListeners.add(listener);

        List<PacketHandlerScanner.ReceiveRegistration> registrations = recordScanner.receiveHandlers(listener);
        if (registrations.isEmpty()) {
            if (!recordScanner.hasReceiveHandlerDeclaration(listener.getClass())) {
                throw new IllegalStateException("No receive @CultPacketHandler methods on "
                        + listener.getClass().getName());
            }
            return;
        }
        for (PacketHandlerScanner.ReceiveRegistration registration : registrations) {
            PacketReceiveHandler<Object> handler = (event, player, packet) -> {
                if (shouldDispatch(listener)) {
                    registration.handler().handle(event, player, packet);
                }
            };
            handlers.receiveRoute(
                    registration.packetType(), PacketListenerPriority.NORMAL, handler, ReceiveStage.ORDINARY);
        }
    }

    private void registerSend(
            PacketRouteBuilder handlers, Set<CheckListener> registeredListeners, CheckListener listener) {
        if (listener == null) {
            return;
        }
        if (!shouldRegister(listener)) {
            return;
        }
        registeredListeners.add(listener);

        List<PacketHandlerScanner.SendRegistration> registrations = recordScanner.sendHandlers(listener);
        if (registrations.isEmpty()) {
            if (!recordScanner.hasSendHandlerDeclaration(listener.getClass())) {
                throw new IllegalStateException("No send @CultPacketHandler methods on "
                        + listener.getClass().getName());
            }
            return;
        }
        for (PacketHandlerScanner.SendRegistration registration : registrations) {
            PacketSendHandler<Object> handler = (event, player, packet) -> {
                if (shouldDispatch(listener)) {
                    registration.handler().handle(event, player, packet);
                }
            };
            handlers.sendRoute(registration.packetType(), PacketListenerPriority.NORMAL, handler);
        }
    }

    private void validatePacketDispatchCoverage(PacketRegistrations registrations) {
        List<String> missing = new ArrayList<>();
        for (CheckListener listener : allCheckListeners.values()) {
            if (!shouldRegister(listener)) {
                continue;
            }
            if (recordScanner.hasReceiveHandlerDeclaration(listener.getClass())
                    && !registrations.receiveListeners.contains(listener)) {
                missing.add(listener.getClass().getName() + " receive");
            }
            if (recordScanner.hasSendHandlerDeclaration(listener.getClass())
                    && !registrations.sendListeners.contains(listener)) {
                missing.add(listener.getClass().getName() + " send");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Unregistered packet handler declarations: " + String.join(", ", missing));
        }
    }

    private void buildPacketRoutes(PacketRegistrations registrations) {
        earlyReceiveRoutes = registrations.receive.receiveRoutes(ReceiveStage.EARLY);
        prePredictionReceiveRoutes = registrations.prePredictionReceive.receiveRoutes(ReceiveStage.ORDINARY);
        receiveRoutes = registrations.receive.receiveRoutes(ReceiveStage.ORDINARY);
        sendRoutes = registrations.send.sendRoutes();
    }

    private boolean shouldRegister(CheckListener listener) {
        return !(listener instanceof Check check) || !player.isBedrockMovement() || check.isBedrockSupported();
    }

    private boolean shouldDispatch(CheckListener listener) {
        return shouldRegister(listener);
    }

    public void reload() {
        for (CheckListener value : allCheckListeners.values()) {
            if (value instanceof CultProcessor) {
                CultProcessor processor = (CultProcessor) value;
                processor.reload();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends CheckListener> T getListener(Class<T> check) {
        return (T) allCheckListeners.get(check);
    }

    @SuppressWarnings("unchecked")
    public <T extends Check> T getCheck(Class<T> check) {
        return (T) allChecks.get(check);
    }

    public void dispatchPrePredictionReceive(final PacketReceiveEvent packet) {
        Object routedPacket = packet.getPacket();
        PacketReceiveRoute route = prePredictionReceiveRoutes.get(packet.getPacketType());
        if (route != null) {
            route.dispatch(packet, player, routedPacket);
        }
    }

    public void dispatchEarlyReceive(final PacketReceiveEvent event) {
        Object routedPacket = event.getPacket();
        var route = earlyReceiveRoutes.get(event.getPacketType());
        if (route != null) route.dispatch(event, player, routedPacket);
    }

    public void dispatchReceiveHandlers(final PacketReceiveEvent packet) {
        dispatchDecodedReceiveObservers(packet);

        Object routedPacket = packet.getPacket();
        PacketReceiveRoute route = receiveRoutes.get(packet.getPacketType());
        if (route != null) {
            route.dispatch(packet, player, routedPacket);
        }

        dispatchNonAsyncReceive(packet);

        if (routedPacket instanceof ServerboundMovePlayer
                && player.compensatedWorld.pistons.usesLegacyCollision()
                && !player.packetStateData.lastPacketWasTeleport) {
            // 1.7/1.8 send a movement, rotation or status packet every player tick,
            // before World#updateEntities ticks TileEntityPiston. Phase is one of
            // the receive handlers above and must still see the movement's phase.
            player.compensatedWorld.pistons.onLegacyMovementTick();
        }

        if (packet.getPacketType() == ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END) {
            // MCP-Reborn Minecraft#tick sends ServerboundClientTickEndPacket only
            // after ClientLevel#tickEntities and Level#tickBlockEntities have
            // both finished. Tick-end handlers that simulate the completed client
            // tick therefore must see the post-block-entity piston phase and the
            // post-entity interpolation state, not the stale pre-tick-end cache.
            player.compensatedWorld.onClientTickEnd();
            for (TickEndHandlerRegistration handler : tickEndHandlers) {
                handler.handle(packet);
            }
        }
    }

    public void dispatchDecodedReceiveObservers(final PacketReceiveEvent event) {
        if (event.getPhase() != ConnectionPhase.PLAY) {
            return;
        }
        for (DecodedPacketReceiveListener listener : decodedReceiveListeners) {
            if (shouldDispatch(listener)) {
                listener.onDecodedPacketReceive(event);
            }
        }
    }

    /**
     * Dispatches explicitly registered callbacks for one non-async PLAY packet,
     * including packets that intentionally bypass the normal check route.
     */
    public void dispatchNonAsyncReceive(final PacketReceiveEvent<?> event) {
        if (LegacyPacketEventSemantics.isAsync(event)) {
            return;
        }
        ServerboundPacket packet = event.getPacket();
        for (PacketReceiveHandler<ServerboundPacket> handler : nonAsyncReceiveHandlers) {
            handler.handle(event, player, packet);
        }
    }

    public void dispatchSendHandlers(final PacketSendEvent<?> packet) {
        Object routedPacket = packet.getPacket();
        PacketSendRoute route = sendRoutes.get(packet.getPacketType());
        if (route != null) {
            route.dispatch(packet, player, routedPacket);
        }
    }

    public void doChecksWithKnownLook() {
        for (BlockPlace place : placesToCheck) {
            for (BlockPlaceCheck check : blockPlaceCheck.values()) {
                if (!shouldDispatch(check)) {
                    continue;
                }
                check.onPostFlyingBlockPlace(place);
            }
        }
        placesToCheck.clear();

        // Evaluate breaks at the position claimed when they were queued.
        if (!breaksToCheck.isEmpty()) {
            double lastX = player.x;
            double lastY = player.y;
            double lastZ = player.z;

            Vec3 claimed = player.packetStateData.clientSidePosition;
            player.x = claimed.x;
            player.y = claimed.y;
            player.z = claimed.z;

            if (player.inVehicle()) {
                Vec3 posFromVehicle = BoundingBoxSize.getRidingOffsetFromVehicle(
                        player.compensatedEntities.getSelf().getRiding(), player);
                player.x = posFromVehicle.x;
                player.y = posFromVehicle.y;
                player.z = posFromVehicle.z;
            }

            for (BlockBreak blockBreak : breaksToCheck) {
                for (CheckListener check : postFlyingBlockBreakListeners) {
                    if (!shouldDispatch(check)) {
                        continue;
                    }
                    ((PostFlyingBlockBreakListener) check).onPostFlyingBlockBreak(blockBreak);
                }
            }
            breaksToCheck.clear();

            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
        }
    }

    public void onPositionUpdate(final PositionUpdate position) {
        for (CheckListener check : prePredictionChecks.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPositionUpdate(position);
        }
        for (CheckListener check : positionCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPositionUpdate(position);
        }
        for (CheckListener check : packetChecks.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPositionUpdate(position);
        }
    }

    public void onRotationUpdate(final RotationUpdate rotation) {
        for (RotationListener check : rotationCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.process(rotation);
        }
        for (BlockPlaceCheck check : blockPlaceCheck.values()) {
            if (!(check instanceof RotationListener rotationListener) || !shouldDispatch(check)) {
                continue;
            }
            rotationListener.process(rotation);
        }
    }

    public void onVehiclePositionUpdate(final VehiclePositionUpdate update) {
        for (VehicleListener check : vehicleCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.process(update);
        }
    }

    public void onPredictionFinish(final PredictionComplete complete) {
        for (PostPredictionListener check : postPredictionCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPredictionComplete(complete);
        }
        // Some block-place checks also consume completed predictions.
        for (BlockPlaceCheck check : blockPlaceCheck.values()) {
            if (!(check instanceof PostPredictionListener postPredictionListener)) {
                continue;
            }
            if (!shouldDispatch(check)) {
                continue;
            }
            postPredictionListener.onPredictionComplete(complete);
        }
        for (PostPredictionListener check : pseudoCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            if (!MovementProfiles.forPlayer(player).shouldRunPseudoCheck(check.getClass())) {
                continue;
            }
            check.onPredictionComplete(complete);
        }
    }

    public void onBlockPlace(final BlockPlace place) {
        for (BlockPlaceCheck check : blockPlaceCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onBlockPlace(place);
        }
    }

    public void queuePostFlyingBlockPlace(final BlockPlace place) {
        placesToCheck.add(place);
    }

    public void onBlockBreak(final BlockBreak blockBreak) {
        for (CheckListener check : blockBreakListeners) {
            if (!shouldDispatch(check)) {
                continue;
            }
            ((BlockBreakListener) check).onBlockBreak(blockBreak);
        }
    }

    public void queuePostFlyingBlockBreak(final BlockBreak blockBreak) {
        breaksToCheck.add(blockBreak);
    }

    private ExplosionHandler explosionHandlerCache;

    public ExplosionHandler getExplosionHandler() {
        if (explosionHandlerCache == null) {
            explosionHandlerCache = getListener(ExplosionHandler.class);
        }
        return explosionHandlerCache;
    }

    private SimulationProcessor cachedSimulationProcessor = null;

    public SimulationProcessor getSimulationProcessor() {
        if (cachedSimulationProcessor == null) {
            cachedSimulationProcessor = getListener(SimulationProcessor.class);
        }
        return cachedSimulationProcessor;
    }

    private SetbackTeleportUtil cachedSetbackUtil = null;

    public SetbackTeleportUtil getSetbackUtil() {
        if (cachedSetbackUtil == null) {
            cachedSetbackUtil = getListener(SetbackTeleportUtil.class);
        }
        return cachedSetbackUtil;
    }

    public NoFallExecutor getNoFall() {
        return getListener(NoFallExecutor.class);
    }

    private KnockbackHandler knockbackHandlerCache;

    public KnockbackHandler getKnockbackHandler() {
        if (knockbackHandlerCache == null) {
            knockbackHandlerCache = getListener(KnockbackHandler.class);
        }
        return knockbackHandlerCache;
    }

    private Angle angleCheck;

    public Angle getAngleCheck() {
        if (angleCheck == null) {
            angleCheck = getListener(Angle.class);
        }
        return angleCheck;
    }

    private CompensatedInventory inventory = null;

    public CompensatedInventory getInventory() {
        if (inventory == null) inventory = getListener(CompensatedInventory.class);
        return inventory;
    }

    public CompensatedCooldown getCompensatedCooldown() {
        return getListener(CompensatedCooldown.class);
    }

    public DebugHandler getDebugHandler() {
        return getListener(DebugHandler.class);
    }

    public OffsetHandler getOffsetHandler() {
        return getListener(OffsetHandler.class);
    }

    /** Construction data only: callbacks retain their listener, never these collections. */
    private static final class PacketRegistrations {
        final PacketRouteBuilder prePredictionReceive;
        final PacketRouteBuilder receive;
        final PacketRouteBuilder send;

        PacketRegistrations(PacketHandlerScanner scanner) {
            prePredictionReceive = new PacketRouteBuilder(scanner);
            receive = new PacketRouteBuilder(scanner);
            send = new PacketRouteBuilder(scanner);
        }

        final Set<CheckListener> receiveListeners = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<CheckListener> sendListeners = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private record TickEndHandlerRegistration(ClientTickEndListener listener) {
        void handle(PacketReceiveEvent event) {
            listener.onPlayerTickEnd(event);
        }
    }
}
