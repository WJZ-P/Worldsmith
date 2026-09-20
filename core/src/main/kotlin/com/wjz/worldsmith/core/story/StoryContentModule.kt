package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.JsonObject

/** One installed domain, with real cross-domain references rather than prose-only identities. */
object StoryContentModule : WorldContentModule {
    val kinds = listOf("story_fact","place","character","dialogue","knowledge","trade","soundscape","story_projection")
    val collections = linkedMapOf("story_fact" to "facts","place" to "places","character" to "characters","dialogue" to "dialogues","knowledge" to "knowledge","trade" to "trades","soundscape" to "soundscapes","story_projection" to "projections")
    override val descriptor = ContentModuleDescriptor("story",kinds,listOf(2),listOf("structures","creatures","items","abilities","blocks"),
        listOf(ContentRequirement("story.runtime",1,ContentLifecycle.WORLD_BINDING),ContentRequirement("story.client",1,ContentLifecycle.CLIENT_RESOURCES)),
        "Scoped persistent facts, actual place/character anchors, conditional dialogue, knowledge, atomic trades and soundscapes")
    @JvmStatic fun factReferences(ref: StoryFactRef, library: StoryLibrary, path: String): List<ContentReference> = buildList {
        add(ContentReference(ContentKey("story_fact",ref.id),"$path.id"))
        ref.subject?.let { subject -> when(library.facts.firstOrNull { it.id==ref.id }?.scope) {
            StoryFactScope.CHARACTER -> add(ContentReference(ContentKey("character",subject),"$path.subject"))
            StoryFactScope.PLACE -> add(ContentReference(ContentKey("place",subject),"$path.subject"))
            else -> Unit
        } }
    }
    @JvmStatic fun conditionReferences(condition: StoryCondition, library: StoryLibrary, path: String): List<ContentReference> = StoryConditions.references(condition).flatMap { factReferences(it,library,path) }
    @JvmStatic fun changeReferences(changes: List<StoryFactChange>, library: StoryLibrary, path: String): List<ContentReference> = changes.flatMapIndexed { i,c -> factReferences(c.fact,library,"$path[$i].fact") }
    override fun describe(document: JsonObject): ContentContribution {
        val l=WorldsmithJson.format.decodeFromJsonElement(StoryLibrary.serializer(),document)
        fun ref(kind: String,id: String,path: String)=ContentReference(ContentKey(kind,id),path)
        fun condition(c: StoryCondition,p: String)=conditionReferences(c,l,p)
        fun changes(c: List<StoryFactChange>,p: String)=changeReferences(c,l,p)
        fun item(v: StoryItemAmount,p: String): ContentReference? = when {
            v.item.startsWith("worldsmith:item/") -> ref("item",v.item.removePrefix("worldsmith:item/"),p)
            v.item.startsWith("worldsmith:content/") -> ref("block_item",v.item.removePrefix("worldsmith:content/"),p)
            else -> null
        }
        val entries=buildList {
            l.facts.forEachIndexed { i,v -> add(ContentEntry(ContentKey("story_fact",v.id),"story","story.facts[$i]")) }
            l.places.forEachIndexed { i,v -> val p="story.places[$i]";add(ContentEntry(ContentKey("place",v.id),"story",p,listOf(ref("structure",v.structure,"$p.structure"))+listOfNotNull(v.soundscape?.let { ref("soundscape",it,"$p.soundscape") })+condition(v.discoverWhen,"$p.discoverWhen")+changes(v.onDiscover,"$p.onDiscover"))) }
            l.characters.forEachIndexed { i,v -> val p="story.characters[$i]";add(ContentEntry(ContentKey("character",v.id),"story",p,listOf(ref("creature",v.creature,"$p.creature"),ref("place",v.place,"$p.place"))+listOfNotNull(v.dialogue?.let { ref("dialogue",it,"$p.dialogue") })+condition(v.spawnWhen,"$p.spawnWhen")+changes(v.onDeath,"$p.onDeath")+v.routines.flatMapIndexed { j,r -> condition(r.condition,"$p.routines[$j].condition") })) }
            l.dialogues.forEachIndexed { i,v -> val p="story.dialogues[$i]";add(ContentEntry(ContentKey("dialogue",v.id),"story",p,v.nodes.flatMapIndexed { j,n ->
                val at="$p.nodes[$j]";condition(n.condition,"$at.condition")+n.options.flatMapIndexed { k,o -> val op="$at.options[$k]";condition(o.condition,"$op.condition")+changes(o.changes,"$op.changes")+listOfNotNull(o.trade?.let { ref("trade",it,"$op.trade") },o.program?.let { ref("ability",it,"$op.program") }) }
            })) }
            l.knowledge.forEachIndexed { i,v -> val p="story.knowledge[$i]";add(ContentEntry(ContentKey("knowledge",v.id),"story",p,condition(v.discoverWhen,"$p.discoverWhen"))) }
            l.trades.forEachIndexed { i,v -> val p="story.trades[$i]";add(ContentEntry(ContentKey("trade",v.id),"story",p,
                condition(v.condition,"$p.condition")+changes(v.changes,"$p.changes")+v.inputs.mapIndexedNotNull { j,a -> item(a,"$p.inputs[$j].item") }+v.outputs.mapIndexedNotNull { j,a -> item(a,"$p.outputs[$j].item") },
                nativeReferences=(v.inputs+v.outputs).filter { item(it,p)==null }.map { NativeContentReference("item",it.item) }.distinct())) }
            l.soundscapes.forEachIndexed { i,v -> val p="story.soundscapes[$i]";add(ContentEntry(ContentKey("soundscape",v.id),"story",p,v.layers.flatMapIndexed { j,a -> condition(a.condition,"$p.layers[$j].condition") },nativeReferences=v.layers.map { NativeContentReference("sound_event",it.sound) }.distinct())) }
            l.projections.forEachIndexed { i,v ->
                val p="story.projections[$i]";val states=v.blocks.flatMap { listOf(it.expected,it.desired) }
                add(ContentEntry(ContentKey("story_projection",v.id),"story",p,
                    listOf(ref("place",v.place,"$p.place"))+condition(v.condition,"$p.condition")+changes(v.onApplied,"$p.onApplied")+
                        states.filter { it.block.startsWith("worldsmith:content/") }.map { ref("block",it.block.removePrefix("worldsmith:content/"),"$p.blocks") },
                    nativeReferences=states.filterNot { it.block.startsWith("worldsmith:content/") }.map { NativeContentReference("block",it.block) }.distinct()))
            }
        }
        return ContentContribution(entries,diagnostics=StoryValidation.validate(l).map { it.copy(path="story.${it.path}") })
    }
}
