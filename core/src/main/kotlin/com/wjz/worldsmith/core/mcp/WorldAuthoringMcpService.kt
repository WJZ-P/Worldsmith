package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import com.wjz.worldsmith.core.validation.Diagnostic
import kotlinx.serialization.json.*

/** Session-only world design tools. Reports are supplied by the authoring AI, never invented by the Mod. */
class WorldAuthoringMcpService(private val sessions: WorkflowSessions) {
    fun tools(): List<McpTool> {
        val str=McpJson.type("string");val obj=McpJson.type("object");val integer=McpJson.type("integer")
        val sid=mapOf("sessionId" to str);val cas=sid+mapOf("expectedRevision" to integer)
        return listOf(
            McpTool("worldsmith_get_world_bible","Read the persistent world design","Read the authoring-only WorldBible, its rendered Markdown, source references and current review status. Does not load world resources.",
                McpJson.schema(sid+mapOf("format" to str,"summary" to McpJson.type("boolean"),"nodeIds" to McpJson.array()),listOf("sessionId")),true,handler=::read),
            McpTool("worldsmith_put_world_bible","Save the world's creative source of truth","Replace the structured WorldBible at expectedRevision. Changes stale relevant briefs/reviews while preserving completed assets. The original user prompt remains immutable.",
                McpJson.schema(cas+mapOf("bible" to obj),listOf("sessionId","expectedRevision","bible")),false,handler=::putBible),
            McpTool("worldsmith_review_world_bible","Record the AI world-design self-check","Record an evidence-bearing authoring AI review of the current WorldBible. Use get_authoring_review_context first. Passing self-check continues automatically; this is not user approval.",
                McpJson.schema(cas+mapOf("review" to obj),listOf("sessionId","expectedRevision","review")),false,handler={review(it,true)}),
            McpTool("worldsmith_put_module_briefs","Save derived content briefs","Atomically merge ModuleBriefs by id, with optional explicit removeIds. The server stamps current source digests. Preserve other briefs; reference existing world facts rather than inventing another lore source.",
                McpJson.schema(cas+mapOf("briefs" to buildJsonObject {put("type","array");put("items",obj);put("maxItems",WorldAuthoringModel.MAX_BRIEFS)},"removeIds" to McpJson.array()),listOf("sessionId","expectedRevision","briefs")),false,handler=::putBriefs),
            McpTool("worldsmith_get_authoring_review_context","Read exact self-check inputs","Return current source/content digests, required check IDs and available evidence pointers for subjectId world_bible or a brief id. Evidence and references do not themselves prove narrative quality.",
                McpJson.schema(sid+mapOf("subjectId" to str),listOf("sessionId","subjectId")),true,handler={a->
                    val s=session(a);require(s.authoring!=null){"This is a legacy/focused session; explicitly upgrade a complete-world draft to use world-design reviews"}
                    McpToolResult.success(JsonObject(WorldAuthoringPolicy.reviewContext(s,McpJson.string(a,"subjectId"))+
                        mapOf("originalPrompt" to JsonPrimitive(s.prompt),"semanticCorrectnessProven" to JsonPrimitive(false))))
                }),
            McpTool("worldsmith_review_world_alignment","Review actual content against its world brief","Record AI checks that cite current actual module fields/assets and compare them to the brief and WorldBible. A report is stale after its reviewed source or content changes.",
                McpJson.schema(cas+mapOf("subjectId" to str,"review" to obj),listOf("sessionId","expectedRevision","subjectId","review")),false,handler={review(it,false)}),
            McpTool("worldsmith_upgrade_world_authoring","Opt a legacy complete-world draft into the new workflow","Explicitly enable world-design authoring for an existing COMPLETE_WORLD session. Preserve its prompt, modules, assets and jobs; supplement missing provenance rather than regenerate content. Published packages are not rewritten.",
                McpJson.schema(cas,listOf("sessionId","expectedRevision")),false,handler=::upgrade),
        )
    }

    private fun session(a:JsonObject)=requireNotNull(sessions.find(McpJson.string(a,"sessionId"))) {"Unknown authoring session"}
    private fun current(a:JsonObject):WorkflowSession=session(a).also {
        require(!it.archived){"Resume this archived draft first"}
        require(it.revision==a.getValue("expectedRevision").jsonPrimitive.long){"DRAFT_REVISION_CONFLICT: current revision ${it.revision}"}
        require(it.mode==WorkflowMode.COMPLETE_WORLD){"Focused modes do not require the full world-design workflow"}
    }
    private fun state(s:WorkflowSession)=requireNotNull(s.authoring){"Explicitly call worldsmith_upgrade_world_authoring for this legacy complete-world draft"}
    private fun save(s:WorkflowSession,state:WorldAuthoringState)=requireNotNull(sessions.putAuthoring(s.id,s.revision,state)){"Draft is no longer active"}

