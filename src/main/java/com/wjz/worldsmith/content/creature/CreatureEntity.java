package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.ability.AbilityEventRuntime;
import com.wjz.worldsmith.ability.AbilityActorControl;
import com.wjz.worldsmith.content.story.StoryCharacters;
import com.wjz.worldsmith.core.content.*;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.LoggerFactory;

/** Server-authoritative ground mob. The client receives immutable identity and bounded animation state only. */
public class CreatureEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> BUNDLE = SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> CREATURE = SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> APPEARANCE_SEED = SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> ACTION = SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BOSS_PHASE = SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.INT);
    private CreatureDefinition configured;
    private CreatureSoundProfile soundProfile;
    private BlockPos origin;
    private boolean diagnosed;
    private boolean suspendedForMissing;
    private boolean savedNoAi;
    private boolean rewardsProcessed;
    private final Set<ServerPlayer> bossTrackedPlayers = new HashSet<>();
    private ServerBossEvent bossEvent;
    private CreatureBehavior effectiveBehavior;
    private int savedBossPhase;
    private int combatRevision;
    private java.util.UUID combatInvocation;

    public CreatureEntity(EntityType<? extends CreatureEntity> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, .25)
            .add(Attributes.FOLLOW_RANGE, 24).add(Attributes.ATTACK_DAMAGE, 3).add(Attributes.KNOCKBACK_RESISTANCE, 0);
    }

    @Override protected void registerGoals() { /* Goals are installed only after immutable identity resolves. */ }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(BUNDLE, ""); builder.define(CREATURE, ""); builder.define(APPEARANCE_SEED, 0L); builder.define(ACTION, 0); builder.define(BOSS_PHASE, 0);
    }

    public String bundleHash() { return entityData.get(BUNDLE); }
    public String creatureId() { return entityData.get(CREATURE); }
    public long appearanceSeed() { return entityData.get(APPEARANCE_SEED); }
    public int action() { return entityData.get(ACTION); }
    public int bossPhase() { return entityData.get(BOSS_PHASE); }
    /** Actual server home, including a marker-owned resident's rebound home. */
    public BlockPos abilityHome() { return origin == null ? null : origin.immutable(); }
    public boolean isWorldBoss() { var d=definition(); return d!=null && d.getBoss()!=null; }
    public CreatureDefinition definition() { return entityData == null ? null : CreatureRuntime.definition(level(), bundleHash(), creatureId()); }

    /** Retire native goal movement only after an invocation actually claims MOVE+LOOK. */
    public void yieldToAbilityEvents() {
        if (!(level() instanceof ServerLevel)) return;
        StoryCharacters.yieldRoutine(this);
        goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning)
            .filter(goal -> !(goal.getGoal() instanceof ProgramControlGoal))
            .filter(goal -> goal.getFlags().contains(Goal.Flag.MOVE) || goal.getFlags().contains(Goal.Flag.LOOK))
            .toList().forEach(WrappedGoal::stop);
    }

    private void cancelCombatInvocation() {
        if (combatInvocation != null && level() instanceof ServerLevel level) WorldAbilityRuntime.cancelInvocation(level, combatInvocation);
        combatInvocation = null;
    }

    /** A generic presentation cue from the server program, not a damage decision or named skill. */
    public void setAbilityPose(String pose) {
        if (!(level() instanceof ServerLevel)) throw new IllegalStateException("Ability poses are server-owned");
        CreatureCombatState next = switch (pose) {
            case "idle" -> CreatureCombatState.IDLE;
            case "walk", "chase" -> CreatureCombatState.CHASE;
            case "windup" -> CreatureCombatState.WINDUP;
            case "strike" -> CreatureCombatState.STRIKE;
            case "recovery" -> CreatureCombatState.RECOVERY;
            default -> throw new IllegalArgumentException("Unknown creature presentation pose: " + pose);
        };
        if (next == CreatureCombatState.STRIKE && action() != next.ordinal() && isAlive()) {
            swing(InteractionHand.MAIN_HAND); playCreatureSound(CreatureSoundRole.ATTACK);
        }
        entityData.set(ACTION, next.ordinal());
    }

    /** World creation/debug spawn entry point; bind the level before initializing an entity. */
    public void initialize(String bundleHash, String creatureId, long seed) {
        if (!(level() instanceof ServerLevel)) throw new IllegalStateException("Creature initialization is server-only");
        var definition = CreatureRuntime.definition(level(), bundleHash, creatureId);
        if (definition == null) throw new IllegalArgumentException("Missing creature definition " + bundleHash + ":" + creatureId);
        if (!CreatureRuntime.matchesHost(getType(), definition)) throw new IllegalArgumentException("Creature native host/definition mismatch");
        if (!creatureId().isEmpty() && (!bundleHash().equals(bundleHash) || !creatureId().equals(creatureId)))
            throw new IllegalStateException("An existing creature's immutable definition cannot be replaced");
        entityData.set(BUNDLE, bundleHash); entityData.set(CREATURE, creatureId); entityData.set(APPEARANCE_SEED, seed);
        origin = blockPosition();
        configure(definition, true);
    }

    private void configure(CreatureDefinition d, boolean fresh) {
        if (configured != null && configured != d) { WorldAbilityRuntime.cancelOwner(this); AbilityActorControl.revokeActor(this); AbilityEventRuntime.creatureRemoved(this); combatInvocation = null; }
        configured = d;
        soundProfile = CreatureSounds.profile(d);
        var a = d.getAttributes();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(a.getHealth());
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(a.getSpeed());
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(a.getFollowRange());
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(a.getAttackDamage());
        getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(a.getKnockbackResistance());
        if (fresh) setHealth(getMaxHealth()); else setHealth(Math.min(getHealth(), getMaxHealth()));
        effectiveBehavior = d.getBehavior();
        if (d.getBoss() == null || d.getBoss().getPhases().isEmpty()) {
            savedBossPhase=0; entityData.set(BOSS_PHASE,0);
            if (d.getBoss() == null) clearBossBar();
        } else {
            int phase=CreatureBosses.phaseIndex(d.getBoss(), Math.max(0.0, getHealth()/getMaxHealth()), fresh ? 0 : savedBossPhase);
            applyBossPhase(d,phase);
        }
        if (origin == null) origin = blockPosition();
        setHomeTo(origin, d.getBehavior().getTerritoryRadius());
        goalSelector.removeAllGoals(goal -> true); targetSelector.removeAllGoals(goal -> true);
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new ReturnHomeGoal());
        goalSelector.addGoal(2, new ProgramControlGoal());
        if (d.getCategory() == CreatureCategory.HOSTILE) {
            // A program binding replaces ordinary melee; its damage, waits and branches live in source.
            goalSelector.addGoal(3, d.getAbility() == null ? new BoundedAttackGoal() : new AbilityAttackGoal());
            targetSelector.addGoal(1, new HurtByTargetGoal(this));
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        } else if (d.getBehavior().getPassiveMode() == CreaturePassiveMode.FLEE_PLAYERS) {
            goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Player.class, (float)a.getFollowRange(), 1.0, 1.35));
        }
        goalSelector.addGoal(4, new StoryRoutineGoal());
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        refreshDimensions();
        // Do not override an explicit vanilla NoAI save flag.
    }

    @Override public void tick() {
        if (level() instanceof ServerLevel) {
            CreatureDefinition d = definition();
            if (d == null || !CreatureRuntime.matchesHost(getType(), d)) {
                WorldAbilityRuntime.cancelOwner(this);
                AbilityActorControl.revokeActor(this);
                if (!suspendedForMissing) { savedNoAi = isNoAi(); suspendedForMissing = true; }
                if (!diagnosed) {
                    diagnosed = true;
                    LoggerFactory.getLogger("worldsmith.creatures").error("Creature {} has missing/mismatched definition {}:{}; AI suspended", getUUID(), bundleHash(), creatureId());
                }
                setNoAi(true); getNavigation().stop(); setTarget(null); entityData.set(ACTION, CreatureCombatState.IDLE.ordinal());
                clearBossBar();
            } else {
                if (suspendedForMissing) { setNoAi(savedNoAi); suspendedForMissing = false; diagnosed = false; }
                if (configured != d) configure(d, false);
                if (isNoAi() || !isAlive() || origin != null && !isWithinHome(blockPosition())) {
                    WorldAbilityRuntime.cancelOwner(this);
                    AbilityActorControl.revokeActor(this);
                    if (d.getAbility() != null) entityData.set(ACTION, CreatureCombatState.IDLE.ordinal());
                } else if (d.getAbility() != null && d.getAbility().getCancelOnTargetLoss() && !validTarget(getTarget())
                    && combatInvocation != null) {
                    cancelCombatInvocation();
                }
                updateBossState(d);
            }
        }
        super.tick();
        if (level() instanceof ServerLevel) AbilityEventRuntime.creatureTick(this);
    }

    @Override protected EntityDimensions getDefaultDimensions(Pose pose) {
        var d = definition();
        return d == null ? super.getDefaultDimensions(pose) : EntityDimensions.scalable(d.getAttributes().getWidth(), d.getAttributes().getHeight());
    }

    @Override public boolean checkSpawnObstruction(LevelReader level) {
        // Mob's default checks liquid/entity obstruction only. The initial native host-sized test is too
        // small for an authored Boss, so recheck solid blocks using the fully initialized body as well.
        return super.checkSpawnObstruction(level) && level.noCollision(this);
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key.equals(BUNDLE) || key.equals(CREATURE)) refreshDimensions();
    }

    @Override protected Component getTypeName() {
        var d = definition();
        return Component.literal(d == null ? "[Missing creature: " + creatureId() + "]" : d.getDisplayName());
    }

    @Override public boolean removeWhenFarAway(double distanceSquared) {
        // Unlike persistenceRequired, this preserves natural mob-cap accounting for naturally spawned bosses.
        return !isWorldBoss() && CreatureRuntime.category(getType()) == CreatureCategory.HOSTILE;
    }

    private void applyBossPhase(CreatureDefinition definition, int phase) {
        var selected=CreatureBosses.phase(definition.getBoss(),phase);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(definition.getAttributes().getSpeed()*selected.getSpeedMultiplier());
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(definition.getAttributes().getAttackDamage()*selected.getDamageMultiplier());
        var base=definition.getBehavior();
        effectiveBehavior=new CreatureBehavior(base.getPassiveMode(),base.getTerritoryRadius(),base.getAttackReach(),selected.getWindupTicks(),selected.getRecoveryTicks());
        savedBossPhase=phase; entityData.set(BOSS_PHASE,phase); combatRevision++;
    }

    private void updateBossState(CreatureDefinition definition) {
        var boss=definition.getBoss();
        if(boss==null || !isAlive() || isRemoved()) { clearBossBar(); return; }
        int selected = 0;
        if (!boss.getPhases().isEmpty()) {
            selected=CreatureBosses.phaseIndex(boss,Math.max(0.0,getHealth()/getMaxHealth()),bossPhase());
            if(selected!=bossPhase())applyBossPhase(definition,selected);
        }
        String title=boss.getBarTitle().isBlank()?definition.getDisplayName():boss.getBarTitle();
        String cue = WorldAbilityRuntime.caption(this);
        String suffix = !cue.isEmpty() ? cue : boss.getPhases().isEmpty() ? "" : CreatureBosses.phase(boss,selected).getName();
        Component name=Component.literal(title + (suffix.isEmpty() ? "" : " — " + suffix));
        if(bossEvent==null) {
            bossEvent=new ServerBossEvent(java.util.UUID.randomUUID(),name,BossEvent.BossBarColor.valueOf(boss.getBarColor().name()),BossEvent.BossBarOverlay.PROGRESS);
            bossTrackedPlayers.forEach(bossEvent::addPlayer);
        } else bossEvent.setName(name);
        bossEvent.setProgress(Math.max(0.0F,Math.min(1.0F,getHealth()/getMaxHealth())));
    }

    private void clearBossBar() {
        if(bossEvent!=null){bossEvent.removeAllPlayers();bossEvent=null;}
    }

    @Override public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player); bossTrackedPlayers.add(player);
        if(bossEvent!=null && isAlive())bossEvent.addPlayer(player);
    }

    @Override public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player); bossTrackedPlayers.remove(player);
        if(bossEvent!=null)bossEvent.removePlayer(player);
    }

    @Override public void die(DamageSource source) {
        boolean alreadyDead = dead;
        super.die(source);
        if (!alreadyDead && dead) playCreatureSound(CreatureSoundRole.DEATH);
        if(isDeadOrDying()){
            if (level() instanceof ServerLevel) { WorldAbilityRuntime.cancelOwner(this); AbilityActorControl.revokeActor(this); StoryCharacters.stopRoutine(this); }
            clearBossBar();bossTrackedPlayers.clear();
        }
    }

    /** Server broadcasts each vocal once. Native client hurt/death animation hooks remain silent. */
    @Override protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource source) { return null; }
    @Override protected net.minecraft.sounds.SoundEvent getDeathSound() { return null; }
    @Override public void playAmbientSound() { if (isAlive()) playCreatureSound(CreatureSoundRole.AMBIENT); }
    @Override public int getAmbientSoundInterval() { return soundProfile == null ? 200 : soundProfile.getAmbientIntervalTicks(); }
    @Override protected void playHurtSound(DamageSource source) {
        super.playHurtSound(source); // Retain Mob's ambient cooldown reset; getHurtSound is intentionally null.
        playCreatureSound(CreatureSoundRole.HURT);
    }
    @Override public net.minecraft.sounds.SoundSource getSoundSource() {
        var d = definition();
        return d != null && d.getCategory() == CreatureCategory.HOSTILE
            ? net.minecraft.sounds.SoundSource.HOSTILE : net.minecraft.sounds.SoundSource.NEUTRAL;
    }
    private void playCreatureSound(CreatureSoundRole role) {
        if (!(level() instanceof ServerLevel) || isSilent() || suspendedForMissing || soundProfile == null) return;
        var current = definition();
        if (current == null || current != configured || !CreatureRuntime.matchesHost(getType(), current)) return;
        var cue = CreatureSounds.cue(soundProfile, role);
        if (cue.getSound() == CreatureSound.SILENT || cue.getVolume() <= 0) return;
        playSound(CreatureSoundRuntime.event(cue.getSound()), cue.getVolume(), CreatureSounds.playbackPitch(cue, random.nextFloat()));
    }

    @Override public void onRemoval(Entity.RemovalReason reason) {
        if (level() instanceof ServerLevel) AbilityEventRuntime.creatureRemoved(this);
        // Also covers direct setRemoved during chunk unload or dimension changes, not only explicit remove().
        if (level() instanceof ServerLevel) WorldAbilityRuntime.cancelOwner(this);
        if (level() instanceof ServerLevel) { AbilityActorControl.revokeActor(this); StoryCharacters.stopRoutine(this); }
        clearBossBar();bossTrackedPlayers.clear();super.onRemoval(reason);
    }

    @Override protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        // This hook is reached after a real server killing blow, never from a client animation or arbitrary drop payload.
        if (level() != level || !dead || !isDeadOrDying() || rewardsProcessed) return;
        rewardsProcessed = true;
        if (!level.getGameRules().get(GameRules.MOB_DROPS) || !shouldDropLoot(level)) return;
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        var snapshot = CreatureRuntime.snapshot(level);
        if (snapshot == null || !snapshot.bundleHash().equals(bundleHash()) || !snapshot.definitions().containsKey(creatureId())) {
            LoggerFactory.getLogger("worldsmith.creatures").error("Creature {} reward rejected: missing immutable server definition {}:{}", getUUID(), bundleHash(), creatureId());
            return;
        }
        try {
            // All prototypes were resolved at publication; construct every roll before spawning any of them.
            var drops = snapshot.deathDrops(level, creatureId(), killedByPlayer, getRandom());
            for (var stack : drops) spawnAtLocation(level, stack);
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger("worldsmith.creatures").error("Creature {} reward rejected for {}:{}; no retry after this death", getUUID(), bundleHash(), creatureId(), failure);
        }
    }

    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (suspendedForMissing) output.putBoolean("NoAI", savedNoAi);
        output.putString("WorldsmithBundle", bundleHash()); output.putString("WorldsmithCreature", creatureId());
        output.putLong("WorldsmithAppearanceSeed", appearanceSeed());
        output.putBoolean("WorldsmithRewardsProcessed", rewardsProcessed);
        if(isWorldBoss())output.putInt("WorldsmithBossPhase",bossPhase());
        if (origin != null) { output.putInt("WorldsmithOriginX", origin.getX()); output.putInt("WorldsmithOriginY", origin.getY()); output.putInt("WorldsmithOriginZ", origin.getZ()); }
    }

    @Override protected void readAdditionalSaveData(ValueInput input) {
        if (level() instanceof ServerLevel) AbilityEventRuntime.creatureRemoved(this);
        if (level() instanceof ServerLevel) WorldAbilityRuntime.cancelOwner(this);
        if (level() instanceof ServerLevel) { AbilityActorControl.revokeActor(this); StoryCharacters.stopRoutine(this); }
        super.readAdditionalSaveData(input);
        entityData.set(BUNDLE, input.getStringOr("WorldsmithBundle", "")); entityData.set(CREATURE, input.getStringOr("WorldsmithCreature", ""));
        entityData.set(APPEARANCE_SEED, input.getLongOr("WorldsmithAppearanceSeed", 0));
        rewardsProcessed = input.getBooleanOr("WorldsmithRewardsProcessed", false);
        savedBossPhase = Math.max(0,Math.min(2,input.getIntOr("WorldsmithBossPhase",0)));
        entityData.set(BOSS_PHASE,savedBossPhase);
        // Actor attachments retain program state/cooldown; an animation or a waiting attack is never resumed.
        entityData.set(ACTION, CreatureCombatState.IDLE.ordinal());
        origin = new BlockPos(input.getIntOr("WorldsmithOriginX", blockPosition().getX()), input.getIntOr("WorldsmithOriginY", blockPosition().getY()), input.getIntOr("WorldsmithOriginZ", blockPosition().getZ()));
        configured = null;
    }

    @Override public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData group) {
        super.finalizeSpawn(level, difficulty, reason, group);
        if (!creatureId().isEmpty()) return group;
        var snapshot = CreatureRuntime.snapshot(level.getLevel());
        if (snapshot == null) { discard(); return group; }
        String biome = CreatureRuntime.nativeBiome(level, blockPosition());
        int light = level.getRawBrightness(blockPosition(), 0);
        CreatureGroup selected = group instanceof CreatureGroup g ? g : null;
        CreatureDefinition d = selected != null ? snapshot.definitions().get(selected.id) : snapshot.select(biome, CreatureRuntime.category(getType()), light, getRandom());
        if (d == null || !snapshot.candidates(biome, CreatureRuntime.category(getType()), light).contains(d) || selected != null && selected.count >= d.getSpawn().getMaxGroup()) { discard(); return group; }
        if(d.getBoss()!=null && selected==null && !CreatureRuntime.allowNaturalBoss(level.getLevel(),snapshot,d,blockPosition(),getRandom())){discard();return group;}
        initialize(snapshot.bundleHash(), d.getId(), getRandom().nextLong());
        // Vanilla checks generic host dimensions before finalization; recheck the selected custom body.
        if (!checkSpawnObstruction(level)) { discard(); return group; }
        if (selected == null) selected = new CreatureGroup(d.getId());
        selected.count++;
        return selected;
    }

    private static final class CreatureGroup implements SpawnGroupData {
        final String id; int count;
        CreatureGroup(String id) { this.id = id; }
    }

    private boolean validTarget(LivingEntity target) {
        return target != null && target.level() == level() && !target.isRemoved() && target.isAlive()
            && isWithinHome(target.blockPosition()) && isWithinHome(blockPosition())
            && !(target instanceof Player p && (p.isCreative() || p.isSpectator()));
    }

    private final class ReturnHomeGoal extends Goal {
        private int nextPath;
        ReturnHomeGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return origin != null && !isWithinHome(blockPosition()); }
        @Override public boolean canContinueToUse() { return origin != null && distanceToSqr(origin.getX() + .5, origin.getY(), origin.getZ() + .5) > 4 && !getNavigation().isDone(); }
        @Override public void start() { WorldAbilityRuntime.cancelOwner(CreatureEntity.this); AbilityActorControl.revokeActor(CreatureEntity.this); StoryCharacters.stopRoutine(CreatureEntity.this); setTarget(null); nextPath = 0; tick(); }
        @Override public void tick() {
            if (nextPath-- <= 0) { getNavigation().moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5, 1.0); nextPath = 20; }
        }
    }

    /** Rebind loaded resident homes to their actual marker rather than a recreated/random origin. */
    public void bindStoryHome(BlockPos home) {
        if(!(level() instanceof ServerLevel))throw new IllegalStateException("Story homes are server-owned");
        origin=home.immutable();setHomeTo(origin,Math.max(32,definition()==null?32:definition().getBehavior().getTerritoryRadius()));
    }
    private final class StoryRoutineGoal extends Goal {
        StoryRoutineGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return StoryCharacters.hasRoutine(CreatureEntity.this);}
        @Override public boolean canContinueToUse(){return canUse();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void start(){if(!AbilityActorControl.hasControl(CreatureEntity.this))getNavigation().stop();StoryCharacters.tick(CreatureEntity.this);}
        @Override public void tick(){StoryCharacters.tick(CreatureEntity.this);}
        @Override public void stop(){StoryCharacters.stopRoutine(CreatureEntity.this);}
    }

    /** Only an explicit live token blocks native movement; pure observers run beside native routines. */
    private final class ProgramControlGoal extends Goal {
        ProgramControlGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return AbilityActorControl.hasControl(CreatureEntity.this); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
    }

    /** Target acquisition is native; everything after activation runs through the shared source VM. */
    private final class AbilityAttackGoal extends Goal {
        private net.minecraft.world.level.pathfinder.Path nativePath;
        AbilityAttackGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private boolean active() {
            return combatInvocation != null && level() instanceof ServerLevel level && WorldAbilityRuntime.invocationActive(level, combatInvocation);
        }
        private boolean keepRunning() {
            var d = definition();
            if (d == null || d.getAbility() == null || isNoAi() || !isAlive() || !isWithinHome(blockPosition())) return false;
            return validTarget(getTarget()) || active();
        }
        @Override public boolean canUse() { return keepRunning(); }
        @Override public boolean canContinueToUse() { return keepRunning(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { setAggressive(true); nativePath = null; }
        @Override public void stop() {
            var d = definition();
            boolean unavailable = d == null || d.getAbility() == null || isNoAi() || !isAlive() || !isWithinHome(blockPosition());
            if (unavailable) WorldAbilityRuntime.cancelOwner(CreatureEntity.this);
            else if (d.getAbility().getCancelOnTargetLoss() && !validTarget(getTarget()))
                cancelCombatInvocation();
            setAggressive(false);
            // Retire only this goal's approach path, never an explicit newer controller's route.
            if (nativePath != null && getNavigation().getPath() == nativePath) getNavigation().stop();
            nativePath = null;
            if (!active() && !AbilityActorControl.hasControl(CreatureEntity.this)) entityData.set(ACTION, CreatureCombatState.IDLE.ordinal());
            if (!validTarget(getTarget())) setTarget(null);
        }
        @Override public void tick() {
            if (!keepRunning()) { stop(); return; }
            if (active()) return;
            var target = getTarget();
            if (validTarget(target)) {
                var beforePath = getNavigation().getPath();
                WorldAbilityRuntime.creatureTick(CreatureEntity.this, target);
                if (getNavigation().getPath() != beforePath) nativePath = getNavigation().getPath();
                var d = definition();
                if (d != null && d.getAbility() != null)
                    combatInvocation = WorldAbilityRuntime.invocation(CreatureEntity.this, d.getAbility().getProgram());
            }
        }
    }

    private final class BoundedAttackGoal extends Goal {
        private CreatureCombatFrame frame = new CreatureCombatFrame(CreatureCombatState.IDLE, 0);
        private int nextPath;
        private int seenCombatRevision;
        BoundedAttackGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return validTarget(getTarget()); }
        @Override public boolean canContinueToUse() { return validTarget(getTarget()); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { setAggressive(true); nextPath = 0; frame=new CreatureCombatFrame();seenCombatRevision=combatRevision; }
        @Override public void stop() {
            frame = new CreatureCombatFrame(CreatureCombatState.IDLE, 0); entityData.set(ACTION, frame.getState().ordinal());
            setAggressive(false); getNavigation().stop();
            if (!validTarget(getTarget())) setTarget(null);
        }
        @Override public void tick() {
            var target = getTarget();
            if (!validTarget(target)) { stop(); return; }
            var behavior = effectiveBehavior;
            if(seenCombatRevision!=combatRevision){frame=new CreatureCombatFrame();seenCombatRevision=combatRevision;nextPath=0;}
            getLookControl().setLookAt(target, 30, 30);
            double reach = behavior.getAttackReach() + target.getBbWidth() * .5;
            boolean close = distanceToSqr(target) <= reach * reach;
            var decision = CreatureCombat.advance(frame, true, close, getSensing().hasLineOfSight(target), behavior);
            frame = decision.getFrame(); entityData.set(ACTION, frame.getState().ordinal());
            if (frame.getState() == CreatureCombatState.CHASE) {
                if (nextPath-- <= 0) { getNavigation().moveTo(target, 1.1); nextPath = 10; }
            } else getNavigation().stop();
            if (decision.getStrike()) { swing(InteractionHand.MAIN_HAND); playCreatureSound(CreatureSoundRole.ATTACK); doHurtTarget((ServerLevel)level(), target); }
        }
    }
}
