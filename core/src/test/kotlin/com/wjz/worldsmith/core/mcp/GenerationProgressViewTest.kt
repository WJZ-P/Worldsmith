package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GenerationProgressViewTest {
    private val sid="a".repeat(32)
    private val texture="b".repeat(64)
    private val handle=ContentAsset(texture,texture,"image/png",3,"missing/on-purpose.blob")
    private fun target(kind:String,id:String,name:String=id)=DesignTarget(ContentKey(kind,id),name,"A concrete role for $id")
    private fun plan(vararg targets:DesignTarget)=WorldDesignPlan(goal="A named world",targets=targets.toList(),links=emptyList())
    private fun session(plan:WorldDesignPlan?=null)=WorkflowSession(sid,"Prompt for the current world",mode=WorkflowMode.STANDALONE,designPlan=plan)
    private fun items(vararg ids:String)=CustomItemLibrary(items=ids.map {CustomItemDefinition(it,"Item $it",texture)})
    private fun itemSession()=session(plan(target("item","first"),target("item","second"))).copy(revision=3,
        contentModules=mapOf("items" to McpJson.encode(items("first","second")).jsonObject),contentAssets=mapOf(texture to handle))
    private fun job(id:String,stage:DrawingJobStage,drawings:List<String> = emptyList(),sessionId:String=sid)=
        DrawingJob(id,sessionId,1,"c".repeat(64),DrawingRequest("Drawing $id",id),stage,drawings,message="Actual ${stage.name} stage")

    @Test fun `biome names and matching ids determine declaration totals rather than extra same kind definitions`() {
        val template=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").biomes.biomes.first()
        val current=session(plan(target("biome","requested","月光林地"),target("biome","later","银色海岸"))).copy(
            contentModules=mapOf("biomes" to McpJson.encode(BiomePlan(biomes=listOf(template.copy(id="requested"),template.copy(id="extra_a"),template.copy(id="extra_b")))).jsonObject))
        val view=GenerationProgressViews.inspect(current)
        assertEquals(1,view.declaredTargets);assertEquals(2,view.totalPlannedTargets)
        assertEquals("已提交目标草稿 1/2",view.overallLabel)
        val biomes=view.categories.single {it.kind=="biome"}
        assertEquals(2,biomes.planned);assertEquals(1,biomes.matchedDeclared);assertEquals(2,biomes.extraDefinitions);assertEquals(3,biomes.declaredTotal)
        assertEquals(listOf("月光林地","银色海岸"),view.targets.map {it.name})
        assertEquals(GenerationTargetState.MISSING,view.targets.single {it.id=="later"}.state)
    }

    @Test fun `duplicate plan keys and Boss subset are never extra progress units`() {
        val creature=CreatureDefinition("warden","Warden",CreatureCategory.HOSTILE,
            CreatureModel(texture,16,16,listOf(CreatureBone("body",cubes=listOf(CreatureCube(CreatureVector(),CreatureVector(1f,1f,1f)))))),
            boss=CreatureBossProfile(listOf(CreatureBossPhase("One",1.0),CreatureBossPhase("Two",0.5,speedMultiplier=1.2))))
        val draft=session(plan(target("creature","warden","守望者"),target("creature","warden","重复声明"),target("quest","defeat"))
            .copy(bosses=listOf(DesignBoss("warden","defeat")))).copy(contentModules=mapOf("creatures" to McpJson.encode(CreatureLibrary(2,listOf(creature))).jsonObject),contentAssets=mapOf(texture to handle))
        val view=GenerationProgressViews.inspect(draft)
        assertEquals(2,view.totalPlannedTargets);assertEquals(1,view.declaredTargets)
        assertEquals(1,view.categories.single {it.kind=="creature"}.planned)
        assertEquals(listOf("守望者","defeat"),view.targets.map {it.name})
        assertFalse(view.categories.any {it.kind=="boss"})
    }

    @Test fun `without a plan denominator remains unknown while actual definitions are visible`() {
        val draft=session().copy(contentModules=mapOf("items" to McpJson.encode(items("token")).jsonObject),contentAssets=mapOf(texture to handle))
        val view=GenerationProgressViews.inspect(draft)
        assertFalse(view.planPresent);assertNull(view.totalPlannedTargets)
        assertTrue(view.categories.all {it.planned==null});assertTrue(view.targets.isEmpty())
        assertEquals(1,view.categories.single {it.kind=="item"}.declaredTotal)
        assertTrue(view.overallLabel.contains("目标总数未知"))
        assertFalse(view.overallLabel.contains("100"))
    }

    @Test fun `declared asset missing frozen and native states stay distinct without PNG or file access`() {
        val draft=session(plan(target("item","token"))).copy(contentModules=mapOf("items" to McpJson.encode(items("token")).jsonObject))
        val missing=GenerationProgressViews.inspect(draft)
        assertEquals(1,missing.declaredTargets)
        assertEquals(GenerationTargetState.NEEDS_ASSET,missing.targets.single().state)
        // Deliberately nonexistent and not PNG data: a read-only projection must inspect handles only.
        val declared=draft.copy(contentAssets=mapOf(texture to handle))
        assertEquals(GenerationTargetState.DECLARED,GenerationProgressViews.inspect(declared).targets.single().state)
        val frozen=declared.copy(packId="d".repeat(64))
        val view=GenerationProgressViews.inspect(frozen)
        assertEquals(GenerationTargetState.FROZEN,view.targets.single().state)
        assertFalse(view.nativeActivationVerified)
        assertTrue(GenerationProgressViews.inspect(frozen.copy(finished=true)).nativeActivationVerified)
        assertEquals(view,WorldsmithJson.decode<GenerationProgressView>(WorldsmithJson.encode(view)))
    }

    @Test fun `only current precise target diagnostics mark repair and module warnings do not paint all rows`() {
        val current=itemSession()
        fun with(diagnostic:Diagnostic,revision:Long=current.revision,inline:List<String> = emptyList())=current.copy(lastWriteFailure=
            PackValidationReceipt.bounded(revision,listOf(diagnostic),"World","",inline))
        val error=Diagnostic("items.items[0].maxStackSize","CONTENT_ITEM_STACK_LIMIT",DiagnosticSeverity.ERROR,"First item's stack limit needs repair")
        val targeted=GenerationProgressViews.inspect(with(error))
        assertEquals(listOf(GenerationTargetState.REPAIR,GenerationTargetState.DECLARED),targeted.targets.map {it.state})
        for(draft in listOf(with(error.copy(severity=DiagnosticSeverity.WARNING)),with(error,revision=2),with(error,inline=listOf("items")),
            with(error.copy(path="items",message="Module-wide check needs attention")))) {
            assertTrue(GenerationProgressViews.inspect(draft).targets.all {it.state==GenerationTargetState.DECLARED})
        }
        val invalidItems=items("first","second").let {it.copy(items=listOf(it.items.first().copy(maxStackSize=0),it.items.last()))}
        val cheap=GenerationProgressViews.inspect(current.copy(contentModules=mapOf("items" to McpJson.encode(invalidItems).jsonObject)))
        assertEquals(listOf(GenerationTargetState.REPAIR,GenerationTargetState.DECLARED),cheap.targets.map {it.state})
    }

    @Test fun `building names and jobs use actual drawing references without compiling or guessing name associations`() {
        val drawing="e".repeat(64)
        // Invalid geometry and absent drawing bytes would fail compilation; the projection is declaration-only.
        val structure=WorldStructureDefinition("observatory",StructureBlueprint(id="tower",size=BuildPos(0,0,0),drawing=StructureDrawingSource(listOf(drawing))),StructurePlacement(emptyList()))
        val draft=session(plan(target("structure","observatory","云顶观星塔"))).copy(structures=mapOf(structure.id to structure))
        val source=job("source",DrawingJobStage.SUCCEEDED,listOf(drawing))
        val waiting=job("waiting",DrawingJobStage.WAITING_APPROVAL).copy(request=DrawingRequest("云顶观星塔","waiting"))
        val view=GenerationProgressViews.inspect(draft,listOf(source,waiting,job("foreign",DrawingJobStage.DRAWING,sessionId="another")))
        val row=view.targets.single()
        assertEquals("云顶观星塔",row.name);assertEquals(GenerationTargetState.DECLARED,row.state)
        assertEquals(listOf("source"),row.jobIds,"Name similarity is not a drawing-to-target reference")
        assertEquals(listOf("waiting","source"),view.jobs.map {it.jobId})
        assertTrue(view.requiresUserAction);assertTrue(view.jobs.first().needsApproval)
        assertEquals(listOf("observatory"),view.jobs.last().structureIds)
        val changed=GenerationProgressViews.inspect(draft,listOf(source.copy(stage=DrawingJobStage.VALIDATING)))
        assertEquals(draft.revision,changed.revision)
        assertEquals("VALIDATING",changed.jobs.single().stage)
        assertEquals(view.declaredTargets,changed.declaredTargets)
    }

    @Test fun `projection budgets targets jobs and issues without inventing a completion ratio`() {
        val oversized=WorldDesignPlan(goal="Oversized plan",targets=List(513) {target("biome","biome_$it")},links=emptyList())
        val draft=session(oversized).copy(contentModules=mapOf("items" to McpJson.encode(items(*(0 until 40).map {"item_$it"}.toTypedArray())).jsonObject))
        val jobs=List(40) {job("job_$it",if(it==0)DrawingJobStage.WAITING_APPROVAL else DrawingJobStage.SUCCEEDED)}
        val view=GenerationProgressViews.inspect(draft,jobs)
        assertEquals(512,view.targets.size);assertTrue(view.targetsTruncated);assertNull(view.totalPlannedTargets)
        assertEquals(32,view.jobs.size);assertTrue(view.jobsTruncated)
        assertEquals("job_0",view.jobs.first().jobId)
        assertEquals(32,view.issues.size);assertTrue(view.issuesTruncated)
        assertTrue(view.requiresUserAction)
    }
}
