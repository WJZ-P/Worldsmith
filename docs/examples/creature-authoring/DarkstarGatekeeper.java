import com.wjz.worldsmith.core.creatureauthoring.CreatureBuilder;
import com.wjz.worldsmith.core.creatureauthoring.CreatureProgram;
import com.wjz.worldsmith.core.content.CreatureCategory;
import com.wjz.worldsmith.core.content.CreatureBoneRole;
import com.wjz.worldsmith.core.content.CreaturePassiveMode;

/** Reusable source example: appearance and current basic melee only, not a new Boss combat system. */
public final class DarkstarGatekeeper implements CreatureProgram {
    @Override public CreatureBuilder create() {
        var c = CreatureBuilder.create("darkstar_gatekeeper", "黯星守门者", CreatureCategory.HOSTILE)
            .atlas(256, 256, 2)
            .themeRole("An ancient dark-stone gate guardian, with bronze bindings and an exposed amethyst heart.")
            .attributes(240, 0.19, 32, 8, 0.7, 2.4f, 4.95f)
            .behavior(CreaturePassiveMode.WANDER, 32, 3.0, 24, 32);

        // Feet end exactly at root Y=24. Head and horns reach approximately five blocks above it.
        c.bone("root", null, 0, 24, 0).end();
        c.bone("pelvis", "root", 0, -32, 0)
            .cube("hipStone", -11, -6, -7, 22, 12, 14, "stone_dark")
            .cube("waistBand", -12, -5, -8, 24, 3, 16, "bronze")
            .cube("frontGuard", -6, 3, -10, 12, 14, 3, "stone_engraved")
            .cube("beltSeal", -3, -3, -10, 6, 5, 3, "amethyst").end();
        c.bone("torso", "pelvis", 0, -12, 0)
            .cube("chestStone", -12, -12, -7, 24, 24, 14, "stone_dark")
            .cube("collar", -11, -14, -7, 22, 3, 14, "bronze")
            .cube("sternumPlate", -7, -10, -9, 14, 16, 3, "bronze")
            .cube("heartCrystal", -4, -7, -12, 8, 10, 5, "amethyst")
            .cube("ribLeft", -12, 2, -9, 4, 7, 3, "stone_engraved")
            .cube("ribRight", 8, 2, -9, 4, 7, 3, "stone_engraved")
            .cube("backSpine", -3, -10, 7, 6, 22, 4, "bronze")
            .cube("backPlate", -10, -8, 7, 20, 16, 2, "stone_engraved").end();
        c.bone("head", "torso", 0, -18, -2).role(CreatureBoneRole.HEAD)
            .cube("helmet", -7, -9, -6, 14, 13, 12, "stone_dark")
            .cube("brow", -8, -6, -7, 16, 3, 4, "bronze")
            .cube("faceMask", -5, -3, -7, 10, 6, 3, "stone_engraved")
            .cube("eyeLeft", -5, -3, -8, 3, 1, 1, "amethyst")
            .cube("eyeRight", 2, -3, -8, 3, 1, 1, "amethyst")
            .cube("chinSeal", -2, 1, -9, 4, 4, 2, "bronze").end();
        c.bone("hornL", "head", -5, -7, 0).rotation(0, 0, -18)
            .cube("rootBand", -3, -2, -3, 6, 4, 6, "bronze")
            .cube("horn", -2, -10, -2, 4, 10, 4, "stone_dark")
            .cube("tip", -1, -13, -1, 2, 4, 2, "amethyst").end();
        c.mirrorSubtree("hornL", "hornR");

        // Arms sit outside the face silhouette; lower arms articulate under their shoulder pivots.
        c.bone("armL", "torso", -18, -8, 0).role(CreatureBoneRole.ARM_LEFT)
            .cube("upperArm", -5, 0, -5, 10, 18, 10, "stone_dark")
            .cube("shoulderCap", -7, -5, -7, 14, 9, 14, "stone_engraved")
            .cube("upperBinding", -6, 5, -6, 12, 3, 12, "bronze")
            .cube("shoulderCrystal", -8, -3, -4, 3, 5, 8, "amethyst").end();
        c.bone("armL_forearm", "armL", 0, 17, 0)
            .cube("forearm", -5, 0, -5, 10, 15, 10, "stone_dark")
            .cube("bracer", -6, 1, -7, 12, 10, 3, "stone_engraved")
            .cube("wristBinding", -6, 11, -6, 12, 3, 12, "bronze")
            .cube("fist", -6, 13, -7, 12, 10, 13, "stone_dark")
            .cube("knuckles", -6, 14, -9, 12, 4, 3, "bronze").end();
        c.mirrorSubtree("armL", "armR");
        c.bone("legL", "pelvis", -7, 4, 0).role(CreatureBoneRole.LEG_LEFT)
            .cube("thigh", -5, 0, -5, 10, 17, 10, "stone_dark")
            .cube("thighPlate", -6, 1, -7, 12, 12, 3, "stone_engraved")
            .cube("kneeBinding", -6, 13, -6, 12, 4, 12, "bronze").end();
        c.bone("legL_shin", "legL", 0, 16, 0)
            .cube("shin", -4, 0, -4, 8, 10, 8, "stone_dark")
            .cube("shinPlate", -5, 0, -6, 10, 9, 3, "stone_engraved")
            .cube("boot", -6, 8, -8, 12, 4, 15, "stone_dark")
            .cube("toeCap", -6, 9, -9, 12, 3, 3, "bronze").end();
        c.mirrorSubtree("legL", "legR");
        return c;
    }
}
