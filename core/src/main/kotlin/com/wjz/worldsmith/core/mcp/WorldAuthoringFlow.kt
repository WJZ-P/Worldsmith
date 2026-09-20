package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.*

/** Progress hints are derived from immutable authoring snapshots, never from model activity guesses. */
object WorldAuthoringFlow {
    fun subject(s:WorkflowSession,problem:Diagnostic):String? {
        val a=s.authoring ?: return null
        return a.briefs.sortedByDescending {it.brief.id.length}.firstOrNull {problem.path.contains("authoring.briefs.${it.brief.id}")}?.brief?.id
            ?: a.bible?.requirements?.firstOrNull {problem.path=="authoring.bible.requirements.${it.id}"}?.let {r->
                a.briefs.firstOrNull {"requirement/${r.id}" in it.brief.basisRefs}?.brief?.id
            }
    }
    fun arguments(s:WorkflowSession,tool:String,subject:String?=null):JsonObject=buildJsonObject {
        put("sessionId",s.id)
        if(tool in setOf("worldsmith_put_world_bible","worldsmith_review_world_bible","worldsmith_put_module_briefs",
                "worldsmith_review_world_alignment","worldsmith_upgrade_world_authoring","worldsmith_put_world_design_plan",
                "worldsmith_put_content_modules","worldsmith_put_architecture_draft","worldsmith_write_pack"))put("expectedRevision",s.revision)
        if(subject!=null && tool in setOf("worldsmith_get_authoring_review_context","worldsmith_review_world_alignment"))put("subjectId",subject)
    }
    fun issues(s:WorkflowSession):List<GenerationIssue> {
        val a=s.authoring ?: return emptyList()
        if(s.mode!=WorkflowMode.COMPLETE_WORLD)return emptyList()
        fun issue(code:String,category:String,message:String,tool:String,subject:String?=null,blocked:Boolean=false,priority:Int=-70)=GenerationIssue(
            code,category,message,tool,arguments(s,tool,subject),
            when(tool){"worldsmith_put_world_bible"->listOf("bible");"worldsmith_put_module_briefs"->listOf("briefs");else->emptyList()},blocked,priority,authoringSubjectId=subject)
        val bible=a.bible ?: return listOf(issue("WORLD_BIBLE_REQUIRED","world_bible_draft",
            "Expand the original prompt into a persistent WorldBible before producing world content. Preserve user constraints and mark author assumptions.","worldsmith_put_world_bible"))
        val malformed=WorldAuthoringModel.validateBible(bible,s.prompt)
        if(malformed.isNotEmpty())return malformed.take(16).map {issue(it.code,"world_bible_draft",it.message,"worldsmith_put_world_bible")}
        val bibleProblems=WorldAuthoringPolicy.bibleProblems(s)
        if(bibleProblems.isNotEmpty()) {
            val repeated=WorldAuthoringPolicy.repeatedBlocker(s,"world_bible")
            val repair=bible.openDecisions.isNotEmpty() || a.bibleReview?.let {it.basisDigest==WorldAuthoringModel.bibleDigest(bible) && it.checks.any {c->c.status==ReviewCheckStatus.BLOCKED}}==true
            return bibleProblems.take(16).map {issue(it.code,"world_bible_review",
                if(repeated)"Three self-check attempts still identify this blocker. Report the concrete unresolved decision and change the relevant design rather than repeating the same review. ${it.message}" else it.message,
                if(repair)"worldsmith_put_world_bible" else "worldsmith_get_authoring_review_context","world_bible",repeated)}
        }
        val plan=s.designPlan ?: return listOf(issue("WORLD_BIBLE_DESIGN_PLAN_REQUIRED","plan",
            "The world design self-check is current. Jointly plan regional ecology, the main line and resource/reward chains, then save the named WorldDesignPlan.","worldsmith_put_world_design_plan"))
        val planErrors=WorldDesignPlans.validate(plan,true,s.requiresPlannedBoss())
        if(planErrors.isNotEmpty())return planErrors.take(16).map {issue(it.code,"plan",it.message,"worldsmith_put_world_design_plan")}
        val production=WorldAuthoringPolicy.productionProblems(s)
        if(production.isNotEmpty())return production.take(24).map {issue(it.code,"module_briefs",it.message,"worldsmith_put_module_briefs")}
        // Let the existing per-module/geometry checks lead until there is actual content to review.
        val inventory=WorldDesignCoverage.draft(s)
        if(inventory.moduleErrors.isNotEmpty() || a.briefs.any {r->r.brief.targets.any {it !in inventory.symbols}})return emptyList()
        return WorldAuthoringPolicy.alignmentProblems(s).take(24).map {problem->
            val subject=subject(s,problem)
            val repeated=subject!=null && WorldAuthoringPolicy.repeatedBlocker(s,subject)
            val brief=a.briefs.find {it.brief.id==subject}?.brief
            val report=a.alignmentReviews.find {it.subjectId==subject}?.takeIf {r->
                r.checks.any {it.status==ReviewCheckStatus.BLOCKED} && WorldAuthoringPolicy.validateReviewForSession(s,r).isEmpty()
            }
            val repair=report!=null || problem.code=="AUTHORING_ALIGNMENT_THEME_TITLE_MISMATCH"
            val affected=brief?.criteria?.filter {c->report?.checks?.any {it.criterionId==c.id && it.status==ReviewCheckStatus.BLOCKED}==true}?.map {it.target}.orEmpty().ifEmpty {brief?.targets.orEmpty()}
            val modules=affected.mapNotNull {target->mapOf("theme" to "theme","terrain" to "terrain","biome" to "biomes","feature" to "features",
                "block" to "blocks","block_item" to "blocks","item" to "items","creature" to "creatures","quest" to "quests","mechanic" to "mechanics","ability" to "abilities").plus(com.wjz.worldsmith.core.story.StoryContentModule.kinds.associateWith { "story" })[target.kind]}.distinct()
            val tool=if(repair) {
                if(problem.code=="AUTHORING_ALIGNMENT_THEME_TITLE_MISMATCH" || modules.isNotEmpty())"worldsmith_put_content_modules" else "worldsmith_put_architecture_draft"
            } else if(subject==null)"worldsmith_put_module_briefs" else "worldsmith_get_authoring_review_context"
            val findings=report?.checks?.filter {it.status==ReviewCheckStatus.BLOCKED}?.take(4)?.joinToString("; ") {"${it.criterionId}: ${it.conclusion} [${it.evidencePaths.joinToString()}]"}.orEmpty()
            issue(problem.code,"content_alignment_review",problem.message+if(findings.isEmpty())"" else " Repair these actual findings before reviewing again: $findings",
                tool,subject,repeated,if(subject==null)96 else 95).let {hint->
                if(!repair)hint else hint.copy(requiresAuthoring=if(tool=="worldsmith_put_architecture_draft")listOf("architecture","structures")
                    else if(problem.code=="AUTHORING_ALIGNMENT_THEME_TITLE_MISMATCH")listOf("modules.theme") else modules.map {"modules.$it"})
            }
        }
    }