    private fun read(a:JsonObject):McpToolResult {
        val s=session(a);val state=s.authoring;val bible=state?.bible
        val format=a["format"]?.jsonPrimitive?.content ?: "json";require(format in setOf("json","markdown")){"format is json or markdown"}
        val ids=a["nodeIds"]?.jsonArray?.map {it.jsonPrimitive.content}
        require(ids==null || ids.size<=WorldAuthoringModel.MAX_NODES && ids.distinct().size==ids.size){"Select distinct bounded node ids"}
        require(ids==null || bible!=null && ids.all {id->bible.nodes.any {it.id==id}}){"Unknown world-design node"}
        val projected=if(ids==null)bible else bible?.copy(nodes=bible.nodes.filter {it.id in ids})
        return McpToolResult.success(buildJsonObject {
            summary(s).forEach { (k,v)->put(k,v) }
            put("originalPrompt",s.prompt)
            put("partial",ids!=null);if(ids!=null)put("selectedNodeIds",McpJson.encode(ids))
            if(bible!=null){
                put("bibleDigest",WorldAuthoringModel.bibleDigest(bible));put("referenceIds",McpJson.encode(WorldAuthoringModel.references(bible).keys.sorted()))
                put("title",bible.title)
                put("nodeIndex",JsonArray(requireNotNull(projected).nodes.map {node->buildJsonObject {
                    put("id",node.id);put("kind",node.kind.name);put("title",node.title)
                }}))
                if(a["summary"]?.jsonPrimitive?.boolean!=true) {
                    if(format=="markdown")put("markdown",WorldAuthoringModel.markdown(requireNotNull(projected)))
                    else put("bible",McpJson.encode(projected))
                    put("briefs",McpJson.encode(state.briefs));put("bibleReview",McpJson.encode(state.bibleReview));put("alignmentReviews",McpJson.encode(state.alignmentReviews))
                }
            }
        })
    }

    private fun putBible(a:JsonObject):McpToolResult {
        require(a.getValue("bible").toString().toByteArray(Charsets.UTF_8).size<=WorldAuthoringModel.MAX_BIBLE_BYTES){"WorldBible document exceeds 512 KiB"}
        val s=current(a);val old=state(s);val bible=McpJson.decode<WorldBible>(a.getValue("bible"))
        val problems=WorldAuthoringModel.validateBible(bible,s.prompt)
        if(problems.isNotEmpty())return blocked(s,problems,"worldsmith_put_world_bible")
        val next=if(old.bible==bible)old else old.copy(bible=bible,bibleRevision=old.bibleRevision+
            if(old.bible?.let(WorldAuthoringModel::bibleDigest)==WorldAuthoringModel.bibleDigest(bible))0 else 1)
        return McpToolResult.success(summary(save(s,next)))
    }

    private fun putBriefs(a:JsonObject):McpToolResult {
        require(a.getValue("briefs").toString().toByteArray(Charsets.UTF_8).size<=WorldAuthoringModel.MAX_BRIEF_EDIT_BYTES){"ModuleBrief edit exceeds 1 MiB"}
        val s=current(a);val old=state(s)
        val problems=WorldAuthoringPolicy.bibleProblems(s)
        if(problems.isNotEmpty())return blocked(s,problems)
        require(s.designPlan!=null){"Save the named WorldDesignPlan before deriving module briefs"}
        val updates=McpJson.decode<List<ModuleBrief>>(a.getValue("briefs"))
        require(updates.size<=WorldAuthoringModel.MAX_BRIEFS && updates.map {it.id}.distinct().size==updates.size){"Use distinct bounded briefs"}
        val remove=a["removeIds"]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty()
        require(remove.size<=WorldAuthoringModel.MAX_BRIEFS && remove.distinct().size==remove.size && remove.none {id->updates.any {it.id==id}}){"Use distinct removals, separate from updates"}
        val existing=old.briefs.filter {it.brief.id !in remove}.associateBy {it.brief.id}.toMutableMap()
        updates.forEach {existing[it.id]=BriefRecord(it,old.bibleRevision,"")}
        val provisional=old.copy(briefs=existing.values.toList())
        val errors=WorldAuthoringModel.validateBriefs(provisional.briefs.map {it.brief},requireNotNull(old.bible))
        if(errors.isNotEmpty())return blocked(s,errors,"worldsmith_put_module_briefs")
        val changed=updates.map {it.id}.toSet()
        val next=provisional.copy(briefs=provisional.briefs.map {record->
            if(record.brief.id in changed)record.copy(basisDigest=WorldAuthoringPolicy.basisDigest(provisional,record.brief)) else record
        },alignmentReviews=old.alignmentReviews.filter {it.subjectId !in remove})
        return McpToolResult.success(summary(save(s,next)))
    }

