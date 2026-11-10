package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.events.packets.listeners.PlayerInfoListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.math.Vec3;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/** ClientPacketListener#applyPlayerInfoUpdate -> LocalPlayer#onGameModeChanged, not GameEvent or abilities. */
class JavaPlayerInfoSpectatorResetTest {
    private static final Vec3 AIR = new Vec3(.5, 68, .5);
    private static final Vec3 MOTION = new Vec3(.125, .25, .0625);
    private static final double FALL = -.0784000015258789;
    private static final Set<ClientboundPlayerInfoUpdate.Action> UPDATE = EnumSet.of(ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE);
    private static final Set<ClientboundPlayerInfoUpdate.Action> ADD = EnumSet.of(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER);
    private static final Set<ClientboundPlayerInfoUpdate.Action> BOTH = EnumSet.of(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER, ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE);

    private static void prepare(JavaTickFixture f, ClientVersion version) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.packetStateData.javaLocalPlayerPresent = true;
        f.place(AIR, MOTION);
        f.pongThrough(info(f, f.player.playerUUID, GameMode.SURVIVAL, ADD));
    }

    private static int info(JavaTickFixture f, UUID id, GameMode mode, Set<ClientboundPlayerInfoUpdate.Action> actions) {
        return info(f,id,mode,actions,0);
    }

    private static int info(JavaTickFixture f, UUID id, GameMode mode, Set<ClientboundPlayerInfoUpdate.Action> actions,int proof) {
        var packet = new ClientboundPlayerInfoUpdate(actions, List.of(new ClientboundPlayerInfoUpdate.Entry(id, mode, Map.of())));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_INFO_UPDATE, packet, false);
        if(proof>0) event.sharedPing(proof);
        new PlayerInfoListener().onPlayerInfoUpdate(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static void spectator(JavaTickFixture f) {
        f.pongThrough(info(f, f.player.playerUUID, GameMode.SPECTATOR, UPDATE));
    }

    private static void remove(JavaTickFixture f) {
        var packet = new ClientboundPlayerInfoRemove(List.of(f.player.playerUUID));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_INFO_REMOVE, packet, false);
        new PlayerInfoListener().onPlayerInfoRemove(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void gameEvent(JavaTickFixture f) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, 3);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        new PacketChangeGameState(f.player).onGameEvent(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void abilities(JavaTickFixture f) {
        var packet = new ClientboundPlayerAbilities(true, true, true, false, .05F, .1F);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        new PacketPlayerAbilities(f.player).onPlayerAbilities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void exact(PredictionResult result) {
        assertEquals(0, result.getOffset(), 1e-10, JavaTickFixture.describe(result));
        assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_2","V_1_21_5","V_26_3"})
    void capturedFloorCarryThenAbilitiesInfoGameEventAbilitiesProducesZeroStatusMove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f, version);
            Vec3 at = new Vec3(350.5, 88, -80.5);
            for (int x=349; x<=352; x++) for (int z=-82; z<=-79; z++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(x>>4, z>>4);
                f.player.compensatedWorld.updateBlock(new BlockPos(x,87,z), BlockIds.STONE.defaultState());
            }
            for (int i=0;i<3;i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(at, new Vec3(0,FALL,0));
            exact(f.tick(at,true)); // Native tick20: attempted Y=FALL, landed; delta still FALL.
            assertTrue(f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                    .anyMatch(v -> v.y == FALL));
            abilities(f);
            info(f, f.player.playerUUID, GameMode.SPECTATOR, UPDATE);
            gameEvent(f);
            abilities(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.statusTick(false, false); // Native tick21: attempted=(0,0,0), ground=false.
            var result=f.player.checkManager.getSimulationProcessor().getLastPrediction();
            exact(result);
            assertEquals(0, result.getInitialStartingVel().y, 0);
            assertEquals(GameMode.SPECTATOR, f.player.gamemode);
            assertTrue(f.player.isFlying);
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void receiptIsRequiredAndTheTabModeIsIndependentOfActualGameMode(ClientVersion version) throws Exception {
        try (var f=new JavaTickFixture()) {
            prepare(f,version);
            int proof=info(f,f.player.playerUUID,GameMode.SPECTATOR,UPDATE);
            assertEquals(Set.of(MOTION),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            f.pongThrough(proof);
            assertEquals(GameMode.SURVIVAL,f.player.gamemode);
            assertFalse(f.player.isFlying);
            var result=f.tick(AIR.add(MOTION.with(Direction.Axis.Y, 0)),false);
            exact(result);
            assertFalse(result.isExempt());
            assertEquals(MOTION.with(Direction.Axis.Y, 0),new Vec3(result.getInitialStartingVel().x,result.getInitialStartingVel().y,result.getInitialStartingVel().z));
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void remoteUnknownUnchangedAndAbsentLocalPlayerDoNotReset(ClientVersion version) throws Exception {
        for(int which=0;which<4;which++) try(var f=new JavaTickFixture()) {
            prepare(f,version);
            if(which==1) remove(f);
            if(which==2) {spectator(f);f.place(AIR,MOTION);}
            if(which==3) f.player.packetStateData.javaLocalPlayerPresent=false;
            UUID id=which==0?UUID.randomUUID():f.player.playerUUID;
            f.pongThrough(info(f,id,GameMode.SPECTATOR,which==0?BOTH:UPDATE));
            assertEquals(Set.of(MOTION),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            exact(f.tick(AIR.add(MOTION),false));
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void addDefaultsRemoveReaddAndSeveralModeWritesUseThePreviousTabEntry(ClientVersion version) throws Exception {
        try(var f=new JavaTickFixture()) {
            prepare(f,version);
            spectator(f);
            Object old=f.player.compensatedEntities.clientPlayerModes.get(f.player.playerUUID).identity();
            f.place(AIR,MOTION);
            f.pongThrough(info(f,f.player.playerUUID,GameMode.SPECTATOR,BOTH)); // existing spectator unchanged
            assertEquals(Set.of(MOTION),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            remove(f);
            spectator(f); // unknown entry ignored
            assertEquals(Set.of(MOTION),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            f.pongThrough(info(f,f.player.playerUUID,GameMode.SPECTATOR,BOTH)); // new default SURVIVAL -> SPECTATOR
            assertNotSame(old,f.player.compensatedEntities.clientPlayerModes.get(f.player.playerUUID).identity());
            assertEquals(Set.of(MOTION.with(Direction.Axis.Y, 0)),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            f.place(AIR,MOTION);
            info(f,f.player.playerUUID,GameMode.CREATIVE,UPDATE);
            info(f,f.player.playerUUID,GameMode.SPECTATOR,UPDATE);
            f.pongThrough(info(f,f.player.playerUUID,GameMode.SPECTATOR,UPDATE));
            exact(f.tick(AIR.add(MOTION.with(Direction.Axis.Y, 0)),false));
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void gameEventAndAbilitiesAloneKeepNonzeroVerticalMotion(ClientVersion version) throws Exception {
        try(var f=new JavaTickFixture()) {
            prepare(f,version);
            abilities(f);gameEvent(f);abilities(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(Set.of(MOTION),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            exact(f.tick(AIR.add(MOTION),false));
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void packetVelocityBeforeResetKeepsItsHorizontalAntiKBProvenanceAndLaterMotionReplacesIt(ClientVersion version) throws Exception {
        for(boolean later:new boolean[]{false,true}) try(var f=new JavaTickFixture()) {
            prepare(f,version);
            f.velocity(MOTION);
            spectator(f);
            var earlier=f.player.checkManager.getKnockbackHandler().secondBread;
            assertEquals(MOTION.with(Direction.Axis.Y, 0),earlier.getVel());
            Vec3 expected=MOTION.with(Direction.Axis.Y, 0);
            if(later) {expected=new Vec3(.25,.375,.125);f.pongThrough(f.velocity(expected));}
            var applied=f.player.checkManager.getKnockbackHandler().secondBread;
            var result=f.tick(AIR.add(expected),false);
            exact(result);
            assertTrue(result.getInitialStartingVel().hasPacketModifier(applied));
            assertEquals(0,applied.getOffset(),1e-10);
        }
    }



    private static void velocity(JavaTickFixture f,int proof) {
        var packet=new ClientboundEntityMotion(f.player.entityID,new Vec3d(MOTION.x,MOTION.y,MOTION.z));
        var event=new PacketSendEvent<>(f.fixture.user,ConnectionPhase.PLAY,ClientboundPackets.ENTITY_MOTION,packet,false);
        event.sharedPing(proof);
        f.player.checkManager.getKnockbackHandler().handleEntityVelocity(event,packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void unchangedSpectatorAfterANewVelocityMustPreserveItsNonzeroY(ClientVersion version) throws Exception {
        try(var f=new JavaTickFixture()) {
            prepare(f,version);spectator(f);
            int proof=f.player.sendTrailingTransaction();
            velocity(f,proof);
            info(f,f.player.playerUUID,GameMode.SPECTATOR,UPDATE,proof);
            f.pongThrough(proof);
            var applied=f.player.checkManager.getKnockbackHandler().secondBread;
            assertEquals(MOTION,applied.getVel());
            exact(f.tick(AIR.add(MOTION),false));
            assertEquals(0,applied.getOffset(),1e-10);
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void explosionBeforeResetLosesOnlyYAndLaterExplosionAddsNewY(ClientVersion version) throws Exception {
        try(var f=new JavaTickFixture()) {
            prepare(f,version);
            f.explode(new Vec3(.125,.375,.0625));
            spectator(f);
            f.pongThrough(f.explode(new Vec3(.0625,.125,.125)));
            Vec3 expected=new Vec3(.3125,.125,.25);
            var applied=f.player.checkManager.getExplosionHandler().secondBread;
            assertEquals(new Vec3(.1875,.125,.1875),applied.getVel());
            exact(f.tick(AIR.add(expected),false));
            assertEquals(0,applied.getOffset(),1e-10);
        }
    }

    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void teleportAndTabResetKeepTheirActualPacketOrder(ClientVersion version) throws Exception {
        for(boolean resetLast:new boolean[]{false,true}) try(var f=new JavaTickFixture()) {
            prepare(f,version);
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(ac.cult.cultac.platform.api.world.PlatformWorld.class));
            Vec3 to=new Vec3(2.5,72,2.5),delta=new Vec3(.0625,.375,.125);
            if(!resetLast) spectator(f);
            f.playerPosition(to,delta,Set.of(Relative.DELTA_X,Relative.DELTA_Y,Relative.DELTA_Z));
            if(resetLast) spectator(f);
            Vec3 expected=resetLast?MOTION.add(delta).with(Direction.Axis.Y,0):MOTION.with(Direction.Axis.Y,0).add(delta);
            assertEquals(Set.of(expected),f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            exact(f.tick(to.add(expected),false));
        }
    }


    @ParameterizedTest @EnumSource(value=ClientVersion.class, names={"V_1_21_5","V_26_3"})
    void delayedEarlierExplosionCallbacksResetTheirOwnContributionBeforeAddingLaterSecondBread(ClientVersion version) throws Exception {
        try(var f=new JavaTickFixture()) {
            prepare(f,version);
            var handler=f.player.checkManager.getExplosionHandler();
            Vec3 original=new Vec3(.125,.5,.0625),later=new Vec3(.0625,.375,.125);
            var earlier=new ac.cult.cultac.utils.data.TransactionVel(original,1,false,false,-1,100);
            var newer=new ac.cult.cultac.utils.data.TransactionVel(later,1,false,false,-1,102);
            handler.firstBread=earlier;
            handler.secondBread=newer;
            handler.resetVerticalBefore(101);
            assertEquals(original.with(Direction.Axis.Y,0),earlier.getVel());
            assertEquals(later,newer.getVel());
            for(String method:List.of("makeVelocityPossible","confirmVelocity")) {
                var callback=ac.cult.cultac.checks.impl.prediction.runner.PacketModHandler.class.getDeclaredMethod(
                        method,ac.cult.cultac.utils.data.TransactionVel.class,Vec3.class);
                callback.setAccessible(true);
                callback.invoke(handler,earlier,original);
                assertEquals(original.with(Direction.Axis.Y,0).add(later),earlier.getVel(),method);
                assertEquals(later,newer.getVel(),"the later independent packet remains unchanged");
            }
            // A still-later packet adds normally; a subsequent source reset then overwrites BOTH prior Ys.
            var confirm=ac.cult.cultac.checks.impl.prediction.runner.PacketModHandler.class.getDeclaredMethod(
                    "confirmVelocity",ac.cult.cultac.utils.data.TransactionVel.class,Vec3.class);
            confirm.setAccessible(true);
            Vec3 last=new Vec3(.03125,.125,.03125);
            var newest=new ac.cult.cultac.utils.data.TransactionVel(last,1,false,false,-1,103);
            confirm.invoke(handler,newest,last);
            assertEquals(.5,newest.getVel().y,0);
            handler.resetVerticalBefore(104);
            assertEquals(0,newest.getVel().y,0);
        }
    }

}
