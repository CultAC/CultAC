package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Holds every call that kicks a player, cancels or rewrites a packet, resyncs or teleports to a condition on
 * {@code isObserveOnly()}, {@code shouldModifyPackets()} or {@code failsOpen()} (true in observe-only), or to an
 * entry in {@link #ALLOWED}. A call is guarded when
 * a condition naming the guard encloses it: an {@code if} or {@code ?:} around it, the left side of an {@code &&} or
 * {@code ||}, or an earlier {@code if (...) return|continue|break|throw} in an enclosing block. The scan is syntactic:
 * it resolves neither receivers nor the guard's polarity, which {@code ObserveOnlyTest} pins.
 */
class ObserveOnlyCallSiteTest {
    private static final List<String> MODULES = List.of(
            "common",
            "bukkit",
            "velocity-platform",
            "velocity-bootstrap",
            "paper-companion",
            "bridge-protocol",
            "reload-api",
            "reload-host",
            "reload-services",
            "reload-integrations",
            "protocol-netty");

    private static final Set<String> GAMEPLAY = Set.of(
            "kickPlayer",
            "kick",
            "disconnect",
            "closeConnection",
            "close",
            "shutdown",
            "setGameMode",
            "closeInventory",
            "closeInventorySafely",
            "clearActiveItem",
            "teleportAsync",
            "teleport",
            "eject",
            "sendSetback",
            "blockMovementsUntilResync",
            "resync",
            "cancel",
            "setCancelled",
            "replace",
            "correctPlayerSlot",
            "resyncCancelledClick",
            "sendCorrections",
            "rejectPlace",
            "rejectDig",
            "rejectEarly",
            "sendServerStates",
            "resyncFromServerView",
            "inject",
            "dispatchCommand",
            "applyValidationBlock",
            "setBlockData",
            "sendBlockChange",
            "disconnectPacket",
            "resyncPackets",
            "sendVehicleProtocolResync",
            "resyncPose");

    private static final List<String> GUARDS = List.of("isObserveOnly()", "shouldModifyPackets()", "failsOpen()");

    /**
     * {@code [guard] reason} starts a section; {@code [] reason} starts a section of exceptions. Each entry is
     * {@code file#method(ParameterTypes) calledName}, followed by {@code xN} when N calls stay outside every guard (1 for
     * an exception, 0 under a guard, by default).
     */
    private static final String ALLOWED = """
            [isExempt()] SetbackTeleportUtil.isExempt() is true in observe-only
            manager/player/SetbackTeleportUtil.java#executeTooHighLatencySetback(String) blockMovementsUntilResync
            manager/player/SetbackTeleportUtil.java#executeViolationSetback() blockMovementsUntilResync
            [shouldBlockMovement()] true in observe-only only before the world loads; the codec forwards the client's packet
            checks/impl/combat/ReachCheck.java#handleInteract(PacketReceiveEvent,ServerboundInteract) setCancelled
            checks/impl/movement/timer/AbstractTimerCheck.java#recordModernMovePlayerPacket(PacketReceiveEvent,boolean) setCancelled
            checks/impl/movement/timer/DumbTimer.java#handleMovePlayer(PacketReceiveEvent,ServerboundMovePlayer) setCancelled
            [shouldCancel(] BadPacketsH.shouldCancel(int) requires shouldModifyPackets()
            checks/impl/badpackets/BadPacketsH.java#onBlockBreak(BlockBreak) cancel
            checks/impl/badpackets/BadPacketsH.java#onBlockPlace(BlockPlace) resync
            checks/impl/badpackets/BadPacketsH.java#onUseItem(PacketReceiveEvent,CultPlayer,ServerboundUseItem) setCancelled
            [handleLegacySneak(] BadPacketsG rejects only under shouldModifyPackets()
            checks/impl/badpackets/BadPacketsG.java#onPlayerCommand(PacketReceiveEvent,CultPlayer,ServerboundPlayerCommand) setCancelled
            [canceled] console commands are withheld in observe-only
            manager/PunishmentManager.java#handleAlert(CultPlayer,Supplier,Check) dispatchCommand
            [REJECT] AbstractTimerCheck rejects only under shouldModifyPackets()
            checks/impl/movement/timer/TimerCheck.java#onBedrockAuthInput(PacketReceiveEvent) setCancelled
            [] reached only from callers that return first in observe-only
            checks/impl/prediction/checks/NoSlow.java#clearFlaggedUse() clearActiveItem
            events/packets/patch/ResyncWorldUtil.java#resyncOnOwner(CultPlayer,int,int,int,int,int,int) sendServerStates
            manager/player/SetbackTeleportUtil.java#blockMovementsUntilResync(boolean,boolean,boolean) sendSetback
            manager/player/SetbackTeleportUtil.java#sendSetback(SetBackData) sendSetback
            manager/player/SetbackTeleportUtil.java#sendSetback(SetBackData,boolean) sendSetback
            manager/player/SetbackTeleportUtil.java#sendSetback(SetBackData,boolean,boolean) sendSetback
            manager/player/SetbackTeleportUtil.java#transferBedrockVehicleSetback(int) sendSetback
            [reachesServer(] CheckManagerListener.reachesServer(PacketReceiveEvent) is true in observe-only
            events/packets/listeners/CheckManagerListener.java#handle(PacketReceiveEvent,CultPlayer,Object) rejectEarly
            events/packets/listeners/CheckManagerListener.java#onContainerClick(PacketReceiveEvent,CultPlayer,InventoryClick) resyncCancelledClick
            events/packets/listeners/CheckManagerListener.java#processPlayerActionReceive(PacketReceiveEvent,CultPlayer,ServerboundPlayerAction) rejectDig
            [] sends the correction its caller decided on; each caller is listed
            events/packets/patch/ResyncWorldUtil.java#sendServerStates(CultPlayer,Collection,int) sendCorrections x2
            utils/latency/BlockActionReconciler.java#rejectEarly(Object) rejectDig
            utils/latency/BlockActionReconciler.java#rejectEarly(Object) rejectPlace
            utils/latency/BlockActionReconciler.java#send(Correction) sendCorrections
            utils/latency/BlockPredictionAckSender.java#sendAck(CultPlayer,int,Collection) sendCorrections
            utils/latency/BlockPredictionAckSender.java#sendCorrections(CultPlayer,int,boolean,Collection,Collection) sendCorrections
            utils/latency/CompensatedInventory.java#correctPlayerSlot(int) sendCorrections
            utils/latency/CompensatedInventory.java#resyncCancelledClick(InventoryClick) sendCorrections
            utils/latency/CompensatedInventory.java#resyncFromServerView() sendCorrections
            [] follows a cancel that already happened
            events/packets/listeners/PacketPlayerDigging.java#onPlayerAction(PacketReceiveEvent,CultPlayer,ServerboundPlayerAction) setCancelled
            [] the codec forwards the client's packet in observe-only
            checks/impl/crash/CrashA.java#onMovePlayer(PacketReceiveEvent,CultPlayer,ServerboundMovePlayer) setCancelled
            checks/impl/crash/CrashB.java#onSetCreativeModeSlot(PacketReceiveEvent,CultPlayer,CreativeSlot) setCancelled
            checks/impl/crash/CrashC.java#onMovePlayer(PacketReceiveEvent,CultPlayer,ServerboundMovePlayer) setCancelled
            checks/impl/crash/CrashD.java#onContainerClick(PacketReceiveEvent,CultPlayer,InventoryClick) setCancelled
            checks/impl/crash/CrashE.java#sanitizeClientInformation(PacketReceiveEvent,ServerboundClientInformation) replace
            checks/impl/crash/CrashF.java#onContainerClick(PacketReceiveEvent,CultPlayer,InventoryClick) setCancelled x2
            checks/impl/crash/CrashG.java#onBlockBreak(BlockBreak) cancel
            checks/impl/crash/CrashG.java#onBlockPlace(BlockPlace) resync
            checks/impl/crash/CrashG.java#onUseItem(PacketReceiveEvent,CultPlayer,ServerboundUseItem) setCancelled
            checks/impl/crash/CrashI.java#onSelectBundleItem(PacketReceiveEvent,CultPlayer,ServerboundSelectBundleItem) setCancelled
            checks/impl/movement/SetbackBlocker.java#blockInactiveMovePlayer(PacketReceiveEvent,ServerboundMovePlayer) setCancelled x2
            checks/impl/movement/SetbackBlocker.java#blockMovePlayer(PacketReceiveEvent,ServerboundMovePlayer,boolean) setCancelled
            checks/impl/movement/SetbackBlocker.java#handleMovePlayer(PacketReceiveEvent,ServerboundMovePlayer,boolean) replace
            checks/impl/movement/SetbackBlocker.java#handleMovePlayer(PacketReceiveEvent,ServerboundMovePlayer,boolean) setCancelled x2
            checks/impl/movement/SetbackBlocker.java#handleMoveVehicle(PacketReceiveEvent) setCancelled x6
            checks/impl/movement/SetbackBlocker.java#onJavaMovePlayerAfterChecks(PacketReceiveEvent) setCancelled
            events/packets/listeners/CheckManagerListener.java#processMovePlayerReceive(PacketReceiveEvent,CultPlayer,ServerboundMovePlayer) replace x2
            events/packets/listeners/CheckManagerListener.java#processMovePlayerReceive(PacketReceiveEvent,CultPlayer,ServerboundMovePlayer) setCancelled x3
            events/packets/listeners/CheckManagerListener.java#processMoveVehicleReceive(PacketReceiveEvent,CultPlayer,ServerboundMoveVehicle) setCancelled x5
            events/packets/listeners/PacketServerTeleport.java#onAcceptTeleportation(PacketReceiveEvent,CultPlayer,ServerboundAcceptTeleportation) setCancelled
            manager/player/PluginChannelManager.java#handle(String,byte[],PacketReceiveEvent) setCancelled
            utils/latency/CompensatedInventory.java#onContainerClick(PacketReceiveEvent,CultPlayer,InventoryClick) setCancelled
            [] README: a non-finite rotation from the server is sent as 0
            events/packets/PacketServerPlayerRotation.java#onPlayerRotation(PacketSendEvent,CultPlayer,ClientboundPlayerRotation) replace
            [] README: the self pose strip; the health spoof breaks out on isObserveOnly() first
            events/packets/listeners/PacketSelfMetadataListener.java#handleSetEntityData(PacketSendEvent,CultPlayer,EntityMetadata) replace x2
            [] README: the Bedrock movement bridge
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#beginTeleportRecovery(ChannelHandlerContext,BedrockPacketWrapper,long,Emission) disconnect
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#commitOutboundBedrockTeleport(Vec3,boolean,Vec3,Emission) disconnect
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#installFloatingPoints(GeyserSession,boolean) disconnect
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#installPacketTap(GeyserConnection) disconnect x2
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#writeTapped(ChannelHandlerContext,Object,BedrockPacketWrapper,ChannelPromise) disconnect
            bedrock/bridge/GeyserFloatingPointsAdapter.java#fail(Throwable) disconnect
            bedrock/bridge/GeyserFloatingPointsAdapter.java#send(JavaHook,PacketSendingEvent) setCancelled x2
            bedrock/bridge/GfpPacketRewriter.java#send(PacketSendingEvent) setCancelled
            bedrock/bridge/GfpSessionHooks.java#disconnect(String) disconnect
            [] README: staff /cult spectate and /cult debug
            command/commands/CultDebug.java#applyValidationBlock(CommandContext,boolean) applyValidationBlock
            command/commands/CultDebug.java#handleValidationBlock(CommandContext) applyValidationBlock
            command/commands/CultDebug.java#handleValidationPacketBlock(CommandContext) applyValidationBlock
            events/packets/listeners/PlayerInfoListener.java#onPlayerInfoUpdate(PacketSendEvent,CultPlayer,ClientboundPlayerInfoUpdate) replace x2
            events/packets/listeners/PlayerInfoListener.java#onPlayerInfoUpdate(PacketSendEvent,CultPlayer,ClientboundPlayerInfoUpdate) setCancelled
            manager/SpectateManager.java#connectAndSpectate(Router,PlatformPlayer,PlatformPlayer,String,String,Long,PreviousState,Runnable) setGameMode
            manager/SpectateManager.java#onDisable(PreviousState,PlatformPlayer) setGameMode
            manager/SpectateManager.java#respectate(Router,PlatformPlayer,String) setGameMode
            manager/SpectateManager.java#restoreTargetGameMode(PreviousState,PlatformPlayer,String) setGameMode
            manager/SpectateManager.java#spectateHere(Router,PlatformPlayer,PlatformPlayer,String,PreviousState,Runnable) setGameMode
            manager/SpectateManager.java#teleportBack(PreviousState,PlatformPlayer,Long) teleportAsync
            manager/SpectateManager.java#teleportToPlayer(PlatformPlayer,PlatformPlayer) teleportAsync
            utils/latency/InteractionPause.java#resync() resyncFromServerView
            utils/latency/InteractionPause.java#resync() sendServerStates
            utils/latency/InteractionPause.java#start(long) resync x2
            [] the model and CultAC's own pings
            bedrock/bridge/GeyserEntityAttributes.java#capture(GeyserSession,CultPlayer,GeyserReplayUpdate) replace
            bedrock/prediction/integration/BedrockReplayAttributeEvent.java#state(BedrockMovementState) replace x2
            bedrock/prediction/integration/BedrockReplayContextEvent.java#state(BedrockMovementState) replace
            bedrock/prediction/state/BedrockActorAttributes.java#replace(Map) replace
            checks/impl/prediction/runner/PacketModHandler.java#bundlePacketWithTrailingTransaction(PacketSendEvent,R) replace
            events/packets/listeners/PacketPingListener.java#onPong(PacketReceiveEvent,CultPlayer,ServerboundPong) setCancelled
            events/packets/worldreader/BasePacketWorldReader.java#handleBlockChange(CultPlayer,PacketSendEvent,BlockUpdate) replace
            events/packets/worldreader/BasePacketWorldReader.java#handleBlockEvent(CultPlayer,PacketSendEvent,ClientboundBlockEvent) replace
            network/PacketDispatcher.java#clear() replace
            utils/data/packetentity/PacketEntity.java#mount(PacketEntity) eject
            utils/latency/CompensatedEntities.java#removeEntity(int) eject x2
            utils/latency/CompensatedVehicleState.java#applyClientVisibleDismount() eject
            utils/latency/CompensatedVehicleState.java#applyVehiclePassengers(int,int[]) eject
            [] session cleanup and shutdown
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#disconnectSession(Object) disconnect
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#releaseSession(Object) disconnect
            bedrock/bridge/GeyserBedrockBridgeRuntime.java#stop() disconnect
            bedrock/logging/BedrockPacketLogger.java#close() shutdown
            cloud/CloudModule.java#shutdown() shutdown
            cloud/transport/SharedTransport.java#stopIfDone() shutdown x2
            manager/InitManager.java#<init>(Initable[]) shutdown
            manager/datastore/SharedDataStoreLifecycle.java#close() shutdown
            manager/datastore/SharedDataStoreLifecycle.java#disablePersistenceAfterLostOwnership() shutdown
            manager/datastore/SharedDataStoreLifecycle.java#disableStorageAfterDuplicate() shutdown
            manager/tick/impl/ClientVersionSetter.java#tick() disconnect
            network/CultNetworkManager.java#disconnect(Channel) disconnect
            network/CultNetworkManager.java#disconnect(User) disconnect
            network/PacketConnections.java#attach(CultConnection) disconnect
            network/PacketConnections.java#disconnectRemainingUsers() disconnect
            network/logging/JavaPacketLogger.java#close() shutdown
            network/protocol/player/User.java#closeConnection() disconnect
            platform/bukkit/CultACBukkitLoaderPlugin.java#disconnect(Channel) disconnect
            platform/bukkit/PaperInjector.java#disconnect(Channel) disconnect
            platform/bukkit/PaperRuntimeEntrypoint.java#disconnectChannel(Channel) disconnect
            platform/velocity/VelocityPlatform.java#disconnect(Channel) disconnect
            platform/velocity/VelocityRuntimeEntrypoint.java#disconnectChannel(Channel) disconnect
            reload/host/GenerationManager.java#attach(Object,Object,boolean) disconnect
            reload/host/GenerationManager.java#beginStop(Path) disconnect
            reload/host/GenerationManager.java#beginStop(Path) shutdown
            reload/host/GenerationManager.java#disconnect(Object) disconnect
            reload/host/GenerationManager.java#handoff(Object,Object) disconnect x2
            reload/host/GenerationManager.java#stop() shutdown
            velocity/CultVelocityPlugin.java#login(PostLoginEvent) disconnect x2
            velocity/CultVelocityPlugin.java#shutdown(ProxyShutdownEvent) shutdown
            [] tasks and futures
            cloud/transport/CloudConnection.java#onNext(List) cancel
            companion/api/ApiModule.java#disable() cancel
            companion/api/ApiModule.java#disable() shutdown
            companion/bridge/BridgeModule.java#disable() cancel
            companion/command/CommandModule.java#disable() cancel
            manager/portal/PortalSyncService.java#stop() cancel
            platform/bukkit/scheduler/bukkit/BukkitTaskHandle.java#cancel() cancel
            platform/bukkit/scheduler/folia/FoliaTaskHandle.java#cancel() cancel
            platform/velocity/VelocityScheduler.java#entity(CultEntity,GrimPlugin,Runnable,Runnable,long,long) cancel x2
            platform/velocity/bridge/api/BridgeApiService.java#stop() cancel
            [] platform implementations; each caller is listed
            network/protocol/player/User.java#closeConnection() close
            platform/api/PlatformServer.java#dispatchCommand(Sender,String,PlatformPlayer,AbstractCheck,double,String) dispatchCommand
            platform/bukkit/BukkitConnectionAdapter.java#disconnect(Component) disconnect x2
            platform/bukkit/BukkitConnectionAdapter.java#disconnect(Component) kick
            platform/bukkit/BukkitPlatformServer.java#dispatchCommand(Sender,String) dispatchCommand
            platform/bukkit/BukkitValidationBlocks.java#apply(PlatformPlayer,int,int,int,String,Runnable) setBlockData
            platform/bukkit/PaperInjector.java#injectNew(Channel) inject
            platform/bukkit/PaperInjector.java#register() inject x2
            platform/bukkit/PaperRuntimeEntrypoint.java#attach(Object,Object) inject
            platform/bukkit/entity/BukkitCultEntity.java#eject() eject
            platform/bukkit/entity/BukkitCultEntity.java#teleportAsync(Location) teleportAsync
            platform/bukkit/manager/BukkitItemResetHandler.java#resetItemUsage(PlatformPlayer) clearActiveItem
            platform/bukkit/player/BukkitPlatformPlayer.java#closeInventory() closeInventory
            platform/bukkit/player/BukkitPlatformPlayer.java#eject() eject
            platform/bukkit/player/BukkitPlatformPlayer.java#kickPlayer(String) kickPlayer
            platform/bukkit/player/BukkitPlatformPlayer.java#setGameMode(GameMode) setGameMode
            platform/bukkit/player/BukkitPlatformPlayer.java#teleportAsync(Location) teleportAsync
            platform/bukkit/sender/BukkitSenderFactory.java#performCommand(CommandSender,String) dispatchCommand
            platform/velocity/VelocityConnectionAdapter.java#disconnect(Component) disconnect
            platform/velocity/VelocityPlayer.java#closeInventory() inject
            platform/velocity/VelocityPlayer.java#kickPlayer(String) disconnect
            platform/velocity/VelocityServer.java#dispatchCommand(Sender,String,PlatformPlayer) dispatchCommand x2
            platform/velocity/VelocityServer.java#dispatchCommand(Sender,String,PlatformPlayer,AbstractCheck,double,String) dispatchCommand
            player/CultPlayer.java#closeInventorySafely() closeInventory
            player/CultPlayer.java#disconnect(Component) closeConnection
            player/CultPlayer.java#disconnect(Component) disconnectPacket
            player/CultPlayer.java#disconnect(Component) kickPlayer
            reload/host/BuildUpdates.java#onNext(List) cancel x2
            [] the companion runs a proxy request; each proxy caller is listed
            companion/api/ApiModule.java#dispatchConsole(JavaPlugin,String) dispatchCommand
            companion/bridge/BridgeInjector.java#install() inject x2
            companion/bridge/ServerActions.java#consoleExec(CompanionSession,ConsoleExec) dispatchCommand
            companion/bridge/ServerActions.java#onMessage(CompanionSession,BridgeMessage) clearActiveItem
            companion/bridge/ServerActions.java#onMessage(CompanionSession,BridgeMessage) closeInventory
            companion/bridge/ServerActions.java#onMessage(CompanionSession,BridgeMessage) setGameMode
            companion/bridge/ServerActions.java#onMessage(CompanionSession,BridgeMessage) teleportAsync x2
            companion/bridge/ServerActions.java#validationBlock(Player,ValidationBlock) sendBlockChange
            companion/bridge/ServerActions.java#validationBlock(Player,ValidationBlock) setBlockData
            [] closes an HTTP connection
            command/commands/CultLog.java#sendLog(Sender,String,String,String,Consumer,String) disconnect
            """;

    @Test
    void everyGameplayCallIsGuardedOrListed() throws IOException {
        Map<String, Allowed> allowed = allowed();
        List<String> problems = new ArrayList<>();
        scan().forEach((key, calls) -> {
            Allowed entry = allowed.remove(key);
            List<String> guards = new ArrayList<>(GUARDS);
            if (entry != null && entry.guard() != null) guards.add(entry.guard());
            long open = calls.stream()
                    .filter(conditions ->
                            conditions.stream().noneMatch(c -> guards.stream().anyMatch(c::contains)))
                    .count();
            long expected = entry == null ? 0 : entry.open();
            if (open != expected) problems.add(key + " x" + open + " (allowed " + expected + ")");
        });
        allowed.keySet().forEach(key -> problems.add(key + ": listed but not found"));
        assertTrue(
                problems.isEmpty(),
                "Observe-only changes no gameplay. A new kick, cancel, teleport, resync or clear must return first or"
                        + " be skipped on isObserveOnly() (shouldModifyPackets() in a check), or be listed under"
                        + " observe-only in README.md and added to ALLOWED as an exception:\n  "
                        + String.join("\n  ", problems));
    }

    private record Allowed(String guard, long open) {}

    private static Map<String, Allowed> allowed() {
        Map<String, Allowed> allowed = new TreeMap<>();
        String guard = null;
        for (String line : ALLOWED.lines().map(String::strip).toList()) {
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                guard = line.substring(1, line.indexOf(']'));
                if (guard.isEmpty()) guard = null;
                continue;
            }
            int count = line.lastIndexOf(" x");
            boolean counted = count > 0 && line.substring(count + 2).chars().allMatch(Character::isDigit);
            String key = counted ? line.substring(0, count) : line;
            long open = counted ? Long.parseLong(line.substring(count + 2)) : guard == null ? 1 : 0;
            if (allowed.put(key, new Allowed(guard, open)) != null) throw new IllegalStateException(key + " twice");
        }
        return allowed;
    }

    /** Each gameplay call, keyed by file, method and called name, as the conditions that enclose it. */
    private static Map<String, List<List<String>>> scan() throws IOException {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.exists(repository.resolve("settings.gradle.kts"))) repository = repository.getParent();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Map<String, List<List<String>>> sites = new TreeMap<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            for (String module : MODULES) {
                Path root = repository.resolve(module).resolve("src/main/java");
                if (!Files.isDirectory(root)) continue;
                List<Path> sources;
                try (Stream<Path> walk = Files.walk(root)) {
                    sources = walk.filter(path -> path.toString().endsWith(".java"))
                            .toList();
                }
                JavacTask task = (JavacTask) javac.getTask(
                        null, files, null, List.of("-proc:none"), null, files.getJavaFileObjectsFromPaths(sources));
                for (CompilationUnitTree unit : task.parse()) {
                    String file = root.relativize(Path.of(unit.getSourceFile().toUri()))
                            .toString()
                            .replace('\\', '/')
                            .replaceFirst("^ac/cult/(cultac/)?", "");
                    new Finder(file, sites).scan(unit, null);
                }
            }
        }
        return sites;
    }

    private static final class Finder extends TreePathScanner<Void, Void> {
        private final String file;
        private final Map<String, List<List<String>>> sites;

        Finder(String file, Map<String, List<List<String>>> sites) {
            this.file = file;
            this.sites = sites;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            String name = name(call.getMethodSelect());
            if (name != null && GAMEPLAY.contains(name) && affectsGameplay(name, call)) record(name);
            return super.visitMethodInvocation(call, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
            String name = reference.getName().toString();
            if (GAMEPLAY.contains(name) && !name.equals("cancel") && !name.equals("close")) record(name);
            return super.visitMemberReference(reference, unused);
        }

        private void record(String name) {
            List<String> conditions = new ArrayList<>();
            TreePath at = getCurrentPath();
            while (!(at.getLeaf() instanceof MethodTree || at.getParentPath().getLeaf() instanceof ClassTree)) {
                conditions.addAll(enclosing(at.getLeaf(), at.getParentPath().getLeaf()));
                at = at.getParentPath();
            }
            sites.computeIfAbsent(file + "#" + ownerName(at.getLeaf()) + " " + name, k -> new ArrayList<>())
                    .add(conditions);
        }

        /** The conditions {@code parent} checks before its child {@code leaf} runs. */
        private static List<String> enclosing(Tree leaf, Tree parent) {
            if (parent instanceof IfTree branch && leaf != branch.getCondition()) {
                return List.of(branch.getCondition().toString());
            }
            if (parent instanceof ConditionalExpressionTree choice && leaf != choice.getCondition()) {
                return List.of(choice.getCondition().toString());
            }
            if (parent instanceof BinaryTree binary
                    && (binary.getKind() == Tree.Kind.CONDITIONAL_AND || binary.getKind() == Tree.Kind.CONDITIONAL_OR)
                    && leaf == binary.getRightOperand()) {
                return List.of(binary.getLeftOperand().toString());
            }
            List<? extends StatementTree> statements = parent instanceof BlockTree block
                    ? block.getStatements()
                    : parent instanceof CaseTree branch ? branch.getStatements() : null;
            if (statements == null) return List.of();
            List<String> exits = new ArrayList<>();
            for (StatementTree statement : statements) {
                if (statement == leaf) break;
                if (statement instanceof IfTree branch && exits(branch.getThenStatement())) {
                    exits.add(branch.getCondition().toString());
                }
            }
            return exits;
        }

        private static boolean exits(StatementTree statement) {
            if (statement instanceof BlockTree block) {
                return !block.getStatements().isEmpty()
                        && exits(block.getStatements().getLast());
            }
            return statement instanceof ReturnTree
                    || statement instanceof ContinueTree
                    || statement instanceof BreakTree
                    || statement instanceof ThrowTree;
        }

        /**
         * Skips same-named calls that change nothing: an uncancel, a map's replace, a teleport query, a future's
         * cancel, a close of anything but a channel.
         */
        private static boolean affectsGameplay(String name, MethodInvocationTree call) {
            return switch (name) {
                case "setCancelled" ->
                    !(call.getArguments().getFirst() instanceof LiteralTree literal
                            && Boolean.FALSE.equals(literal.getValue()));
                case "replace" -> call.getArguments().size() == 1;
                case "teleport" -> !call.getArguments().isEmpty();
                case "cancel" -> call.getArguments().isEmpty();
                case "close" ->
                    call.getMethodSelect() instanceof MemberSelectTree member
                            && member.getExpression().toString().matches("(?i)(.*\\.)?\\w*channel(\\(\\))?");
                default -> true;
            };
        }

        private static String name(ExpressionTree select) {
            if (select instanceof MemberSelectTree member)
                return member.getIdentifier().toString();
            if (select instanceof IdentifierTree identifier)
                return identifier.getName().toString();
            return null;
        }

        private static String ownerName(Tree owner) {
            if (owner instanceof MethodTree method) {
                return method.getName()
                        + method.getParameters().stream()
                                .map(parameter -> simple(parameter.getType().toString()))
                                .collect(Collectors.joining(",", "(", ")"));
            }
            if (owner instanceof VariableTree field) return field.getName().toString();
            return ((BlockTree) owner).isStatic() ? "<clinit>" : "<init>";
        }

        private static String simple(String type) {
            return type.replaceAll("<.*>", "").replaceAll("^.*\\.", "");
        }
    }
}