    private fun review(a:JsonObject,bible:Boolean):McpToolResult {
        require(a.getValue("review").toString().toByteArray(Charsets.UTF_8).size<=WorldAuthoringModel.MAX_REVIEW_BYTES){"Authoring review exceeds 512 KiB"}
        val s=current(a);val old=state(s);val report=McpJson.decode<AuthoringReview>(a.getValue("review"))
        val subject=if(bible)"world_bible" else McpJson.string(a,"subjectId")
        require(report.subjectId==subject && (bible || subject!="world_bible")){"Review subject must match the requested draft"}
        val problems=WorldAuthoringPolicy.validateReviewForSession(s,report)
        if(problems.isNotEmpty())return blocked(s,problems,"worldsmith_get_authoring_review_context",subject)
        val previous=if(bible)old.bibleReview else old.alignmentReviews.find {it.subjectId==subject}
        if(previous==report)return McpToolResult.success(summary(s))
        require(previous?.id!=report.id){"Use a new review id when changing its evidence or result"}
        val ctx=WorldAuthoringPolicy.reviewContext(s,subject)
        val input=ctx.getValue("expectedInputDigest").jsonPrimitive.content
        val blockedChecks=report.checks.filter {it.status==ReviewCheckStatus.BLOCKED}.sortedBy {it.criterionId}
        val retained=old.reviewAttempts.filter {it.subjectId!=subject || it.inputDigest==input}.toMutableList()
        if(blockedChecks.isEmpty())retained.removeAll {it.subjectId==subject}
        else {
            // The criterion identifies the blocker; changing review IDs or paraphrasing it does not reset the budget.
            val issues=blockedChecks.map {DrawSnapshotCodec.hash(it.criterionId.toByteArray(Charsets.UTF_8))}.toSet()
            retained.removeAll {it.subjectId==subject && it.issueDigest !in issues}
            issues.forEach {issue->
                val at=retained.indexOfFirst {it.subjectId==subject && it.inputDigest==input && it.issueDigest==issue}
                if(at>=0)retained[at]=retained[at].copy(count=(retained[at].count+1).coerceAtMost(3))
                else retained+=ReviewAttempt(subject,input,issue,1)
            }
        }
        require(retained.size<=WorldAuthoringModel.MAX_REVIEW_ATTEMPTS){"Too many unresolved distinct review issues; repair the existing findings first"}
        val next=if(bible)old.copy(bibleReview=report,reviewAttempts=retained) else old.copy(
            alignmentReviews=old.alignmentReviews.filter {it.subjectId!=subject}+report,reviewAttempts=retained)
        return McpToolResult.success(summary(save(s,next)))
    }

    private fun upgrade(a:JsonObject):McpToolResult {
        val s=current(a)
        return McpToolResult.success(summary(if(s.authoring==null)save(s,WorldAuthoringState()) else s))
    }

    companion object {
        fun summary(s:WorkflowSession):JsonObject=buildJsonObject {
            put("sessionId",s.id);put("revision",s.revision);put("authoringContractVersion",s.authoring?.contractVersion ?: 0)
            put("legacyAuthoring",s.authoring==null);put("bibleRevision",s.authoring?.bibleRevision ?: 0)
            put("biblePresent",s.authoring?.bible!=null);put("briefCount",s.authoring?.briefs?.size ?: 0)
            put("automaticAfterAiReview",s.authoring!=null);put("reviewSource","AUTHORING_AI");put("userApprovalClaimed",false)
            put("worldBibleStoredInPackage",false);put("runtimePackFormat",6)
            put("capabilityContract",WorldAuthoringModel.CAPABILITY_CONTRACT)
            val next=WorldAuthoringFlow.issues(s).firstOrNull()
            put("productionReady",WorldAuthoringPolicy.productionProblems(s).isEmpty())
            put("authoringReady",s.authoring!=null && WorldAuthoringPolicy.alignmentProblems(s).isEmpty());put("nextTool",next?.nextTool ?: "worldsmith_get_generation_progress")
            put("nextArguments",next?.arguments ?: buildJsonObject {put("sessionId",s.id)})
            if(next!=null){put("nextInstruction",next.message);put("requiresUserAction",next.requiresUserAction)}
        }
        fun blocked(s:WorkflowSession,diagnostics:List<Diagnostic>,nextTool:String?=null,subjectId:String?=null):McpToolResult {
            val next=WorldAuthoringFlow.issues(s).firstOrNull()
            return McpToolResult.error("World authoring needs its current source and evidence repaired",buildJsonObject {
                put("sessionId",s.id);put("revision",s.revision);put("valid",false);put("complete",false)
                put("diagnostics",McpJson.encode(diagnostics.take(32)))
                put("nextTool",nextTool ?: next?.nextTool ?: "worldsmith_get_generation_progress")
                put("nextArguments",if(nextTool!=null)WorldAuthoringFlow.arguments(s,nextTool,subjectId) else next?.arguments ?: buildJsonObject {put("sessionId",s.id)})
                put("requiresUserAction",next?.requiresUserAction ?: false)
            })
        }
    }
}
