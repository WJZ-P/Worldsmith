package com.wjz.worldsmith.ability;

import static com.wjz.worldsmith.ability.NativeAbilityCapabilities.*;
import com.wjz.worldsmith.content.*;
import com.wjz.worldsmith.content.creature.*;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.content.CreatureCategory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Bounded loaded-world mutations; costs/temporary writes have explicit, owned lifecycles. */
final class AbilityWorldGameplay {
    private AbilityWorldGameplay() {}
    static void install() {
        WorldAbilityRuntime.builtin("world.set_block",AbilityWorldGameplay::setBlock);
        WorldAbilityRuntime.builtin("world.restore",(c,a) -> AbilityValues.bool(c.releaseLease(integer(a,0,1,Integer.MAX_VALUE),"world")));
        WorldAbilityRuntime.builtin("entity.spawn",AbilityWorldGameplay::spawn);
        WorldAbilityRuntime.builtin("entity.despawn",(c,a) -> AbilityValues.bool(c.retireEntity(c.resolve(a.getFirst()))));
        WorldAbilityRuntime.builtin("inventory.count",(c,a) -> AbilityValues.number(count(c,text(a,0,128))));
        WorldAbilityRuntime.builtin("inventory.take",(c,a) -> AbilityValues.bool(take(c,text(a,0,128),integer(a,1,1,64))));
        WorldAbilityRuntime.builtin("inventory.give",(c,a) -> AbilityValues.bool(give(c,c.living(a.get(0)),text(a,1,128),integer(a,2,1,64))));
    }
    static AbilityValue setBlock(WorldAbilityRuntime.Context c,List<AbilityValue> a) {
        BlockPos pos=BlockPos.containing(c.point(a.get(0))); String ref=text(a,1,128); int ticks=integer(a,3,0,1200);
        var properties=AbilityGameplayProviders.map(a.get(2)); BlockState desired=resolve(c,ref,properties), before=c.level().getBlockState(pos);
        checkWrite(c,pos,before,desired);
        var journal=c.worldEdits();
        if(journal.pending(pos)) throw new IllegalArgumentException("Position already owns a pending temporary edit");
        if(ticks==0) {
            c.chargePermanentWrite();
            if(!write(c.level(),pos,desired)) throw new IllegalStateException("Permanent block write was rejected");
            return AbilityValues.number(0);
        }
        c.reserveResource();
        String token=UUID.randomUUID().toString();
        var edit=new AbilityWorldSavedData.Edit(token,c.invocation().toString(),c.level().getGameTime()+ticks,pos,before,desired);
        journal.add(edit);
        int lease;
        try { lease=c.lease("world",ticks,() -> journal.restore(c.level(),token)); }
        catch(RuntimeException failure) { journal.restore(c.level(),token); throw failure; }
        if(!write(c.level(),pos,desired)) { c.releaseLease(lease,"world"); throw new IllegalStateException("Temporary block write was rejected"); }
        return AbilityValues.number(lease);
    }
    private static boolean write(ServerLevel level,BlockPos pos,BlockState state) {
        return (level.setBlock(pos,state,Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) || level.getBlockState(pos)==state) && level.getBlockState(pos)==state;
    }
    private static BlockState resolve(WorldAbilityRuntime.Context c,String ref,Map<String,AbilityValue> properties) {
        return resolve(c.snapshot().blocks(),ref,properties);
    }
    static BlockState resolve(WorldBlockBindings.Resolver blocks,String ref,Map<String,AbilityValue> properties) {
        if(WorldsmithCustomBlocks.isReservedNativeId(ref)) throw new IllegalArgumentException("Use logical block IDs, not reserved native hosts");
        if(properties.size()>16) throw new IllegalArgumentException("Too many block properties");
        var values=new LinkedHashMap<String,String>();
        properties.forEach((key,value) -> {
            if(!(value instanceof AbilityValue.TextValue text))throw new IllegalArgumentException("Block property values must be text");
            values.put(key,text.getValue());
        });
        BlockState state;
        if(ref.startsWith("worldsmith:content/")) {
            return blocks.resolve(ref,values);
        } else state=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(ref)).orElseThrow(() -> new IllegalArgumentException("Unknown block: "+ref)).defaultBlockState();
        for(var entry:values.entrySet()) {
            var property=state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if(property==null) throw new IllegalArgumentException("Unknown block property: "+entry.getKey());
            state=property(state,property,entry.getValue());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> property,String value) {
        return state.setValue(property,property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid property value")));
    }
    private static void checkWrite(WorldAbilityRuntime.Context c,BlockPos pos,BlockState old,BlockState desired) {
        if(c.actor() instanceof Player player) {
            if(!player.mayBuild() || !c.level().mayInteract(player,pos)) throw new IllegalArgumentException("Protected world edit");
        } else {
            if(!c.level().getGameRules().get(GameRules.MOB_GRIEFING)) throw new IllegalArgumentException("Mob world edits are disabled");
            if(c.level().getServer() instanceof DedicatedServer server) {
                var spawn=c.level().getRespawnData(); var origin=spawn.pos();
                if(server.spawnProtectionRadius()>0 && !server.getPlayerList().getOps().isEmpty() && c.level().dimension()==spawn.dimension()
                    && Math.max(Math.abs(pos.getX()-origin.getX()),Math.abs(pos.getZ()-origin.getZ()))<=server.spawnProtectionRadius())
                    throw new IllegalArgumentException("Protected spawn area");
            }
        }
        if(!WorldAbilityRuntime.loaded(c.level(),new AABB(pos).inflate(1)) || old.hasBlockEntity() || desired.hasBlockEntity() || old.getDestroySpeed(c.level(),pos)<0
            || desired.getDestroySpeed(c.level(),pos)<0 || !old.getFluidState().isEmpty() || !desired.getFluidState().isEmpty()
            || desired.getBlock() instanceof FallingBlock || desired.getBlock() instanceof BaseFireBlock || desired.getBlock() instanceof TntBlock
            || desired.getBlock() instanceof NetherPortalBlock || desired.getBlock() instanceof EndPortalBlock)
            throw new IllegalArgumentException("World writes require loaded ordinary data-free blocks");
        var chunk=c.level().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null || chunk.getBlockEntities().containsKey(pos) || chunk.getBlockEntityNbt(pos)!=null) throw new IllegalArgumentException("Block entity data is protected");
        if(!c.level().isUnobstructed(null,desired.getCollisionShape(c.level(),pos,CollisionContext.empty()).move(pos))) throw new IllegalArgumentException("World write intersects an entity");
    }
    private static AbilityValue spawn(WorldAbilityRuntime.Context c,List<AbilityValue> a) {
        String id=text(a,0,64); Vec3 pos=c.point(a.get(1)); int ticks=integer(a,2,1,1200); c.reserveResource();
        var definition=c.snapshot().creatures().definitions().get(id);
        if(definition==null) throw new IllegalArgumentException("Unknown custom creature: "+id);
        if(definition.getCategory()==CreatureCategory.HOSTILE && c.level().getDifficulty()==Difficulty.PEACEFUL) throw new IllegalArgumentException("Hostile summon is unavailable in peaceful mode");
        var type=definition.getCategory()==CreatureCategory.PASSIVE ? CreatureRuntime.passiveType() : definition.getBoss()==null ? CreatureRuntime.hostileType() : CreatureRuntime.encounterBossType();
        CreatureEntity entity=type.create(c.level(),EntitySpawnReason.TRIGGERED);
        if(entity==null) throw new IllegalStateException("Custom creature host creation failed");
        try {
            entity.snapTo(pos.x,pos.y,pos.z,c.actor().getYRot(),0); entity.initialize(c.snapshot().scope(),id,c.level().getRandom().nextLong()); entity.setPersistenceRequired();
            if(!entity.checkSpawnObstruction(c.level()) || entity.getBoundingBox().minY<c.level().getMinY() || entity.getBoundingBox().maxY>c.level().getMaxY()) throw new IllegalArgumentException("Summon position is obstructed");
            return c.ownEntity(entity,ticks);
        } catch(RuntimeException failure) { entity.discard(); throw failure; }
    }
    private static ItemStack prototype(WorldAbilityRuntime.Context c,String ref) { return WorldRewardItems.stack(ref,1,c.snapshot().blocks(),c.snapshot().items()); }
    private static boolean matches(WorldAbilityRuntime.Context c,ItemStack stack,String ref,ItemStack proto) {
        if(stack.isEmpty()) return false;
        if(ref.startsWith("worldsmith:item/")) {
            var definition=CustomItemRuntime.definition(c.level(),stack);
            return definition!=null && ("worldsmith:item/"+definition.getId()).equals(ref);
        }
        return stack.getItem()==proto.getItem();
    }
    private static int count(WorldAbilityRuntime.Context c,String ref) {
        ItemStack proto=prototype(c,ref); if(!(c.actor() instanceof Player player)) return 0;
        int count=0; for(int i=0;i<36;i++) if(matches(c,player.getInventory().getItem(i),ref,proto)) count+=player.getInventory().getItem(i).getCount(); return count;
    }
    private static boolean take(WorldAbilityRuntime.Context c,String ref,int amount) {
        ItemStack proto=prototype(c,ref); if(!(c.actor() instanceof Player player)) return false;
        var inventory=player.getInventory(); List<ItemStack> plan=new ArrayList<>(36); int remaining=amount;
        for(int i=0;i<36;i++) {
            ItemStack copy=inventory.getItem(i).copy();
            if(matches(c,copy,ref,proto)) { int taken=Math.min(copy.getCount(),remaining); copy.shrink(taken); remaining-=taken; }
            plan.add(copy);
        }
        if(remaining>0) return false;
        apply(inventory,plan); return true;
    }
    private static boolean give(WorldAbilityRuntime.Context c,LivingEntity target,String ref,int count) {
        ItemStack proto=prototype(c,ref);
        if(!(target instanceof ServerPlayer player) || player.isSpectator() || target!=c.actor() && !mayAffect(c,target)) return false;
        Inventory inventory=player.getInventory(); List<ItemStack> plan=new ArrayList<>(36);
        for(int i=0;i<36;i++) plan.add(inventory.getItem(i).copy());
        int remaining=count;
        for(int i=0;i<36 && remaining>0;i++) {
            ItemStack stack=plan.get(i); if(stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack,proto)) continue;
            int added=Math.min(remaining,Math.max(0,inventory.getMaxStackSize(stack)-stack.getCount())); stack.grow(added); remaining-=added;
        }
        for(int i=0;i<36 && remaining>0;i++) if(plan.get(i).isEmpty()) {
            int added=Math.min(remaining,inventory.getMaxStackSize(proto)); if(added<=0) continue;
            plan.set(i,proto.copyWithCount(added)); remaining-=added;
        }
        if(remaining>0) return false;
        apply(inventory,plan); return true;
    }
    private static void apply(Inventory inventory,List<ItemStack> plan) {
        for(int i=0;i<36;i++) inventory.setItem(i,plan.get(i)); inventory.setChanged();
    }
}
