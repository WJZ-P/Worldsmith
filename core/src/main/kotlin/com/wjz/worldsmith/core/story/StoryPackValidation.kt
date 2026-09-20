package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.structure.StructureInteraction
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Marker declarations are placement opportunities, never evidence of a successfully generated instance. */
object StoryPackValidation {
    @JvmStatic fun validate(pack: WorldsmithPack): List<Diagnostic> = buildList {
        fun error(path: String,code: String,message: String) { add(Diagnostic(path,code,DiagnosticSeverity.ERROR,message)) }
        val places=pack.story.places.associateBy { it.id };val characters=pack.story.characters.associateBy { it.id }
        val seenPlaces=mutableSetOf<String>();val seenCharacters=mutableSetOf<String>()
        pack.structures.structures.forEachIndexed { i,s ->
            val blueprints=listOf(s.blueprint to "structures.structures[$i].blueprint")+s.assembly?.pieces.orEmpty().map { (id,b) -> b to "structures.structures[$i].assembly.pieces.$id" }
            blueprints.forEach { (b,p) -> b.interactions.forEachIndexed { j,a -> if(a is StructureInteraction.StoryAnchor) {
                val at="$p.interactions[$j]";val place=places[a.place]
                if(place==null || place.structure!=s.id) error(at,"STORY_ANCHOR_PLACE","Marker must resolve to a place whose structure is '${s.id}'") else if(a.character==null) seenPlaces+=place.id
                a.character?.let { id ->
                    val character=characters[id]
                    if(character==null || character.place!=a.place) error(at,"STORY_ANCHOR_CHARACTER","Character must belong to this marker's place") else seenCharacters+=id
                }
            } } }
        }
        pack.story.places.forEachIndexed { i,p -> if(p.id !in seenPlaces) error("story.places[$i]","STORY_PLACE_UNANCHORED","Place '${p.id}' needs a pure StoryAnchor without character in its declared structure; a resident marker does not establish a place instance") }
        pack.story.characters.forEachIndexed { i,c -> if(c.id !in seenCharacters) error("story.characters[$i]","STORY_CHARACTER_UNANCHORED","Character '${c.id}' needs a typed character StoryAnchor in its home structure") }
    }
}
