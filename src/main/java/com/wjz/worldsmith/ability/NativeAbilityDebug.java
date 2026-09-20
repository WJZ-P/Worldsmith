package com.wjz.worldsmith.ability;

import com.google.gson.*;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.ability.debug.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Thread-confined opt-in native evidence. Offline fixtures never call this bridge. */
public final class NativeAbilityDebug implements AbilityRuntimeDebugHost {
    private static final Gson JSON=new Gson();
    private static final Map<ServerLevel,Map<UUID,Trace>> TRACES=new IdentityHashMap<>();
    private static final int MAX_ACTORS=8,MAX_ENTRIES=256,MAX_CHARS=131072;
    private static final class Trace {
        final Deque<String> entries=new ArrayDeque<>(); int characters; long dropped; boolean enabled=true;
        final LivingEntity actor;
        long expiresAt;
        Trace(LivingEntity actor,long expiresAt) { this.actor=actor; this.expiresAt=expiresAt; }
        void add(UUID invocation,String program,AbilityTraceEvent event) {
            var object=new JsonObject(); object.addProperty("invocation",invocation.toString()); object.addProperty("program",program);
            object.add("trace",JSON.toJsonTree(AbilityTraceViews.entry(event)));
            String line=JSON.toJson(object);
            if(line.length()>MAX_CHARS) { dropped++; return; }
            while(entries.size()>=MAX_ENTRIES || characters+line.length()>MAX_CHARS) { characters-=entries.removeFirst().length(); dropped++; }
            entries.addLast(line); characters+=line.length();
        }
    }
    @Override public kotlinx.serialization.json.JsonObject inspect(kotlinx.serialization.json.JsonObject arguments) {
        JsonObject request=JsonParser.parseString(arguments.toString()).getAsJsonObject();
        MinecraftServer server=WorldAbilityRuntime.connectedServer();
        if(server==null) return result(unavailable("No bound native world"));
        if(server.isSameThread()) return result(onServer(server,request));
        var future=new CompletableFuture<JsonObject>();
        server.execute(() -> {
            if(future.isCancelled()) return;
            try { future.complete(onServer(server,request)); } catch(Throwable failure) { future.completeExceptionally(failure); }
        });
        try { return result(future.get(5,TimeUnit.SECONDS)); }
        catch(TimeoutException timeout) { future.cancel(false); throw new IllegalStateException("Native ability inspection timed out",timeout); }
        catch(InterruptedException interrupted) { future.cancel(false); Thread.currentThread().interrupt(); throw new IllegalStateException("Native ability inspection interrupted",interrupted); }
        catch(ExecutionException failure) { throw new IllegalArgumentException("Native ability inspection failed: "+failure.getCause().getMessage(),failure.getCause()); }
    }
    private static JsonObject unavailable(String reason) {
        var value=new JsonObject(); value.addProperty("available",false); value.addProperty("minecraftExecuted",false); value.addProperty("message",reason); return value;
    }
    private static kotlinx.serialization.json.JsonObject result(JsonObject value) {
        return (kotlinx.serialization.json.JsonObject)kotlinx.serialization.json.Json.Default.parseToJsonElement(value.toString());
    }
    private static JsonObject onServer(MinecraftServer server,JsonObject request) {
        String action=request.get("action").getAsString(); UUID actorId=UUID.fromString(request.get("actor").getAsString());
        int limit=request.has("limit")?request.get("limit").getAsInt():128;
        if(limit<1 || limit>256 || !Set.of("snapshot","start_trace","read_trace","stop_trace").contains(action)) throw new IllegalArgumentException("Invalid inspection action/limit");
        LivingEntity actor=null; ServerLevel level=null;
        for(ServerLevel candidate:server.getAllLevels()) if(candidate.getEntity(actorId) instanceof LivingEntity living && WorldAbilityRuntime.snapshot(candidate)!=null) { actor=living; level=candidate; break; }
        if(actor==null) return unavailable("Actor is not loaded in a bound world");
        String scope=WorldAbilityRuntime.snapshot(level).scope()+"@"+level.dimension().identifier();
        if(request.has("scope") && !scope.equals(request.get("scope").getAsString())) throw new IllegalArgumentException("Native ability scope mismatch");
        var traces=TRACES.get(level); Trace trace=traces==null?null:traces.get(actorId);
        if(action.equals("start_trace")) {
            if(trace==null) {
                int total=TRACES.values().stream().mapToInt(Map::size).sum(); if(total>=MAX_ACTORS) throw new IllegalStateException("Native trace actor budget reached; stop an existing trace first");
                trace=new Trace(actor,level.getGameTime()+1200); TRACES.computeIfAbsent(level,ignored -> new LinkedHashMap<>()).put(actorId,trace);
            }
            trace.enabled=true; trace.expiresAt=level.getGameTime()+1200;
            for(var active:WorldAbilityRuntime.debugInvocations(actor)) attach(active.machine(),trace,active.id(),active.program());
        }
        if(action.equals("stop_trace")) {
            if(trace!=null) trace.enabled=false;
            for(var active:WorldAbilityRuntime.debugInvocations(actor)) active.machine().setTraceListener(null);
        }
        var response=new JsonObject(); response.addProperty("available",true); response.addProperty("liveRuntime",true);
        response.addProperty("minecraftExecuted",false); response.addProperty("observationOnly",true); response.addProperty("scope",scope);
        response.addProperty("actor",actorId.toString()); response.addProperty("worldTick",level.getGameTime());
        response.addProperty("tracing",trace!=null && trace.enabled);
        if(trace!=null) response.addProperty("expiresAt",trace.expiresAt);
        if(action.equals("snapshot")) {
            var programs=new JsonArray();
            for(var active:WorldAbilityRuntime.debugInvocations(actor)) {
                var item=new JsonObject(); item.addProperty("invocation",active.id().toString()); item.addProperty("program",active.program());
                if(active.parent()!=null) item.addProperty("parent",active.parent().toString()); item.addProperty("scene",active.scene());
                item.addProperty("stateRevision",active.machine().stateRevision()); item.addProperty("idle",active.machine().isIdle()); item.addProperty("resources",active.resources());
                var state=new JsonObject(); active.machine().snapshotState().forEach((key,value) -> state.add(key,JSON.toJsonTree(AbilityTraceViews.value(value))));
                item.add("state",state); programs.add(item);
            }
            response.add("programs",programs);
        }
        if(action.equals("read_trace") || action.equals("stop_trace")) {
            var entries=new JsonArray();
            if(trace!=null) {
                int skip=Math.max(0,trace.entries.size()-limit),index=0;
                for(String entry:trace.entries) if(index++>=skip) entries.add(JsonParser.parseString(entry));
                response.addProperty("dropped",trace.dropped);
            }
            response.add("entries",entries);
        }
        // Stop returns the final buffer and releases its slot; no background trace survives an explicit stop.
        if(action.equals("stop_trace") && traces!=null) { traces.remove(actorId); if(traces.isEmpty()) TRACES.remove(level); }
        return response;
    }
    private static void attach(AbilityMachine machine,Trace trace,UUID invocation,String program) {
        machine.setTraceListener(event -> { if(trace.enabled) trace.add(invocation,program,event); });
    }
    static void started(ServerLevel level,LivingEntity actor,String program,UUID invocation,AbilityMachine machine) {
        var traces=TRACES.get(level); var trace=traces==null?null:traces.get(actor.getUUID());
        if(trace!=null && trace.enabled) attach(machine,trace,invocation,program);
    }
    static void unbound(ServerLevel level) { TRACES.remove(level); }
    static void tick(ServerLevel level) {
        var traces=TRACES.get(level); if(traces==null) return;
        for(var entry:List.copyOf(traces.entrySet())) {
            Trace trace=entry.getValue();
            if(level.getGameTime()<trace.expiresAt && level.getEntity(entry.getKey())==trace.actor && !trace.actor.isRemoved()) continue;
            trace.enabled=false;
            for(var active:WorldAbilityRuntime.debugInvocations(trace.actor)) active.machine().setTraceListener(null);
            traces.remove(entry.getKey());
        }
        if(traces.isEmpty()) TRACES.remove(level);
    }
}