    /** All externally exposed mutating production tools pass this gate before running a worker or renderer. */
    fun guard(tool:McpTool,sessions:WorkflowSessions):McpTool {
        val authoringTools=setOf("worldsmith_put_world_bible","worldsmith_review_world_bible","worldsmith_put_module_briefs",
            "worldsmith_review_world_alignment","worldsmith_upgrade_world_authoring")
        val administrative=setOf("worldsmith_cancel_drawing_job","worldsmith_archive_session","worldsmith_resume_session")
        if(tool.readOnly || tool.name in authoringTools || tool.name in administrative)return tool
        val opaque=tool.name in setOf("worldsmith_build_drawing","worldsmith_build_texture","worldsmith_import_texture_file",
            "worldsmith_put_texture_asset","worldsmith_create_pixel_texture")
        val schema=if(!opaque)tool.inputSchema else JsonObject(tool.inputSchema+mapOf("properties" to JsonObject(
            tool.inputSchema["properties"]?.jsonObject.orEmpty()+mapOf("briefIds" to buildJsonObject {
                put("type","array");put("items",McpJson.type("string"));put("maxItems",WorldAuthoringModel.MAX_BRIEFS)
                put("description","For new COMPLETE_WORLD runs, name the current module briefs this drawing or texture implements. Legacy/focused calls remain unchanged.")
            }))))
          return tool.copy(inputSchema=schema,handler={arguments->
              // Brief selection belongs to this orchestration gate, not the worker's strict DTO.
              // Validate it here, then keep the delegate's original argument contract intact.
              val delegatedArguments=if(opaque)JsonObject(arguments-"briefIds") else arguments
              val sid=arguments["sessionId"]?.jsonPrimitive?.contentOrNull
              val session=sid?.let(sessions::find)
              if(session?.authoring==null || session.mode!=WorkflowMode.COMPLETE_WORLD)tool.handler(delegatedArguments)
            else {
                val problems:List<Diagnostic> = if(tool.name=="worldsmith_put_world_design_plan")WorldAuthoringPolicy.bibleProblems(session)
                    else WorldAuthoringPolicy.productionProblems(session)
                if(problems.isNotEmpty())WorldAuthoringMcpService.blocked(session,problems)
                else {
                    val ids=arguments["briefIds"]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty()
                    val known=requireNotNull(session.authoring).briefs.associateBy {it.brief.id}
                    if(opaque && (ids.isEmpty() || ids.size>WorldAuthoringModel.MAX_BRIEFS || ids.distinct().size!=ids.size || ids.any {it !in known}))
                        WorldAuthoringMcpService.blocked(session,listOf(Diagnostic("arguments.briefIds","AUTHORING_BRIEF_SELECTION_REQUIRED",DiagnosticSeverity.ERROR,
                            "Name at least one current owning ModuleBrief in briefIds before building this asset")),"worldsmith_get_world_bible")
                    else {
                        val targets=argumentTargets(tool.name,arguments)+ids.flatMap {known[it]?.brief?.targets.orEmpty()}
                        val targetProblems=if(targets.isEmpty())emptyList() else WorldAuthoringPolicy.productionProblems(session,targets.toSet())
                          if(targetProblems.isNotEmpty())WorldAuthoringMcpService.blocked(session,targetProblems,"worldsmith_put_module_briefs") else tool.handler(delegatedArguments)
                    }
                }
            }
        })
    }

