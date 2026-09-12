package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAssetValidation
import com.wjz.worldsmith.core.content.CustomBlockProfile
import com.wjz.worldsmith.core.content.CustomBlockValidation
import com.wjz.worldsmith.core.content.CustomCreatureValidator
import com.wjz.worldsmith.core.content.CustomItemValidation
import com.wjz.worldsmith.core.content.QuestValidation
import com.wjz.worldsmith.core.content.WorldContentRegistry
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.structure.StructureCatalogCompiler
import com.wjz.worldsmith.core.structure.StructureValidator
import kotlinx.serialization.json.*

/** Machine-readable authoring maxima taken from the same constants that enforce them, not example-world quotas. */
object WorldsmithAuthoringBudgets {
    @JvmStatic fun snapshot():JsonObject=buildJsonObject {
        put("scope","ONE_BUNDLE_SHARED_CATALOG")
        put("mapInstancesAreNotCounted",true)
        put("notRecommendedTargetCounts",true)
        put("exhaustive",false)
        putJsonObject("designPlan") {
            put("maxTargets",WorldDesignPlans.MAX_TARGETS)
            put("maxLinks",WorldDesignPlans.MAX_LINKS)
        }
        putJsonObject("sharedCatalog") {
            put("maxEntries",WorldContentRegistry.MAX_ENTRIES)
            put("maxBundleTextBytes",WorldContentBundleIO.MAX_TEXT_BYTES)
            put("note","All logical entry kinds share this catalog; aliases and referenced blueprint definitions also occupy entries. Bundle JSON texts share a separate byte budget. Other domain, input/session and native validation limits still apply.")
        }
        putJsonObject("biomes") {
            put("independentDefinitionLimit",JsonNull)
            put("note","There is no separate biome-count hard cap. Shared catalog/document budgets, climate validity and actual distribution checks still apply; example counts are not limits.")
        }
        putJsonObject("structures") {
            put("maxDefinitions",StructureValidator.MAX_STRUCTURES)
            put("maxBlueprints",StructureCatalogCompiler.MAX_BLUEPRINTS)
            put("maxCatalogTemplateVoxels",StructureCatalogCompiler.MAX_TOTAL_VOXELS)
            put("maxCatalogExpandedWork",StructureCatalogCompiler.MAX_TOTAL_WORK)
            put("maxPlanAuthoredCells",StructureCatalogCompiler.MAX_PLAN_VOXELS)
            put("countsIncludeAir",true)
            put("catalogBudgetIncludesAllVariants",true)
            put("note","Catalog voxel/work totals sum distinct blueprint templates and all blueprint geometry variants, not assembly-plan combinations or repeated instances. The per-plan budget separately sums its authored parts including AIR. These limits do not count generated map instances.")
        }
        putJsonObject("blocks") {
            put("maxDefinitions",CustomBlockProfile.entries.size*CustomBlockValidation.SLOTS_PER_PROFILE)
            putJsonObject("perProfile") {CustomBlockProfile.entries.forEach {put(it.name,CustomBlockValidation.SLOTS_PER_PROFILE)}}
            put("note","Total capacity is derived from the actual physical profiles; each profile has its own non-transferable slot budget.")
        }
        putJsonObject("creatures") {put("maxDefinitions",CustomCreatureValidator.MAX_CREATURES)}
        putJsonObject("items") {put("maxDefinitions",CustomItemValidation.MAX_ITEMS)}
        putJsonObject("quests") {
            put("maxDefinitions",QuestValidation.MAX_QUESTS)
            put("graph","SINGLE_LINEAR_MAIN_LINE")
            put("maxObjectivesPerQuest",QuestValidation.MAX_OBJECTIVES)
            put("note","These are linear main-line definitions, not branching quest trees or guaranteed gameplay hours.")
        }
        putJsonObject("pngAssets") {
            put("maxAssets",ContentAssetValidation.MAX_ASSETS)
            put("maxTotalBytes",ContentAssetValidation.MAX_TOTAL_BYTES)
            put("maxTotalDecodedPixels",ContentAssetValidation.MAX_TOTAL_PIXELS)
            put("maxBytesPerAsset",ContentAssetValidation.MAX_ASSET_BYTES)
            put("note","All block, item and creature PNGs share the count, encoded-byte and decoded-pixel budgets of one bundle.")
        }
    }
}
