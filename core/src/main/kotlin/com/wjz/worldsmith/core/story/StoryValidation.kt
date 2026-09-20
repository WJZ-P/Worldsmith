package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValue
import com.wjz.worldsmith.core.content.QuestValidation
import com.wjz.worldsmith.core.content.WorldContentRegistry
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Structural and semantic checks independent of the native world. Cross-domain IDs resolve in the content registry. */
object StoryValidation {
    const val MAX_DEFINITIONS = 512
    const val MAX_PLACES = 128
    const val MAX_KNOWLEDGE = 128
    const val MAX_CHARACTERS = 256
    const val MAX_DIALOGUES = 128
    const val MAX_TRADES = 256
    const val MAX_SOUNDSCAPES = 128
    const val MAX_PROJECTIONS = 64
    const val MAX_PROJECTION_BLOCKS = 32
    private val ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    @JvmStatic fun validId(id: String): Boolean = ID.matches(id) && ".." !in id && !id.endsWith('.')
    @JvmStatic fun primitive(value: AbilityValue): Boolean = value is AbilityValue.BoolValue || value is AbilityValue.NumberValue || value is AbilityValue.TextValue
    @JvmStatic fun matches(fact: StoryFact, value: AbilityValue): Boolean = when(fact.type) {
        StoryFactType.BOOL -> value is AbilityValue.BoolValue
        StoryFactType.NUMBER -> value is AbilityValue.NumberValue && value.value in fact.min..fact.max
        StoryFactType.TEXT -> value is AbilityValue.TextValue && value.value.length <= 4096 && value.value.none { it.isISOControl() && it != '\n' }
    }
    private fun error(path: String, message: String, code: String = "story.invalid") = Diagnostic(path,code,DiagnosticSeverity.ERROR,message)
    private fun reference(ref: StoryFactRef, library: StoryLibrary, path: String): List<Diagnostic> = buildList {
        val fact = library.facts.firstOrNull { it.id == ref.id }
        if(fact == null) { add(error(path,"Unknown fact '${ref.id}'")); return@buildList }
        when(fact.scope) {
            StoryFactScope.WORLD, StoryFactScope.PLAYER -> if(ref.subject != null) add(error(path,"World/player facts have no subject"))
            StoryFactScope.CHARACTER -> if(ref.subject != null && library.characters.none { it.id == ref.subject }) add(error(path,"Unknown character subject '${ref.subject}'"))
            StoryFactScope.PLACE -> if(ref.subject != null && library.places.none { it.id == ref.subject }) add(error(path,"Unknown place subject '${ref.subject}'"))
        }
    }
    @JvmStatic @JvmOverloads fun validateCondition(condition: StoryCondition, library: StoryLibrary, path: String = "condition"): List<Diagnostic> = buildList {
        val structural=StoryConditions.validate(condition,path); addAll(structural); if(structural.isNotEmpty()) return@buildList
        fun visit(c: StoryCondition, p: String) { when(c) {
            StoryCondition.Always -> Unit
            is StoryCondition.All -> c.conditions.forEachIndexed { i,v -> visit(v,"$p.conditions[$i]") }
            is StoryCondition.Any -> c.conditions.forEachIndexed { i,v -> visit(v,"$p.conditions[$i]") }
            is StoryCondition.Not -> visit(c.condition,"$p.condition")
            is StoryCondition.Compare -> {
                addAll(reference(c.fact,library,"$p.fact"))
                library.facts.firstOrNull { it.id==c.fact.id }?.let { f ->
                    // Comparisons may lie outside the numeric domain (a useful always-false bound), but types must agree.
                    if(!matches(f.copy(min=-1e9,max=1e9),c.value)) add(error("$p.value","Comparison value must match declared fact type"))
                }
            }
        } }
        visit(condition,path)
    }
    @JvmStatic @JvmOverloads fun validateChanges(changes: List<StoryFactChange>, library: StoryLibrary, path: String = "changes"): List<Diagnostic> = buildList {
        if(changes.size > 32) add(error(path,"At most 32 fact changes per transaction"))
        if(changes.map { it.fact }.distinct().size != changes.size) add(error(path,"A transaction writes each fact subject only once"))
        changes.take(32).forEachIndexed { i,c ->
            val p="$path[$i]"; addAll(reference(c.fact,library,"$p.fact"))
            if(!primitive(c.value)) add(error("$p.value","Fact changes require primitive values"))
            library.facts.firstOrNull { it.id==c.fact.id }?.let { f ->
                if(c.mode == StoryChangeMode.ADD) {
                    if(f.type != StoryFactType.NUMBER || c.value !is AbilityValue.NumberValue) add(error(p,"ADD requires a numeric fact and delta"))
                } else if(!matches(f,c.value)) add(error("$p.value","Value must satisfy the declared fact type and bounds"))
            }
        }
    }
    @JvmStatic fun validate(library: StoryLibrary): List<Diagnostic> = buildList {
        fun check(ok: Boolean,p: String,m: String) { if(!ok) add(error(p,m)) }
        fun text(v: String,p: String,max: Int=4096,empty: Boolean=false,singleLine: Boolean=false) = check((empty || v.isNotBlank()) && v.length<=max && v.none { it.isISOControl() && (singleLine || it!='\n') },p,"Use bounded readable text")
        fun ids(values: List<String>,p: String,limit: Int=MAX_DEFINITIONS) { check(values.size<=limit,p,"Definition budget exceeded: $limit"); check(values.all(::validId),p,"Use stable lowercase local IDs"); check(values.distinct().size==values.size,p,"Duplicate IDs") }
        fun condition(c: StoryCondition,p: String,global: Boolean=false) {
            addAll(validateCondition(c,library,p))
            if(global && StoryConditions.references(c).any { r -> library.facts.any { it.id==r.id && it.scope==StoryFactScope.PLAYER } }) add(error(p,"World character schedules/spawning cannot depend on an arbitrary observing player"))
        }
        fun changes(c: List<StoryFactChange>,p: String) { addAll(validateChanges(c,library,p)) }
        fun local(id: String,p: String) = check(WorldContentRegistry.validName(id),p,"Use a normalized content ID")
        check(library.schemaVersion==2,"schemaVersion","Story schema must be 2")
        ids(library.facts.map { it.id },"facts"); ids(library.places.map { it.id },"places",MAX_PLACES); ids(library.characters.map { it.id },"characters",MAX_CHARACTERS)
        ids(library.dialogues.map { it.id },"dialogues",MAX_DIALOGUES); ids(library.knowledge.map { it.id },"knowledge",MAX_KNOWLEDGE); ids(library.trades.map { it.id },"trades",MAX_TRADES); ids(library.soundscapes.map { it.id },"soundscapes",MAX_SOUNDSCAPES)
        ids(library.projections.map { it.id },"projections",MAX_PROJECTIONS)
        check(library.facts.size+library.places.size+library.characters.size+library.dialogues.size+library.knowledge.size+library.trades.size+library.soundscapes.size+library.projections.size<=2048,"story","At most 2048 total definitions")
        library.facts.take(MAX_DEFINITIONS).forEachIndexed { i,f ->
            val p="facts[$i]"; check(f.min.isFinite() && f.max.isFinite() && f.min>=-1e6 && f.max<=1e6 && f.min<=f.max,p,"Numeric bounds must be ordered within -1e6..1e6")
            check(matches(f,f.initial),"$p.initial","Initial value must match declared fact type and bounds")
        }
        library.places.take(MAX_PLACES).forEachIndexed { i,p ->
            val at="places[$i]"; text(p.name,"$at.name",160,singleLine=true);text(p.description,"$at.description",2048);text(p.clue,"$at.clue",1024,true);local(p.structure,"$at.structure")
            check(p.discoveryRadius in 1..128,"$at.discoveryRadius","Discovery radius must be 1..128")
            check(p.soundscape==null || library.soundscapes.any { it.id==p.soundscape },"$at.soundscape","Unknown soundscape")
            condition(p.discoverWhen,"$at.discoverWhen");changes(p.onDiscover,"$at.onDiscover")
        }
        library.characters.take(MAX_CHARACTERS).forEachIndexed { i,c ->
            val p="characters[$i]"; text(c.name,"$p.name",160,singleLine=true);local(c.creature,"$p.creature")
            check(library.places.any { it.id==c.place },"$p.place","Unknown home place");check(c.dialogue==null || library.dialogues.any { it.id==c.dialogue },"$p.dialogue","Unknown dialogue")
            check(c.respawnTicks==null || c.respawnTicks in 20..1728000,"$p.respawnTicks","Respawn delay must be 20..1728000 or null")
            condition(c.spawnWhen,"$p.spawnWhen",true);changes(c.onDeath,"$p.onDeath");ids(c.routines.map { it.id },"$p.routines",32)
            val occupied=BooleanArray(24000)
            c.routines.take(32).forEachIndexed { j,r ->
                val at="$p.routines[$j]";check(r.startTick in 0..23999 && r.endTick in 0..23999 && r.startTick!=r.endTick,at,"Distinct start/end ticks must be in 0..23999; ranges may wrap midnight")
                val o=r.offset;check(listOf(o.x,o.y,o.z).all { it in -24..24 } && o.x.toLong()*o.x+o.y.toLong()*o.y+o.z.toLong()*o.z<=576, "$at.offset","Routine destinations must be within 24 blocks of home")
                check(r.speed.isFinite() && r.speed in 0.1..2.0,"$at.speed","Speed must be 0.1..2");text(r.activity,"$at.activity",256);condition(r.condition,"$at.condition",true)
                if(r.startTick in 0..23999 && r.endTick in 0..23999 && r.startTick!=r.endTick) {
                    val ticks=if(r.startTick<r.endTick) (r.startTick until r.endTick).toList() else (r.startTick..23999).toList()+(0 until r.endTick).toList()
                    check(ticks.none { occupied[it] },at,"Routine time windows must not overlap");ticks.forEach { occupied[it]=true }
                }
            }
        }
        library.dialogues.take(MAX_DIALOGUES).forEachIndexed { i,d ->
            val p="dialogues[$i]";ids(d.nodes.map { it.id },"$p.nodes",128);check(d.nodes.isNotEmpty(),"$p.nodes","Dialogue needs a node")
            val by=d.nodes.associateBy { it.id };check(d.start in by,"$p.start","Dialogue start must name a node")
            d.nodes.take(128).forEachIndexed { j,n ->
                val at="$p.nodes[$j]";text(n.text,"$at.text");ids(n.options.map { it.id },"$at.options",8);condition(n.condition,"$at.condition")
                n.options.take(8).forEachIndexed { k,o ->
                    val op="$at.options[$k]";text(o.text,"$op.text",512,singleLine=true);check(o.next==null || o.next in by,"$op.next","Unknown target node")
                    check(o.trade==null || library.trades.any { it.id==o.trade },"$op.trade","Unknown trade");o.program?.let { local(it,"$op.program") }
                    condition(o.condition,"$op.condition");changes(o.changes,"$op.changes")
                    o.trade?.let { trade -> library.trades.firstOrNull { it.id==trade }?.let { changes(o.changes+it.changes,"$op.transactionChanges") } }
                }
            }
            val reached=linkedSetOf<String>(); val queue=ArrayDeque<String>();queue.add(d.start)
            while(queue.isNotEmpty() && reached.size<=128) { val id=queue.removeFirst();if(reached.add(id)) by[id]?.options?.mapNotNull { it.next }?.forEach(queue::addLast) }
            check(by.keys.all { it in reached },p,"Every node must be reachable from start")
            val exits=by.values.filter { it.options.isEmpty() || it.options.any { o -> o.next==null } }.map { it.id }.toMutableSet()
            repeat(by.size.coerceAtMost(128)) { by.values.filter { n -> n.options.any { it.next in exits } }.forEach { exits.add(it.id) } }
            check(by.keys.all { it in exits },p,"Every dialogue node must have a structural route to a closing option")
        }
        library.knowledge.take(MAX_KNOWLEDGE).forEachIndexed { i,k -> val p="knowledge[$i]";text(k.title,"$p.title",160,singleLine=true);text(k.text,"$p.text",8192);condition(k.discoverWhen,"$p.discoverWhen") }
        library.trades.take(MAX_TRADES).forEachIndexed { i,t ->
            val p="trades[$i]";text(t.name,"$p.name",160,singleLine=true);check(t.inputs.size in 1..16 && t.outputs.size in 1..16,p,"Trades require 1..16 input and output entries")
            listOf("inputs" to t.inputs,"outputs" to t.outputs).forEach { (name,items) ->
                check(items.map { it.item }.distinct().size==items.size,"$p.$name","Aggregate duplicate item entries")
                items.take(16).forEachIndexed { j,a -> check(QuestValidation.validItemReference(a.item) && a.count in 1..64,"$p.$name[$j]","Use a canonical item reference and count 1..64") }
            }
            check(t.cooldownTicks in 1..1728000,"$p.cooldownTicks","Trade cooldown must be 1..1728000");check(t.maxUsesPerPlayer in 0..1000000,"$p.maxUsesPerPlayer","Use limit must be 0..1000000")
            condition(t.condition,"$p.condition");changes(t.changes,"$p.changes")
        }
        library.soundscapes.take(MAX_SOUNDSCAPES).forEachIndexed { i,s ->
            val p="soundscapes[$i]";ids(s.layers.map { it.id },"$p.layers",16)
            s.layers.take(16).forEachIndexed { j,l ->
                val at="$p.layers[$j]";check(l.sound.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")) && l.sound.length<=128 && ".." !in l.sound,"$at.sound","Use a registered native sound ID")
                check(l.volume.isFinite() && l.volume in 0f..1f && l.pitch.isFinite() && l.pitch in 0.5f..2f,at,"Volume 0..1 and pitch 0.5..2 must be finite")
                check(l.periodTicks in 20..24000 && l.fadeTicks in 0..1200 && l.fadeTicks<=l.periodTicks && l.priority in -100..100,at,"Invalid sound timing/priority")
                text(l.subtitle,"$at.subtitle",256,true,singleLine=true);condition(l.condition,"$at.condition")
            }
        }
        library.projections.take(MAX_PROJECTIONS).forEachIndexed { i,p ->
            val at="projections[$i]"
            check(library.places.any { it.id==p.place },"$at.place","Projection needs a declared place")
            condition(p.condition,"$at.condition");changes(p.onApplied,"$at.onApplied")
            (StoryConditions.references(p.condition)+p.onApplied.map { it.fact }).forEach { ref ->
                val fact=library.facts.firstOrNull { it.id==ref.id }
                check(fact?.scope in setOf(StoryFactScope.WORLD,StoryFactScope.PLACE) && (ref.subject==null || ref.subject==p.place),at,
                    "Projections read/write WORLD or their own actual PLACE only; no observing player or ambiguous other instance")
            }
            check(p.blocks.size in 1..MAX_PROJECTION_BLOCKS,"$at.blocks","Projections contain 1..32 exact block changes")
            check(p.blocks.map { it.offset }.distinct().size==p.blocks.size,"$at.blocks","Projection offsets must be unique")
            p.blocks.take(MAX_PROJECTION_BLOCKS).forEachIndexed { j,b ->
                val bp="$at.blocks[$j]";val o=b.offset
                check(listOf(o.x,o.y,o.z).all { it in -32..32 } && o.x.toLong()*o.x+o.y.toLong()*o.y+o.z.toLong()*o.z<=1024,"$bp.offset","Projection blocks must be within 32 blocks of the actual marker")
                check(b.expected!=b.desired,bp,"Expected and desired states must differ")
                listOf("expected" to b.expected,"desired" to b.desired).forEach { (field,s) ->
                    check(s.block.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")) && s.block.length<=128 && ".." !in s.block && !s.block.startsWith("worldsmith:content/block/") && !s.block.startsWith("worldsmith:content/item/"),"$bp.$field.block","Use a canonical native block or worldsmith:content/<id> alias")
                    check(s.properties.size<=16 && s.properties.all { (k,v) -> k.matches(Regex("[a-z0-9_]{1,64}")) && v.matches(Regex("[a-z0-9_-]{1,64}")) },"$bp.$field.properties","Use at most 16 native state properties")
                }
            }
        }
    }
    @JvmStatic fun freeze(library: StoryLibrary): StoryLibrary {
        fun <T> list(v: List<T>): List<T> = java.util.List.copyOf(v)
        fun c(v: StoryCondition)=StoryConditions.freeze(v)
        return library.copy(facts=list(library.facts),places=list(library.places.map { it.copy(discoverWhen=c(it.discoverWhen),onDiscover=list(it.onDiscover)) }),
            characters=list(library.characters.map { it.copy(spawnWhen=c(it.spawnWhen),onDeath=list(it.onDeath),routines=list(it.routines.map { r -> r.copy(condition=c(r.condition)) })) }),
            dialogues=list(library.dialogues.map { it.copy(nodes=list(it.nodes.map { n -> n.copy(condition=c(n.condition),options=list(n.options.map { o -> o.copy(condition=c(o.condition),changes=list(o.changes)) })) })) }),
            knowledge=list(library.knowledge.map { it.copy(discoverWhen=c(it.discoverWhen)) }),trades=list(library.trades.map { it.copy(inputs=list(it.inputs),outputs=list(it.outputs),condition=c(it.condition),changes=list(it.changes)) }),
            soundscapes=list(library.soundscapes.map { it.copy(layers=list(it.layers.map { l -> l.copy(condition=c(l.condition)) })) }),
            projections=list(library.projections.map { p -> p.copy(condition=c(p.condition),onApplied=list(p.onApplied),blocks=list(p.blocks.map { b -> b.copy(
                expected=b.expected.copy(properties=java.util.Map.copyOf(b.expected.properties)),desired=b.desired.copy(properties=java.util.Map.copyOf(b.desired.properties))) })) }))
    }
}
