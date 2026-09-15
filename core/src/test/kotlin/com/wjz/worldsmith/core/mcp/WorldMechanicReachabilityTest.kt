package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.structure.StructureLibrary
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldMechanicReachabilityTest {
    private val base by lazy { WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands") }
    private val empty = DesignInventory(emptySet(), emptySet(), emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(), emptyMap())
    private val offering = "worldsmith:item/offering"
    private val relic = "worldsmith:item/relic"
    private val anchor = MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:stone"))
    private fun rule(id: String = "activate", actions: List<MechanicAction> = listOf(MechanicAction.GiveItem(relic))) =
        WorldMechanicRule(id, WorldMechanicEvent.USE_BLOCK, listOf(anchor), actions)
    private fun mechanic(id: String = "altar", rules: List<WorldMechanicRule> = listOf(rule())) = WorldMechanicDefinition(id, id, rules = rules)
    private fun quest(id: String, previous: String? = null, objectives: List<QuestObjective>, rewards: List<QuestReward> = emptyList()) =
        Quest(id, id, "Mechanic production route", previous?.let(::listOf).orEmpty(), objectives, rewards)
    private fun seed(count: Int = 1, item: String = offering) = quest("seed", objectives = listOf(QuestObjective.DeliverItem("minecraft:stick")), rewards = listOf(QuestReward(item, count)))
    private fun world(mechanics: List<WorldMechanicDefinition>, quests: List<Quest> = emptyList(), creatures: List<CreatureDefinition> = emptyList()): WorldsmithPack =
        base.copy(mechanics = WorldMechanicLibrary(mechanics = mechanics), quests = QuestLibrary(quests = quests), structures = StructureLibrary(), creatures = CreatureLibrary(creatures = creatures))
    private fun guardian(drops: List<CreatureDrop> = emptyList()) = CreatureDefinition("guardian", "Guardian", CreatureCategory.HOSTILE,
        CreatureModel("a".repeat(64), 16, 16, listOf(CreatureBone("body", cubes = listOf(CreatureCube(CreatureVector(), CreatureVector(1f, 1f, 1f)))))), drops = drops)
    private fun summon() = rule(actions = listOf(MechanicAction.SpawnCreature("guardian", MechanicOffset(y = 1)))).copy(heldItem = MechanicItemCost(offering))
    private fun codes(pack: WorldsmithPack) = WorldQuestReachability.validate(pack, empty).map { it.code }

    @Test fun nativeInputMechanicProducesLogicalItemsAndActivationFactsWithoutAQuestDriver() {
        val pack = world(listOf(mechanic()), listOf(quest("observe", objectives = listOf(QuestObjective.DeliverItem(relic, 2), QuestObjective.ActivateMechanic("altar")))))
        assertTrue(codes(pack).isEmpty())
        assertEquals(setOf("altar"), WorldMechanicReachability.analyze(pack, empty).mechanics)
        assertTrue(WorldQuestReachability.validate(pack.copy(quests = QuestLibrary()), empty, setOf(ContentKey("item", "relic"))).isEmpty())
    }

    @Test fun noSeedSelfRewardAndTwoMechanicMutualOfferingCyclesAreNotSources() {
        val self = mechanic(rules = listOf(rule().copy(heldItem = MechanicItemCost(relic))))
        val target = quest("observe", objectives = listOf(QuestObjective.ActivateMechanic("altar")))
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(world(listOf(self), listOf(target))))
        val a = mechanic("a", listOf(rule(actions = listOf(MechanicAction.GiveItem(relic))).copy(heldItem = MechanicItemCost(offering))))
        val b = mechanic("b", listOf(rule(actions = listOf(MechanicAction.GiveItem(offering))).copy(heldItem = MechanicItemCost(relic))))
        val circular = world(listOf(a, b), listOf(quest("get", objectives = listOf(QuestObjective.DeliverItem(relic)))))
        assertEquals(listOf("DESIGN_DELIVERY_NO_PRODUCER"), codes(circular))
        assertTrue(WorldMechanicReachability.analyze(circular, empty).mechanics.isEmpty())
    }

    @Test fun summonedCreatureDroppingItsOwnOfferingNeedsAnInitialSeed() {
        val creature = guardian(listOf(CreatureDrop(offering)))
        val mechanism = mechanic(rules = listOf(summon()))
        val target = quest("observe", objectives = listOf(QuestObjective.ActivateMechanic("altar", 2)))
        val blocked = world(listOf(mechanism), listOf(target), listOf(creature))
        assertTrue(WorldMechanicReachability.analyze(blocked, empty).creatures.isEmpty())
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(blocked))
        val seeded = blocked.copy(quests = QuestLibrary(quests = listOf(seed(), target.copy(prerequisites = listOf("seed")))))
        assertTrue(codes(seeded).isEmpty())
        assertEquals(setOf("guardian"), WorldMechanicReachability.analyze(seeded, empty).creatures)
    }

    @Test fun oneShotSummonsRespectFiniteEarlierRewardsAcrossRepeatedActivations() {
        val mechanism = mechanic(rules = listOf(summon()))
        fun pack(count: Int) = world(listOf(mechanism), listOf(seed(2), quest("observe", "seed", listOf(QuestObjective.ActivateMechanic("altar", count)))), listOf(guardian()))
        assertTrue(codes(pack(2)).isEmpty())
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(pack(3)))
    }

    @Test fun offeringsAndDeliveryObjectivesShareTheSameFiniteStock() {
        val mechanism = mechanic(rules = listOf(summon()))
        fun pack(count: Int) = world(listOf(mechanism), listOf(seed(2), quest("observe", "seed",
            listOf(QuestObjective.DeliverItem(offering), QuestObjective.ActivateMechanic("altar", count)))), listOf(guardian()))
        assertTrue(codes(pack(1)).isEmpty())
        assertEquals(listOf("DESIGN_DELIVERY_FINITE_SUPPLY_INSUFFICIENT"), codes(pack(2)))
    }

    @Test fun selfAndFutureQuestRewardsDoNotBootstrapTheirRequiredMechanic() {
        val mechanism = mechanic(rules = listOf(summon()))
        val first = quest("observe", objectives = listOf(QuestObjective.ActivateMechanic("altar")), rewards = listOf(QuestReward(offering)))
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(world(listOf(mechanism), listOf(first), listOf(guardian()))))
        val later = seed().copy(id = "later", prerequisites = listOf("observe"))
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(world(listOf(mechanism), listOf(first.copy(rewards = emptyList()), later), listOf(guardian()))))
    }

    @Test fun disconnectedStateAndDisjointBiomePathsNeverMakeASummonReachable() {
        val disconnected = mechanic(rules = listOf(summon().copy(fromState = "sealed"))).copy(states = listOf("idle", "sealed", "active"))
        assertTrue(WorldMechanicReachability.analyze(world(listOf(disconnected), creatures = listOf(guardian())), empty).creatures.isEmpty())
        val first = rule("arm").copy(toState = "armed", biomes = listOf("a"))
        val second = summon().copy(fromState = "armed", heldItem = null, biomes = listOf("b"))
        val mechanism = mechanic(rules = listOf(first, second)).copy(states = listOf("idle", "armed", "active"))
        val pack = world(listOf(mechanism), creatures = listOf(guardian())).let { it.copy(biomes = it.biomes.copy(biomes = listOf(it.biomes.biomes.first().copy(id = "a"), it.biomes.biomes.first().copy(id = "b")))) }
        assertTrue(WorldMechanicReachability.analyze(pack, empty).creatures.isEmpty())
    }

    @Test fun everyStateTransitionPaysItsCostAndLaterOutputsDoNotSatisfyEarlierInputs() {
        val arm = rule("arm").copy(toState = "armed", heldItem = MechanicItemCost(offering))
        val spawn = summon().copy(fromState = "armed")
        val mechanism = mechanic(rules = listOf(arm, spawn)).copy(states = listOf("idle", "armed", "active"))
        fun pack(amount: Int) = world(listOf(mechanism), listOf(seed(amount), quest("slay", "seed", listOf(QuestObjective.KillCreature("guardian")))), listOf(guardian()))
        assertEquals(listOf("DESIGN_KILL_TARGET_UNPLACED"), codes(pack(1)))
        assertTrue(codes(pack(2)).isEmpty())
        val laterReward = mechanism.copy(rules = listOf(arm, spawn.copy(heldItem = null, actions = listOf(MechanicAction.SpawnCreature("guardian", MechanicOffset(y = 1)), MechanicAction.GiveItem(offering)))))
        assertTrue(WorldMechanicReachability.analyze(world(listOf(laterReward), creatures = listOf(guardian())), empty).creatures.isEmpty())
        val forwardReward = mechanism.copy(rules = listOf(arm.copy(heldItem = null, actions = listOf(MechanicAction.GiveItem(offering))), spawn))
        assertEquals(setOf("guardian"), WorldMechanicReachability.analyze(world(listOf(forwardReward), creatures = listOf(guardian())), empty).creatures)
    }

    @Test fun oneCurrentQuestKillSharesItsDropAndAllMatchingKillObjectivesRegardlessOfArrayOrder() {
        val mechanism = mechanic(rules = listOf(summon()))
        val mixed = quest("mixed", "seed", listOf(QuestObjective.DeliverItem(relic), QuestObjective.KillCreature("guardian"), QuestObjective.KillCreature("guardian")))
        val pack = world(listOf(mechanism), listOf(seed(), mixed), listOf(guardian(listOf(CreatureDrop(relic)))))
        assertTrue(codes(pack).isEmpty())
        assertTrue(WorldMechanicReachability.analyze(pack, empty).creatures.contains("guardian"))
        val later = quest("later", "mixed", listOf(QuestObjective.KillCreature("guardian")))
        assertEquals(listOf("DESIGN_KILL_TARGET_UNPLACED"), codes(pack.copy(quests = QuestLibrary(quests = listOf(seed(), mixed, later)))))
    }

    @Test fun aCustomAnchorNeedsAnActualProducerNotMerelyAUsesBlockReferenceFromItself() {
        val custom = rule().copy(pattern = listOf(anchor.copy(block = MechanicBlockPredicate("worldsmith:content/altar"))))
        val pack = world(listOf(mechanic(rules = listOf(custom))))
        val selfReference = empty.copy(links = setOf(DesignLink(ContentKey("mechanic", "altar"), ContentKey("block", "altar"), DesignRelation.USES_BLOCK)))
        assertTrue(WorldMechanicReachability.analyze(pack, selfReference).mechanics.isEmpty())
        val actualTerrain = selfReference.copy(links = selfReference.links + DesignLink(ContentKey("terrain", "main"), ContentKey("block", "altar"), DesignRelation.USES_BLOCK))
        assertEquals(setOf("altar"), WorldMechanicReachability.analyze(pack, actualTerrain).mechanics)
    }

    @Test fun consumedCustomAnchorsAreFiniteAndPatternPlusHandCountsAreCombined() {
        val custom = "worldsmith:content/altar"
        val consumed = rule().copy(pattern = listOf(anchor.copy(block = MechanicBlockPredicate(custom), consume = true)))
        val two = quest("observe", "seed", listOf(QuestObjective.ActivateMechanic("altar", 2)))
        val finite = world(listOf(mechanic(rules = listOf(consumed))), listOf(seed(item = custom), two))
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(finite))
        val reusable = consumed.copy(pattern = consumed.pattern.map { it.copy(consume = false) })
        val reusablePack = finite.copy(mechanics = WorldMechanicLibrary(mechanics = listOf(mechanic(rules = listOf(reusable)))))
        assertTrue(codes(reusablePack).isEmpty())
        val heldToo = reusable.copy(heldItem = MechanicItemCost(custom))
        val needTwo = finite.copy(
            mechanics = WorldMechanicLibrary(mechanics = listOf(mechanic(rules = listOf(heldToo)))),
            quests = QuestLibrary(quests = listOf(seed(item = custom), two.copy(objectives = listOf(QuestObjective.ActivateMechanic("altar"))))),
        )
        assertEquals(listOf("DESIGN_MECHANIC_UNREACHABLE"), codes(needTwo))
    }

    @Test fun implicitIngredientKillsAlsoCreditOtherObjectivesOfTheSameCurrentQuest() {
        val a = guardian(listOf(CreatureDrop(relic))).copy(id = "a")
        val b = guardian().copy(id = "b")
        val summonA = mechanic("summon_a", listOf(summon().copy(actions = listOf(MechanicAction.SpawnCreature("a", MechanicOffset(y = 1))))))
        val summonB = mechanic("summon_b", listOf(summon().copy(heldItem = MechanicItemCost(relic), actions = listOf(MechanicAction.SpawnCreature("b", MechanicOffset(y = 1))))))
        val mixed = quest("mixed", "seed", listOf(QuestObjective.KillCreature("b"), QuestObjective.KillCreature("a")))
        assertTrue(codes(world(listOf(summonA, summonB), listOf(seed(), mixed), listOf(a, b))).isEmpty())
    }

    @Test fun aPreviouslyActivatedAnchorCarriesItsStateIntoTheNextQuestWithoutPayingThePathTwice() {
        val arm = rule("a_arm").copy(toState = "armed", heldItem = MechanicItemCost(offering))
        val spawn = summon().copy(id = "z_summon", fromState = "armed")
        val mechanism = mechanic(rules = listOf(arm, spawn)).copy(states = listOf("idle", "armed", "active"))
        val prepare = quest("prepare", "seed", listOf(QuestObjective.ActivateMechanic("altar")))
        val slay = quest("slay", "prepare", listOf(QuestObjective.KillCreature("guardian")))
        assertTrue(codes(world(listOf(mechanism), listOf(seed(2), prepare, slay), listOf(guardian()))).isEmpty())
    }
}
