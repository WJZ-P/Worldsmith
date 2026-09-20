# Discovered quest journeys

Worldsmith's current quest module is schema 2: a bounded prerequisite DAG rather
than a single mandatory chain. See the packaged [quest authoring contract](../core/src/main/resources/prompts/contract/quests.system.md) for exact fields and limits.

## Player experience

- Undiscovered content is omitted server-side; unknown future titles, rewards,
  objectives and destinations do not appear in the journal or advancement gallery.
- Prerequisites have ALL/ANY semantics. Use ANY to reunite mutually exclusive branches.
- Manual offers can be accepted or deferred. Choosing an exclusive branch asks for
  explicit confirmation and persists the selected alternative.
- Optional journeys and optional goals are labelled. All required goals must finish;
  optional goals never block the reward claim.
- Story fact objectives observe the shared fact ledger instead of storing a second
  copy. Kill and delivery counters are player-owned; mechanic facts are retrospective.
- Journal tracking links only to actual discovered place instances. World knowledge,
  residents and places are reached through the adjacent world-journal button.

## Transition semantics

Each quest may declare discoverWhen, availableWhen, onAccept, onClaim and destination.
Discovery requires satisfied prerequisites plus discoverWhen and is then durable.
availableWhen gates acceptance, active kill credit, delivery and reward claims.
Accepted promises are not undone by DECLINE; that action only defers an unaccepted
manual offer. Exclusive branches require manualAccept=true and are selected on ACCEPT.
A rejected branch's descendants are excluded; an ANY merge stays available through
its chosen predecessor. The server computes completion from the entire required DAG,
not just whichever entries are currently visible.

Story changes and inventory are prepared before mutation and committed together with
the quest attachment on the server thread. Errors roll back that transaction; packet
failure after a commit never causes a repeated reward. Individual Minecraft save files
are not claimed to provide database crash atomicity. There is no schema-1 migration.

## Verification checklist

1. Start without future quest names in journal or native advancements.
2. Discover through a real fact, actual interaction or claimed predecessor.
3. Accept/decline; confirm a branch and verify the other branch stops accepting actions.
4. Complete optional and necessary objectives in different orders.
5. Fill inventory, attempt a claim, and verify neither facts nor rewards partially commit.
6. Retry an old nonce/revision and verify no duplicate effects.
7. Return to the world after save/reload; discovery, branch, tracking and consequences remain.
8. Finish one branch and its ANY merge; verify full campaignComplete despite the rejected path.

Static supply proof keeps branch inventories separate and reports bounded search
exhaustion rather than claiming success. It does not prove arbitrary dialogue facts
or generated coordinates: those need native runtime coverage of the authored slice.
