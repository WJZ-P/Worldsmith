# Worldsmith persistent world bible — authoring contract 1

The connected AI expands the player's prompt into a WorldBible before producing
a new COMPLETE_WORLD. The Mod stores facts and checks structure/provenance; it
does not run a hidden LLM. AI self-review automatically continues when current
checks pass. This is not user approval and does not prove gameplay quality.

## Single source of setting facts

Read `worldsmith_get_world_bible(sessionId, format: "json")`. Optional `nodeIds`
and `summary` keep context focused; `format: "markdown"` returns a deterministic
reading view, not a second editable document. A filtered reading view is not a
replacement bible. Write the complete structured object with
`worldsmith_put_world_bible(sessionId, expectedRevision, bible)`.

```text
WorldBible {
  title: string, premise: string, playerRole: string, mainConflict: string,
  requirements: [{id, text, promptQuote}],
  assumptions: [string],
  nodes: [{id, kind, title, description, dependsOn: [nodeId], truth}],
  requiresBoss: boolean,
  openDecisions: [string]
}
```

`kind` is RULE, HISTORY, REGION, CULTURE, ECOLOGY, RESOURCE, STYLE or EXPERIENCE.
`truth` is FACT, LEGEND or BELIEF (default FACT): conflicting beliefs are not
automatically conflicting author facts. IDs use `[a-z0-9][a-z0-9_.-]{0,63}`.
Node `dependsOn` lists existing local node IDs, with no self-reference or cycle.
It describes setting dependencies, not every in-world interaction.

Record every hard constraint using an exact nonblank `promptQuote` from the
session's original prompt. Inferred choices belong in `assumptions`, not invented
quotations. Cover history and causes, regional transitions, ecology, resource
producers and uses, societies/settlement functions, visual and sound language,
the player's role and main-line experience. A short prompt still needs concrete
derived choices; add coherent detail rather than copying an example catalog.

Plan arrival, main-line beats, acquisition, delivery consumption and meaningful
rewards together. The later task line realizes this skeleton; it is not pasted
onto unrelated finished assets. `requiresBoss` records an explicit scope promise,
not a fixed combat template. New complete worlds retain the six content kinds;
a peaceful exploration world may set false while still including real creatures
and a supported delivery main line. Legacy sessions keep their original policy.

Map promised capabilities to the installed contracts. For executable rituals,
keys and exchanges, use the installed `mechanics` contract: player block placement
or main-hand use, bounded patterns, held costs, anchor states and typed actions.
Record a RULE node and owning `mechanic` brief with actual field-level evidence.
Thrown-item triggers and tick scripts are outside that grammar.  If the prompt needs a
mechanic not installed, preserve the demand and an `openDecisions` blocker. Do
not relabel it as atmospheric prose and call the request fulfilled. Ordinary
fictional history is fine when it was not promised as executable gameplay.

## Stable references and budgets

The service exposes `world/title`, `world/premise`, `world/player_role`,
`world/conflict`, `world/requires_boss`, `world/assumptions`,
`world/open_decisions`, `requirement/<id>` and `node/<id>` references. These are
authoring references, not new runtime ContentKey kinds or bundle modules.

Use 1..128 requirements and 1..256 nodes. Titles are at most 160 characters;
premise/playerRole/mainConflict and node descriptions at most 8192; requirement
text and promptQuote at most 4096. Assumptions and open decisions each allow up
to 64 distinct nonblank entries of at most 2048 characters. A node has at most
64 dependency IDs. Observe the shared session 8 MiB byte limit as well as the
live authoring budgets. Over-budget writes return diagnostics, not silently
shortened facts. Only the UI reading projection may be explicitly truncated.

## Evidence-bound AI review

1. Save the bible and keep the returned session revision. Read
   `worldsmith_get_authoring_review_context(sessionId, subjectId: "world_bible")`.
2. Inspect actual requirements, facts and scope. Copy returned
   `expectedBasisDigest`/`expectedContentDigest` into the review's
   `basisDigest`/`contentDigest`. Follow `requiredCheckIds`; choose actual JSON
   Pointer `evidencePaths` within `evidenceDocument` from `evidenceRoots` or their
   existing descendants. Do not invent hashes or paths. `expectedInputDigest`
   identifies the review input/retry basis, not another review DTO field.
   Cover each required check and explain the reasoning.
   If `evidenceDocumentIncluded` is false, excerpts are only a preview: read the
   relevant full current draft/structure definitions before deciding the result.
3. Submit `worldsmith_review_world_bible(sessionId, expectedRevision, review)`:

```text
AuthoringReview {
  id: localReviewId, subjectId: "world_bible",
  basisDigest: returnedDigest, contentDigest: returnedDigest,
  summary: findings,
  source: "AUTHORING_AI", contractVersion: 1,
  checks: [{criterionId, basisRefs: [reference], claim,
            evidencePaths: [returnedPath], conclusion, status: "PASS" | "BLOCKED"}]
}
```

Both outcomes need concrete claims, evidence and conclusions. Use the report's
BLOCKED conclusion to name the contradiction and repair/scope decision. Each
check must resolve to supplied evidence. Reference presence and flattering prose
do not establish semantic alignment. The service verifies report structure,
coverage and freshness, not the truth of an AI's literary judgment. Review source
is fixed AUTHORING_AI; never present it as user confirmation or a playthrough.

A report has 1..512 distinct checks. Summaries, claims and conclusions are at
most 4096 characters; each check has 1..512 basis references and 1..64 evidence
paths (at most 512 characters each). Reuse the returned exact required IDs.

After PASS, follow progress immediately into the plan/module briefs. On BLOCKED,
repair the actual input and review its new snapshot. At most three identical
failures for the same input and issue are attempted automatically; then surface
the exact remaining blocker or scope decision instead of looping. A cosmetic
change to report prose is not a repaired world.

## Revisions, recovery and compatibility

All authoring writes use the existing session CAS `expectedRevision`.
`bibleRevision` is a separate semantic version; never pass it as the CAS revision.
On conflict reread/merge without erasing other work. Global facts and hard rules
invalidate affected derivations; local edits affect their references and dependent
briefs. A removed referenced node remains a repair finding. Existing content,
PNGs and successful drawing jobs are preserved while evidence needs review.

New COMPLETE_WORLD sessions use this contract. WORLDGEN_ONLY and STANDALONE stay
lightweight; old sessions resume their legacy workflow unless explicitly opted in
with `worldsmith_upgrade_world_authoring(sessionId, expectedRevision)` for an
existing COMPLETE_WORLD. Upgrading
adds missing authoring work around existing results; it does not regenerate them.

The complete bible, briefs and review evidence persist in the session only.
WorldTheme is a bounded, reviewed projection for the existing runtime. `.wspack`
format 7 freezes ten typed gameplay modules, including mechanics; a shared pack
is not a full portable authoring archive. Older formats are rejected without
rewriting local pack or save files. Reading the bible never triggers resource activation.
