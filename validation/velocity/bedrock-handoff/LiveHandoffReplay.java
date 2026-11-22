package ac.cultac.bedrockreplay.regression;

import ac.cultac.bedrockreplay.protocol.*;
import com.google.gson.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static ac.cultac.bedrockreplay.regression.JsonFields.*;

/** Uses recorded gameplay and real live responses; never runs a client physics approximation. */
public final class LiveHandoffReplay {
    private LiveHandoffReplay() { }
    public static void main(String[] args) throws Exception {
        if ((args.length != 7 && args.length != 8) || (args.length == 8 && !args[7].equals("dormant")))
            throw new IllegalArgumentException("scenario plugin output port fixture-a fixture-b rounds [dormant]");
        run(MovementScenario.load(Path.of(args[0])), Path.of(args[1]), Path.of(args[2]),
                Integer.parseInt(args[3]), new Path[]{Path.of(args[4]), Path.of(args[5])}, Integer.parseInt(args[6]),
                args.length == 8);
    }

    public static void run(MovementScenario scenario, Path plugin, Path output, int port, Path[] fixtures, int rounds) throws Exception {
        run(scenario, plugin, output, port, fixtures, rounds, false);
    }

    // The movement assertions below are the existing LiveMovementReplay assertions. Each round
    // replays the entire immutable tape; only live server switching and observer segmentation are added.
    // With dormant, every round after the first replays while CultAC does not check the client.
    public static void run(MovementScenario scenario, Path plugin, Path output, int port, Path[] fixtures, int rounds,
            boolean dormant) throws Exception {
        String host = "127.0.0.1", usernamePrefix = "";
        boolean mutated = false;
        long seed = 42;
        String run=UUID.randomUUID().toString().replace("-","");String bedrockName="br"+run.substring(0,12);
        LiveControl control=new LiveControl(plugin,output.resolve("round-0"),run);long start=System.nanoTime();List<JsonObject> semantic=new ArrayList<>();
        Set<Long> controlTicks=new TreeSet<>();
        Throwable failure=null;boolean prepared=false, primaryThrown=false;
        try {
            Files.createDirectories(output);
            List<MutationPlanner.Mutation> plan=mutated?MutationPlanner.plan(scenario,seed):List.of();
            write(output.resolve("mutation-plan.json"),MutationPlanner.evidence(scenario,seed,plan));
            // Floodgate uses '.', while a Geyser offline-only proxy keeps the unprefixed name.
            new LiveControl(fixtures[0], output.resolve("fixture-0"), run).prepare(scenario, bedrockName);
            control.prepare(scenario,usernamePrefix+bedrockName);prepared=true;

            try(var session=new BedrockProtocolClient().connect(new InetSocketAddress(host,port),scenario.protocol(),Duration.ofSeconds(30),bedrockName)) {
                session.enableTickBoundaryLatency();
                var join=session.joinResult();
                if(!join.loginSuccess() || !join.playerSpawn() || !join.localPlayerInitialized())throw new IllegalStateException("Incomplete real login");
                if(scenario.protocol()<944 && !join.authoritativeMovementMode().contains("SERVER") || join.rewindHistorySize()<40)throw new IllegalStateException("Server-authoritative movement required: "+join.authoritativeMovementMode());
                write(output.resolve("connected.json"),Map.of("username",bedrockName,"runtimeEntityId",join.runtimeEntityId()));
                waitForDriver(session,output.resolve("start.json"),180);
                long nextTick = Math.max(2, join.startTick()+2);
                for (int round=0; round<rounds; round++) {
                    Path roundOutput = output.resolve("round-"+round);
                    Files.createDirectories(roundOutput);
                    mutated = !dormant && round == rounds-1 && rounds>2;
                    boolean unchecked = dormant && round>0;
                    semantic.clear(); controlTicks.clear();
                    plan = mutated ? MutationPlanner.plan(scenario, seed) : List.of();
                    write(roundOutput.resolve("mutation-plan.json"), MutationPlanner.evidence(scenario,seed,plan));
                    int moveStart = 0;
                    if (round>0) {
                        String nextRun = run.substring(0, 28) + String.format("%04d", round);
                        if (prepared) suspend(plugin, round==1?run:run.substring(0,28)+String.format("%04d",round-1));
                        prepared=false;
                        new LiveControl(fixtures[round%2], output.resolve("fixture-"+round), nextRun).prepare(scenario,bedrockName);
                        moveStart = session.serverMovePositions().size();
                        long dimensions = session.packetEvents().stream().filter(e -> e.startsWith("recv:CHANGE_DIMENSION:")).count();
                        write(output.resolve("switch-request-"+round+".json"),Map.of("round",round,"username",bedrockName,
                                "target",round%2==0?"lobby":"factions"));
                        long deadline = System.nanoTime()+180_000_000_000L;
                        while (!Files.isRegularFile(output.resolve("switch-complete-"+round+".json"))) {
                            session.requireHealthyTransport();session.processServerTickBoundary();
                            if (System.nanoTime()>deadline) throw new IllegalStateException("Host switch did not complete");
                            Thread.sleep(20);
                        }
                        long dimensionDeadline = System.nanoTime()+10_000_000_000L;
                        while (session.packetEvents().stream().filter(e -> e.startsWith("recv:CHANGE_DIMENSION:")).count()<=dimensions) {
                            session.requireHealthyTransport();session.processServerTickBoundary();
                            if (System.nanoTime()>dimensionDeadline) throw new IllegalStateException("No real Geyser dimension switch");
                            Thread.sleep(20);
                        }
                        if (session.packetEvents().stream().filter(e -> e.startsWith("recv:START_GAME:")).count()!=1)
                            throw new IllegalStateException("StartGame was resent; physical Bedrock connection did not survive");
                        if (unchecked) {
                            nextTick = replayUnchecked(session,scenario,nextTick,join.runtimeEntityId(),moveStart,roundOutput,round);
                            continue;
                        }
                        control = new LiveControl(plugin,roundOutput,nextRun);
                        control.prepare(scenario,bedrockName);
                        prepared=true;
                    }
                    String observedRun = round==0?run:run.substring(0,28)+String.format("%04d",round);
                    PredictionReader reader = new PredictionReader(roundOutput.resolve("predictions.ndjson"),observedRun);
                    long shift = Math.subtractExact(nextTick,scenario.firstTick());
                    long firstMeasuredTick = scenario.firstTick()+shift+scenario.warmupFrames();
                PlayerAuthInputPacket first=(PlayerAuthInputPacket)MovementPackets.decode(scenario.frames().getFirst(),shift,join.runtimeEntityId());
                // StartGame is Geyser's loading-screen position, not the Java player's real spawn.
                // Wait for the actual server teleport to the restored case before acknowledging it.
                long spawnDeadline=System.nanoTime()+10_000_000_000L;boolean spawnObserved=false;
                while(System.nanoTime()<spawnDeadline) {
                    session.requireHealthyTransport();session.processServerTickBoundary();
                    spawnObserved=session.serverMovePositions().subList(moveStart, session.serverMovePositions().size()).stream().anyMatch(m -> m.mode().equals("TELEPORT")
                        && Math.sqrt(sq(m.x()-first.getPosition().getX())+sq(m.y()-first.getPosition().getY())+sq(m.z()-first.getPosition().getZ()))<=.001);
                    if(spawnObserved && Files.exists(roundOutput.resolve("status.json")))break;
                    Thread.sleep(50);
                }
                if(!spawnObserved)throw new IllegalStateException("Real spawn teleport never matched the captured initial position");
                // A teleport's actor tick must run against its actual compensated chunks.
                // A loading-screen echo would leave HasTeleported pending into recorded gameplay.
                TerrainReadiness spawnReadiness=new TerrainReadiness();
                long chunkDeadline=System.nanoTime()+10_000_000_000L;boolean spawnChunksReady=false;
                while(System.nanoTime()<chunkDeadline) {
                    session.requireHealthyTransport();session.processServerTickBoundary();
                    JsonObject status=control.status();spawnChunksReady=spawnReadiness.readyForTeleportAcknowledgement(status);
                    if(spawnChunksReady){write(roundOutput.resolve("spawn-terrain-barrier.json"),Map.of("transaction",spawnReadiness.transaction(),"status",status));break;}
                    Thread.sleep(50);
                }
                if(!spawnChunksReady)throw new IllegalStateException("Spawn teleport chunks were not acknowledged before its actor tick");
                // A fresh login's real teleport must be acknowledged before genuine captured warmup.
                session.processServerTickBoundary();
                PlayerAuthInputPacket loginAck=teleportAcknowledgement(first,first.getTick()-1,first.getPosition());
                session.sendCapturedAuthInput(loginAck);Thread.sleep(50);
                reader.await(loginAck.getTick()).verify(false);
                // Require the completed teleport as well as the terrain boundary before gameplay.
                long terrainDeadline=System.nanoTime()+10_000_000_000L;boolean terrainReady=false;
                TerrainReadiness readiness=new TerrainReadiness();
                while(System.nanoTime()<terrainDeadline) {
                    session.requireHealthyTransport();session.processServerTickBoundary();
                    JsonObject status=control.status();
                    terrainReady=readiness.ready(status);
                    if(terrainReady){write(roundOutput.resolve("terrain-barrier.json"),Map.of("transaction",readiness.transaction(),"status",status));break;}
                    Thread.sleep(50);
                }
                if(!terrainReady)throw new IllegalStateException("Restored static terrain was not acknowledged before replay");
                Map<Integer,MutationPlanner.Mutation> mutations=new HashMap<>();for(var m:plan)mutations.put(m.frame(),m);
                int frame=-1,correctionCursor=0,moveCursor=0,effectCursor=0,motionCursor=0;
                long lastSend=0;List<BedrockPacket> pendingPackets=new ArrayList<>();
                for(JsonObject row:scenario.packets()) {
                    BedrockPacket packet=MovementPackets.decode(row,shift,join.runtimeEntityId());
                    if(!(packet instanceof PlayerAuthInputPacket auth)){pendingPackets.add(packet);continue;}
                    frame++;boolean measured=frame>=scenario.warmupFrames();boolean injected=mutations.containsKey(frame);
                    long delay=50_000_000L-(System.nanoTime()-lastSend);if(delay>0)TimeUnit.NANOSECONDS.sleep(delay);
                    session.requireHealthyTransport();session.processServerTickBoundary();
                    for(BedrockPacket side:pendingPackets)session.sendCapturedPacket(side);
                    pendingPackets.clear();
                    if(injected)auth=mutations.get(frame).apply(auth);
                    correctionCursor=session.corrections().size();moveCursor=session.serverMovePositions().size();
                    effectCursor=session.movementEffects().size();motionCursor=session.entityMotionEvents().size();
                    session.sendCapturedAuthInput(auth);lastSend=System.nanoTime();
                    long tick=auth.getTick();
                    if(!measured)reader.await(tick).verify(false);
                    if(measured) {
                        TickResult result=reader.await(tick);result.verify(injected);semantic.add(result.semantic(frame));
                        boolean acknowledgedTeleport=false;
                        if(injected) {
                            long deadline=System.nanoTime()+2_000_000_000L;
                            boolean phase=result.flags().contains("Phase");
                            while(session.corrections().size()<=correctionCursor
                                    && (!phase || transportTeleports(session.serverMovePositions(),moveCursor).isEmpty())
                                    && System.nanoTime()<deadline)Thread.sleep(5);
                            PlayerAuthInputPacket original=(PlayerAuthInputPacket)MovementPackets.decode(row,shift,join.runtimeEntityId());
                            var teleports=transportTeleports(session.serverMovePositions(),moveCursor);
                            if(phase && !teleports.isEmpty()) {
                                if(teleports.size()!=1 || session.corrections().size()!=correctionCursor)
                                    throw new IllegalStateException("Expected one transport setback for Phase tick "+tick);
                                var teleport=teleports.getFirst();requireCompatibleTeleport(teleport,original);
                                // Teleport acknowledgements are protocol control ticks, not recorded gameplay.
                                // No source frame is dropped or exempted; subsequent tick numbers shift by one.
                                Thread.sleep(50);session.processServerTickBoundary();
                                PlayerAuthInputPacket ack=teleportAcknowledgement(original,tick+1,
                                    org.cloudburstmc.math.vector.Vector3f.from(teleport.x(),teleport.y(),teleport.z()));
                                session.sendCapturedAuthInput(ack);lastSend=System.nanoTime();shift++;
                                control.awaitStatus(st -> integer(st,"processedTick")>=tick+1 && integer(st,"pendingTeleports")==0
                                        && !required(st,"pendingSetback").getAsBoolean() && !required(st,"blocking").getAsBoolean(),Duration.ofSeconds(2));
                                reader.await(tick+1).verify(false);controlTicks.add(tick+1);
                                append(roundOutput.resolve("correction-acks.ndjson"),Map.of("frame",frame,"runtimeTick",tick,
                                        "ackTick",tick+1,"acknowledged",true,"kind","transport-teleport","teleport",teleport));
                                acknowledgedTeleport=true;
                            } else {
                                var corrections=session.corrections();
                                if(corrections.size()!=correctionCursor+1)throw new IllegalStateException("Expected exactly one live correction for injected tick "+tick);
                                var correction=corrections.getLast();requireCompatibleCorrection(correction,original);
                                Thread.sleep(50);session.processServerTickBoundary();
                                control.awaitStatus(st -> integer(st,"correctionTick")==tick && required(st,"correctionAcknowledged").getAsBoolean(),Duration.ofSeconds(2));
                                append(roundOutput.resolve("correction-acks.ndjson"),Map.of("frame",frame,"runtimeTick",tick,"acknowledged",true,"kind","prediction-correction","correction",correction));
                            }
                        } else {
                            // Observe through the following tick boundary before deciding there was no correction.
                            Thread.sleep(Math.max(0,50-(System.nanoTime()-lastSend)/1_000_000));session.processServerTickBoundary();
                            if(session.corrections().size()!=correctionCursor)throw new IllegalStateException("Unexpected correction on untouched frame "+frame);
                        }
                        if(!acknowledgedTeleport && !transportTeleports(session.serverMovePositions(),moveCursor).isEmpty())
                            throw new IllegalStateException("Live transport teleport changes original trajectory at frame "+frame+"; cannot replay clean recovery");
                        if(session.movementEffects().size()!=effectCursor || session.entityMotionEvents().size()!=motionCursor) {
                            var effects=session.movementEffects();var motions=session.entityMotionEvents();
                            var newEffects=effects.subList(effectCursor,effects.size());var newMotions=motions.subList(motionCursor,motions.size());
                            write(roundOutput.resolve("external-movement.json"),Map.of("frame",frame,"effects",newEffects,"motions",newMotions));
                            throw new IllegalStateException("External effect/velocity in movement-only test at frame "+frame+": effects="+newEffects+" motions="+newMotions);
                        }
                    }
                    if(frame==scenario.warmupFrames()-1) {
                        // Warmup cannot be stretched or fabricated to conceal an initialization problem.
                        control.awaitStatus(s -> integer(s,"processedTick")>=tick && integer(s,"predictionTick")==tick && !required(s,"exempt").getAsBoolean() && integer(s,"pendingTeleports")==0 && !required(s,"flying").getAsBoolean() && !required(s,"blocking").getAsBoolean(),Duration.ofSeconds(2));
                    }
                }
                session.processServerTickBoundary();
                for(BedrockPacket side:pendingPackets)session.sendCapturedPacket(side);
                long lastTick=scenario.firstTick()+shift+scenario.frames().size()-1;
                control.awaitStatus(s -> integer(s,"processedTick")>=lastTick && !required(s,"pendingCorrection").getAsBoolean() && !required(s,"pendingSetback").getAsBoolean(),Duration.ofSeconds(2));
                long measuredFirst=firstMeasuredTick,last=lastTick;
                Map<Long,TickResult> all=reader.results();
                long count=all.keySet().stream().filter(t -> t>=measuredFirst && t<=last).count();
                if(count!=scenario.frames().size()-scenario.warmupFrames()+controlTicks.size())throw new IllegalStateException("Incomplete measured predictions");
                if(session.packetEvents().stream().anyMatch(e -> e.startsWith("decode-error:")))throw new IllegalStateException("Transport decode failure");
                session.awaitSettledInventory(Duration.ofSeconds(2));
                write(roundOutput.resolve("wire-events.json"),session.packetEvents());
                write(roundOutput.resolve("join.json"),join);
                write(roundOutput.resolve("semantic.json"),semantic);
                    write(roundOutput.resolve("result.json"),Map.of("passed",true,"round",round,"mode",mutated?"mutated":"baseline",
                            "measuredFrames",semantic.size(),"runtimeEntityId",join.runtimeEntityId()));
                    nextTick = lastTick+3;
                }
                write(output.resolve("playback-complete.json"),Map.of("passed",true,"username",bedrockName));
                waitForDriver(session,output.resolve("disconnect.json"),180);
            }
        }catch(Throwable e){failure=e;primaryThrown=true;throw e;}
        finally {
            if(prepared)try{control.finish();}catch(Throwable e){if(failure!=null)failure.addSuppressed(e);else failure=e;}
            Files.createDirectories(output);
            write(output.resolve("result.json"),Map.of("scenario",scenario.id(),"seed",Long.toString(seed),"mode",mutated?"mutated":"baseline","passed",failure==null,"measuredFrames",semantic.size(),"durationSeconds",(System.nanoTime()-start)/1e9,"failure",failure==null?"":failure.toString()));
            if(failure!=null && !primaryThrown) {
                if(failure instanceof Exception e)throw e;
                throw new RuntimeException(failure);
            }
        }
    }
    /**
     * Replays the whole tape on a connection CultAC does not check: the backend takes every frame with no correction,
     * setback or external movement. Returns the tick the next round starts at.
     */
    private static long replayUnchecked(BedrockProtocolClient.LiveBedrockSession session,MovementScenario scenario,long nextTick,
            long runtimeEntityId,int moveStart,Path roundOutput,int round) throws Exception {
        long shift=Math.subtractExact(nextTick,scenario.firstTick());
        PlayerAuthInputPacket first=(PlayerAuthInputPacket)MovementPackets.decode(scenario.frames().getFirst(),shift,runtimeEntityId);
        long spawnDeadline=System.nanoTime()+10_000_000_000L;boolean spawnObserved=false;
        while(!spawnObserved && System.nanoTime()<spawnDeadline) {
            session.requireHealthyTransport();session.processServerTickBoundary();
            spawnObserved=session.serverMovePositions().subList(moveStart,session.serverMovePositions().size()).stream().anyMatch(m -> m.mode().equals("TELEPORT")
                && Math.sqrt(sq(m.x()-first.getPosition().getX())+sq(m.y()-first.getPosition().getY())+sq(m.z()-first.getPosition().getZ()))<=.001);
            Thread.sleep(50);
        }
        if(!spawnObserved)throw new IllegalStateException("Real spawn teleport never matched the captured initial position");
        // No engine reports the client's chunk acknowledgements here, so the restored terrain gets a fixed settle.
        long settle=System.nanoTime()+2_000_000_000L;
        while(System.nanoTime()<settle){session.requireHealthyTransport();session.processServerTickBoundary();Thread.sleep(50);}
        session.sendCapturedAuthInput(teleportAcknowledgement(first,first.getTick()-1,first.getPosition()));
        int corrections=session.corrections().size(),moves=session.serverMovePositions().size();
        int effects=session.movementEffects().size(),motions=session.entityMotionEvents().size();
        int frame=-1;long lastSend=0;List<BedrockPacket> pendingPackets=new ArrayList<>();
        for(JsonObject row:scenario.packets()) {
            BedrockPacket packet=MovementPackets.decode(row,shift,runtimeEntityId);
            if(!(packet instanceof PlayerAuthInputPacket auth)){pendingPackets.add(packet);continue;}
            frame++;
            long delay=50_000_000L-(System.nanoTime()-lastSend);if(delay>0)TimeUnit.NANOSECONDS.sleep(delay);
            session.requireHealthyTransport();session.processServerTickBoundary();
            for(BedrockPacket side:pendingPackets)session.sendCapturedPacket(side);
            pendingPackets.clear();
            session.sendCapturedAuthInput(auth);lastSend=System.nanoTime();
            if(session.corrections().size()!=corrections)
                throw new IllegalStateException("Correction on unchecked frame "+frame+": "+session.corrections().getLast());
            if(!transportTeleports(session.serverMovePositions(),moves).isEmpty())
                throw new IllegalStateException("Transport teleport on unchecked frame "+frame);
            if(session.movementEffects().size()!=effects || session.entityMotionEvents().size()!=motions)
                throw new IllegalStateException("External effect/velocity on unchecked frame "+frame);
        }
        session.processServerTickBoundary();
        for(BedrockPacket side:pendingPackets)session.sendCapturedPacket(side);
        Thread.sleep(100);session.processServerTickBoundary();
        if(session.corrections().size()!=corrections)throw new IllegalStateException("Correction after the unchecked tape");
        if(session.packetEvents().stream().anyMatch(e -> e.startsWith("decode-error:")))throw new IllegalStateException("Transport decode failure");
        write(roundOutput.resolve("wire-events.json"),session.packetEvents());
        write(roundOutput.resolve("result.json"),Map.of("passed",true,"round",round,"mode","unchecked","replayedFrames",frame+1));
        return scenario.firstTick()+shift+scenario.frames().size()-1+3;
    }

