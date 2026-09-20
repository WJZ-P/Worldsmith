# Worldsmith module briefs and implementation alignment — authoring contract 1

For a new COMPLETE_WORLD, first save and AI-review the WorldBible. Derive a named
WorldDesignPlan and module briefs from that fact source before expensive content
production. Read `world_bible`, the relevant domain contract, current progress,
and the related setting nodes; do not reload every unrelated contract each turn.

## Atomic brief collection

Submit an atomic ID-keyed merge using
`worldsmith_put_module_briefs(sessionId, expectedRevision, briefs, removeIds?)`:

```text
ModuleBrief {
  id: stableLocalId,
  targets: [{kind, id}],
  purpose: concreteImplementationRole,
  basisRefs: ["node/region_id", "requirement/request_id"],
  dependencies: [otherBriefId],
  criteria: [{id: localCriterionId, claim: concreteAcceptanceClaim,
              target: {kind, id}}],
  openDecisions: [string]
}
```

Use actual supported ContentKeys, including `mechanic/<id>` for installed
event-driven interactions. Review their actual pattern, heldItem cost, state
transition and typed actions through `/modules/mechanics/mechanics/<index>`;
a setting rule or quest description alone does not fulfill that target. Authoring nodes/regions are not new ContentKey
kinds. A target has exactly one owning brief; reuse shared content through brief
dependencies instead of duplicating ownership. Every target needs at least one
criterion naming that owned target. Dependencies are brief IDs forming an acyclic
production graph, not all narrative or material relations. Mutually related
ecology can be planned together without adding a production cycle.

`BriefRecord` wraps the brief with service-stamped `bibleRevision` and
`basisDigest`. Clients submit creative choices, not self-attested provenance.
Read saved authoring state in the draft/resume response. Unmentioned IDs are
retained; `removeIds` explicitly removes briefs, with references and coverage
checked against the resulting collection. Each write is atomic and uses the
returned session revision; after a CAS conflict reread and merge current changes.

Use 1..128 briefs, each with 1..128 targets, 1..128 criteria, 1..512 distinct basis
references, at most 128 dependency IDs, and at most 64 open decisions. Purpose
and criterion claims are at most 4096 characters. Open decisions are distinct,
nonblank and at most 2048 characters. Respect the shared 8 MiB session budget.

## Claims that guide real implementation

Record relevant choices for habitat, geography, materials, architecture function,
abilities and sound, plus source/consumption/use/reward relationships. Describe
what each output must embody and where to inspect that claim, not only its ID.
The main line, resource chain and regional ecology are designed together.

For a resource-depleted ancient city, useful linked claims might be: scarce
crystals occur in a specific region; an actual structure supplies the first
required quantity; its associated creature voice and ecology reflect the setting;
the first delivery consumes less than the available earlier supply; the reward
enables a later supported item action. These are examples of causal reasoning,
not fixed required content or names. Every claimed mechanism needs actual fields.

Terrain-first is a production dependency after the bible/plan/briefs, not a reason
to defer story and resource planning. Build one representative slice and inspect
actual geometry, PNGs and supported sound profiles before multiplying content.
Keep species schema-3 voices through rebuilds; a sound event profile is not a
recording or proof that anyone listened to it.

Opaque high-cost tools `worldsmith_build_drawing`, `worldsmith_build_texture`,
`worldsmith_import_texture_file`, `worldsmith_put_texture_asset` and
`worldsmith_create_pixel_texture` accept the tool argument `briefIds: [briefId]`.
New COMPLETE_WORLD calls supply at least one current owning brief for this work.
`worldsmith_build_creature` infers its target from `recipe.id`; module/structure
writes infer actual target IDs and require their owning briefs. `briefIds` is a
tool argument, not a new field inside runtime modules, PNG recipes or structures.
Legacy/lightweight tool calls retain their existing parameter compatibility.

If implementation changes a setting fact, update WorldBible first, review that
revision, then refresh affected briefs. Do not create a second story in a brief
or merely update the player-facing theme to hide a contradiction.

## Review the actual candidate, not only the brief

Read `worldsmith_get_authoring_review_context(sessionId, subjectId: briefId)`
after saving the actual relevant modules/structures/assets. The context supplies
`expectedBasisDigest`, `expectedContentDigest`, `expectedInputDigest`,
`requiredCheckIds`, `evidenceDocument` and `evidenceRoots`. Use existing JSON
Pointers rooted in the returned evidence for the review's `evidencePaths`;
`blockedEvidenceRoots` are for BLOCKED absence findings only.
If `evidenceDocumentIncluded` is false, `evidencePreview` is only a bounded
excerpt; inspect the relevant full current draft/structures before reviewing.
Compare the global hard constraints, related facts, brief criteria, dependencies
and actual field values. Write one concrete requirement/fact → implementation
claim → evidence → conclusion chain for each required check.

Submit `worldsmith_review_world_alignment(sessionId, expectedRevision,
subjectId: briefId, review)` using the AuthoringReview schema in `world_bible`.
Its `review.subjectId` matches the brief. `source` is AUTHORING_AI; PASS and
BLOCKED both need real paths and reasoned conclusions. Copy current returned
digests, never substitute the digest of an earlier plan. Mention precise repairs
in BLOCKED conclusions; generic consistency praise is not evidence. Map the
expected basis/content digests to the review's `basisDigest`/`contentDigest`;
the input digest is context metadata, not an additional review field.

Theme also has an owning brief: review its bounded runtime premise, rules and
beats against the bible and actual linked content. Planned bosses need their
real profile, encounter/habitat and task; requiresBoss=false does not excuse any
Boss that was explicitly promised in the design plan from those checks.

Changing a relevant definition, referenced texture or frozen geometry makes old
alignment evidence stale. An unrelated uploaded PNG is not itself a setting
change. Global constraints and dependency changes invalidate affected evidence;
completed assets stay available for reuse. Follow exact progress gaps rather than
rebuilding every module or resubmitting successful drawing jobs.

## Completion and evidence boundaries

Current passing world and implementation reviews supplement, not replace, the
existing engineering checks. `write_pack` and `finish_world` recheck provenance
for the actual candidate, including inline documents. Coverage checks still
require real definitions/relationships; quest reachability still checks sources,
self/future locks and finite consumed supply. A valid link or positive random
loot chance does not demonstrate sensible travel, difficulty or narrative motive.

AI self-review passes automatically to the next gap. Identical failures on the
same input/issue have at most three automatic repair attempts before reporting a
specific blocking decision. Keep AI review, geometry/texture previews, Core pack
checks, archive readiness, native activation and observed gameplay separate.
The review loop does not bypass independent worker source-execution approvals.

Briefs and review evidence remain session data, not extra runtime modules or
fields in `.wspack`. Existing packs, legacy sessions and lightweight modes retain
their session recovery paths; published bundles must use format 10. Browsing the read-only progress/bible screens starts
no content generation and performs no resource reload.

Ability programs use `ability/<id>` targets. Review actual code at
`/modules/abilities/programs/<index>/source`, required capabilities and the bound
item/creature/mechanic definition. Criteria should describe timing, branches,
interruptions, cleanup and player counterplay. Do not accept an attack name or
lore paragraph as implementation evidence. Add invokes_ability to the design
plan for each promised host binding.
