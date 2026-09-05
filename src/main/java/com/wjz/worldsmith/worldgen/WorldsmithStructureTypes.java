package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

public final class WorldsmithStructureTypes {
    private static StructureType<WorldsmithTemplateStructure> template;
    private static StructurePieceType piece;
    private WorldsmithStructureTypes() {}

    public static synchronized void initialize() {
        if(template!=null)return;
        template=Registry.register(BuiltInRegistries.STRUCTURE_TYPE, Worldsmith.id("template"), ()->WorldsmithTemplateStructure.CODEC);
        piece=Registry.register(BuiltInRegistries.STRUCTURE_PIECE,Worldsmith.id("template_piece"),WorldsmithTemplatePiece::new);
    }
    public static StructureType<WorldsmithTemplateStructure> template() { initialize();return template; }
    public static StructurePieceType piece() { initialize();return piece; }
}