    private static List<BedrockServerMoveEvent> transportTeleports(List<BedrockServerMoveEvent> moves,int cursor) {
        return moves.subList(cursor,moves.size()).stream().filter(m -> m.mode().equals("TELEPORT") || m.mode().equals("RESET")).toList();
    }
    static PlayerAuthInputPacket teleportAcknowledgement(PlayerAuthInputPacket source,long tick,
            org.cloudburstmc.math.vector.Vector3f target) {
        // Cloudburst clone() shares the mutable flag set; control packets need their own.
        PlayerAuthInputPacket ack=new PlayerAuthInputPacket();ack.setTick(tick);ack.setPosition(target);
        ack.setRotation(source.getRotation());ack.setInputMode(source.getInputMode());
        ack.setPlayMode(source.getPlayMode());ack.setInputInteractionModel(source.getInputInteractionModel());
        ack.setVrGazeDirection(source.getVrGazeDirection());ack.setCameraOrientation(source.getCameraOrientation());
        ack.setInteractRotation(source.getInteractRotation());ack.setVehicleRotation(source.getVehicleRotation());
        ack.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        // MovePlayer TELEPORT resets motion and filters MobTravel for this actor tick.
        // AuthInput delta is future velocity, so the recorded gameplay gravity is not an echo.
        ack.setDelta(org.cloudburstmc.math.vector.Vector3f.ZERO);
        ack.setMotion(org.cloudburstmc.math.vector.Vector2f.ZERO);
        ack.setAnalogMoveVector(org.cloudburstmc.math.vector.Vector2f.ZERO);
        ack.setRawMoveVector(org.cloudburstmc.math.vector.Vector2f.ZERO);
        ack.setItemUseTransaction(null);ack.setItemStackRequest(null);
        return ack;
    }
    public static void requireCompatibleTeleport(BedrockServerMoveEvent teleport,PlayerAuthInputPacket original) {
        var p=original.getPosition();
        if(!Set.of("TELEPORT","RESET").contains(teleport.mode())
                || Math.sqrt(sq(teleport.x()-p.getX())+sq(teleport.y()-p.getY())+sq(teleport.z()-p.getZ()))>.001)
            throw new IllegalStateException("Transport setback is incompatible with untouched recorded trajectory: "+teleport);
    }
    public static void requireCompatibleCorrection(BedrockCorrectionEvent c,PlayerAuthInputPacket original) {
        if(c.tick()!=original.getTick())throw new IllegalStateException("Correction addressed a different auth tick");
        var p=original.getPosition();var d=original.getDelta();
        if(Math.sqrt(sq(c.x()-p.getX())+sq(c.y()-p.getY())+sq(c.z()-p.getZ()))>.001 || Math.sqrt(sq(c.deltaX()-d.getX())+sq(c.deltaY()-d.getY())+sq(c.deltaZ()-d.getZ()))>.001)
            throw new IllegalStateException("Live correction is incompatible with untouched recorded trajectory: "+c);
    }
    private static double sq(double x){return x*x;}
    private static void waitForDriver(BedrockProtocolClient.LiveBedrockSession session,Path file,int seconds) throws Exception {
        long deadline=System.nanoTime()+seconds*1_000_000_000L;
        while (!Files.isRegularFile(file)) {
            session.requireHealthyTransport();session.processServerTickBoundary();
            if (System.nanoTime()>deadline) throw new IllegalStateException("Driver barrier timed out: "+file);
            Thread.sleep(20);
        }
    }

    private static void suspend(Path plugin, String run) throws Exception {
        String id = UUID.randomUUID().toString();
        write(plugin.resolve("control.json"), Map.of("requestId",id,"operation","suspend","runId",run));
        var reply = LiveControl.await(plugin.resolve("control-status.json"),
                value -> id.equals(string(value,"requestId")), Duration.ofSeconds(10));
        if (!required(reply,"ok").getAsBoolean()) throw new IllegalStateException("Observer suspension failed: "+reply);
    }
    private static void append(Path path,Object row)throws Exception{Files.writeString(path,JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
}