    private fun argumentTargets(tool:String,a:JsonObject):Set<ContentKey> = buildSet {
        when(tool) {
            "worldsmith_build_creature" -> a["recipe"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.let {add(ContentKey("creature",it))}
            "worldsmith_put_structure" -> a["structure"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.let {add(ContentKey("structure",it))}
            "worldsmith_put_architecture_draft" -> a["structures"]?.jsonArray?.forEach {it.jsonObject["id"]?.jsonPrimitive?.content?.let {id->add(ContentKey("structure",id))}}
            "worldsmith_put_content_modules" -> a["modules"]?.jsonObject?.forEach { (module,raw)->
                val document=raw.jsonObject
                when(module) {
                    "terrain" -> add(ContentKey("terrain","main"))
                    "story" -> com.wjz.worldsmith.core.story.StoryContentModule.collections.forEach { (kind,collection) -> (document[collection] as? JsonArray).orEmpty().forEach { v -> (v as? JsonObject)?.get("id")?.jsonPrimitive?.content?.let { add(ContentKey(kind,it)) } } }
                    "theme" -> add(ContentKey("theme",document["id"]?.jsonPrimitive?.content ?: "main"))
                    else -> {
                        val kind=mapOf("biomes" to "biome","features" to "feature","blocks" to "block","items" to "item","creatures" to "creature","quests" to "quest","mechanics" to "mechanic","abilities" to "ability")[module]
                        if(kind!=null)document[if(module=="abilities") "programs" else module]?.jsonArray?.forEach {it.jsonObject["id"]?.jsonPrimitive?.content?.let {id->add(ContentKey(kind,id))}}
                    }
                }
            }
        }
    }
}
