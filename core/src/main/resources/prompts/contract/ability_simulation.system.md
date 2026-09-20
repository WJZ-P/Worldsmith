# AbilityScript simulation and live inspection

Use `worldsmith_simulate_ability` to debug **actual source**, not a fixed move catalogue. It runs the same Core compiler and bounded VM used by the runtime, with no Minecraft host. Every result states `simulationOnly=true` and `minecraftExecuted=false`. A supplied `combat.damage` result is a fixture assertion, not damage applied in a game; geometry, LOS, protection rules, collision, resource leases and native event delivery still need native verification.

## Inputs and explicit host responses

Supply exactly one `program` (AbilityProgramDefinition) or `library` plus `programId`. A library chooses one program; other programs are not certified by this simulation. Supply `inputs` with `self`, `target`, `origin`; optional `args` is any bounded AbilityValue and defaults to null, matching child-program arguments. MCP values use the Worldsmith `kind` discriminator:

```json
{
  "self": {"kind":"entity","id":"sim-caster"},
  "target": {"kind":"entity","id":"sim-target"},
  "origin": {"kind":"vector","x":0,"y":64,"z":0}
}
```

`target` may be `{"kind":"null"}`. Entity strings are opaque simulated handles, not verified native UUIDs. `initialState` uses the same typed values and normal state limits. Real pure functions (math, vectors, lists, regions and registered pure extensions) execute in Core. Every **non-pure host query or effect** requires the next entry in `responses`:

```json
{"capability":"combat.damage","arguments":[{"kind":"entity","id":"sim-target"},{"kind":"number","value":3}],"tick":2,"result":{"kind":"bool","value":true}}
```

Responses are consumed strictly in order. Optional `arguments` and `tick` require exact matches. Omit these expectations only deliberately. Supply `error` instead of `result` to test a host failure. A missing response, wrong capability, arguments or tick stops with `HOST_INPUT_REQUIRED`; there is no fallback response or implicit success. Unconsumed responses are reported as `UNUSED_RESPONSE`. Invalid response types fail through the VM's normal capability result validation.

`events` contains `{tick,event,payload}`. Events at a tick are enqueued in declaration order before that tick's continuation. Payload keys are `event_entity`, `event_position`, `event_amount`, `event_tag`, `event_data`; values are typed. An unknown handler or full queue is reported, not silently accepted. `signal.emit`, projectiles and other host capabilities do not fabricate follow-up events: schedule those events explicitly. The simulator may retain an idle instance to deliver later fixtures; this does not prove that native resource leases would keep it alive.

## Budgets and trace

- `ticks`: 1..12000, default 200. `operationsPerTick`: 1..8192, default 128. Program total-operation/lifetime limits still apply.
- At most 256 ordered responses and 128 events. Event/response ticks must be inside the requested run.
- All fixture values together are limited to 256 KiB, in addition to normal immutable value/state limits. MCP request JSON is limited to 4 MiB.
- `traceLimit`: 0..2048, default 2048. Instruction trace and host-call report data share a 256 KiB compact-JSON budget. `droppedTraceEntries` / `droppedHostCallReports` explicitly report clipping; execution still continues within its VM budget. `capabilityCalls` counts all reached capability calls, even when details are clipped.
- Every recorded instruction has tick, operation number, handler event, function, PC, source line/column, call depth and state revision. Capability call/result/failure, successful state writes and waits have matching source locations. Value previews are at most 256 characters and mark truncation; they are not complete values.
- Output includes final immutable state, diagnostics, per-tick/total budget usage, unused/mismatched host responses and rejected/undelivered events. `TICK_LIMIT` means unfinished simulation, not a successful gameplay verification. Trace clipping is separate from execution failure.

Observers are opt-in. The normal runtime does not allocate trace events. A throwing trace observer is detached; it does not stop the program or replay effects. The VM retires effect instructions before host dispatch, including failed host fixtures.

## Live runtime inspection

`worldsmith_inspect_ability_runtime` is a separate native host bridge. It accepts `action` (`snapshot`, `start_trace`, `read_trace`, `stop_trace`), a canonical actor UUID, optional native `scope`, and optional `limit` 1..256. Native scope is `<bundleSHA256>@<dimensionIdentifier>`, returned by snapshot. The native host switches to its server thread with a 5-second dispatch timeout, validates the loaded actor/scope, and keeps at most 8 actor traces, 256 entries/128 Ki characters per actor. `start_trace` attaches to existing and future invocations without restarting fibers; collection expires after 1200 world ticks, actor removal or world unbind. Starting/stopping trace collection does not start/cancel an ability or change world content. `stop_trace` returns the final buffer and frees its slot. Responses mark `liveRuntime=true`, `observationOnly=true`, and `minecraftExecuted=false` because inspection did not execute a gameplay effect. An offline host returns `available=false`, not simulated live state.
