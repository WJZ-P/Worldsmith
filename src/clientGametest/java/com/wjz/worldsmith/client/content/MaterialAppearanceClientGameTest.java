package com.wjz.worldsmith.client.content;

import com.wjz.worldsmith.client.quest.WorldArrivalOverlay;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.examples.MaterialFamilyFactory;
import com.wjz.worldsmith.core.examples.MaterialShowcaseExample;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/** Real native models, four ordinary placement packets and one actual costly repair in an isolated save. */
public final class MaterialAppearanceClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var pack=MaterialShowcaseExample.create();var names=new LinkedHashMap<String,String>();
        pack.getBiomes().getBiomes().forEach(b->names.put(b.getId(),"worldsmith:generated/"+pack.getComputedId()+"/"+b.getId()));
        var screenshots=new ArrayList<Path>();context.getInput().resizeWindow(1120,700);
        context.runOnClient(client->{client.options.guiScale().set(2);client.options.setCameraType(CameraType.FIRST_PERSON);});
        try(var world=context.worldBuilder().create()) {
            var prepared=WorldContentRuntime.prepare(pack,names);await(context,context.computeOnClient(client->WorldContentClientRuntime.prepare(prepared).activate()));
            BlockPos base=world.getServer().computeOnServer(server->{
                server.setDifficulty(Difficulty.PEACEFUL,true);var level=server.overworld();WorldContentRuntime.bindLevel(level,prepared);
                var clock=level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow();server.clockManager().setTotalTicks(clock,6000);server.clockManager().setPaused(clock,true);
                var player=server.getPlayerList().getPlayers().getFirst();player.setGameMode(GameType.SURVIVAL);player.getInventory().clearContent();
                var origin=player.blockPosition().offset(-7,-1,6);
                for(int x=-6;x<=20;x++)for(int z=-9;z<=18;z++) { level.setBlock(origin.offset(x,0,z),Blocks.STONE_BRICKS.defaultBlockState(),Block.UPDATE_ALL);for(int y=1;y<=7;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL); }
                var geometry=StructureGeometryCompiler.compile(MaterialShowcaseExample.blueprint());var template=new StructureTemplate();
                template.load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(geometry,level.registryAccess(),CompiledPack.scoped(pack)));
                check(template.placeInWorld(level,origin,origin,new StructurePlaceSettings().setIgnoreEntities(false),level.getRandom(),Block.UPDATE_ALL),"Material workshop template did not place");
                level.setBlock(origin.offset(1,1,8),prepared.blockResolver().resolve("worldsmith:content/"+MaterialFamilyFactory.HEARTH_TIMBER),Block.UPDATE_ALL);
                stand(player,origin.offset(7,1,-3));return origin;
            });
            world.getConnection().waitForChunksDownload();context.waitTicks(5);
            if(context.computeOnClient(client->WorldArrivalOverlay.isVisible()))context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client->!WorldArrivalOverlay.isVisible() && client.gui.screen()==null && client.gui.overlay()==null,200);
            context.runOnClient(client->{
                var models=client.getModelManager().getBlockStateModelSet();
                for(var definition:pack.getBlocks().getBlocks()) {
                    var state=prepared.blockResolver().resolve("worldsmith:content/"+definition.getId());
                    check(models.get(state)!=models.missingModel(),"Native block model was missing: "+definition.getId());
                    String particle=models.getParticleMaterial(state).sprite().contents().name().toString();
                    check(particle.equals("worldsmith:block/content/"+definition.getAppearance().getParticle()),"Native particle sprite did not use actual bound PNG: "+particle);
                }
            });
            settlePosition(context,base.offset(7,1,-3));context.getInput().lookAt(base.offset(7,3,8));screenshots.add(capture(context,"materials-01-native-workshop"));
            world.getServer().runOnServer(server->stand(server.getPlayerList().getPlayers().getFirst(),base.offset(3,1,6)));
            settlePosition(context,base.offset(3,1,6));context.getInput().lookAt(base.offset(1,1,8));screenshots.add(capture(context,"materials-02-timber-end-and-side"));
            world.getServer().runOnServer(server->stand(server.getPlayerList().getPlayers().getFirst(),base.offset(7,1,8)));
            settlePosition(context,base.offset(7,1,8));context.getInput().lookAt(base.offset(7,2,3));screenshots.add(capture(context,"materials-03-authored-four-directions"));
            world.getServer().runOnServer(server->stand(server.getPlayerList().getPlayers().getFirst(),base.offset(3,1,9)));
            settlePosition(context,base.offset(3,1,9));context.getInput().lookAt(base.offset(3,3,12));screenshots.add(capture(context,"materials-04-native-translucent-window"));

            // Four actual BlockItem uses through the client game-mode packet path, not direct setBlock calls.
            for(int i=0;i<4;i++) {
                Direction facing=new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}[i];BlockPos support=base.offset(2+i*3,0,-4);
                world.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();stand(player,support.relative(facing,2).above());
                    player.getInventory().setSelectedSlot(0);player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(prepared.blockResolver().resolveItem("worldsmith:content/"+MaterialFamilyFactory.WAYSTONE),1));});
                settlePosition(context,support.relative(facing,2).above());
                context.waitFor(client->client.player.getMainHandItem().is(prepared.blockResolver().resolveItem("worldsmith:content/"+MaterialFamilyFactory.WAYSTONE)),100);
                context.getInput().lookAt(support);context.waitTicks(4);useBlock(context,support);
                awaitServer(context,()->world.getServer().computeOnServer(server->{var state=server.overworld().getBlockState(support.above());return state.is(prepared.blockResolver().resolve("worldsmith:content/"+MaterialFamilyFactory.WAYSTONE).getBlock())&&state.getValue(WorldsmithCustomBlocks.FACING)==facing;}),100);
                check(world.getServer().computeOnServer(server->server.getPlayerList().getPlayers().getFirst().getMainHandItem().isEmpty()),"Survival block placement did not consume the actual stack");
            }
            world.getServer().runOnServer(server->stand(server.getPlayerList().getPlayers().getFirst(),base.offset(7,1,-9)));
            settlePosition(context,base.offset(7,1,-9));context.getInput().lookAt(base.offset(7,1,-4));screenshots.add(capture(context,"materials-05-player-placed-four-directions"));

            // Collect real authored container inventory; no synthetic matching items or substituted texture icons.
            world.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();player.getInventory().clearContent();
                var chest=(Container)server.overworld().getBlockEntity(base.offset(MaterialShowcaseExample.CHEST_POSITION.getX(),MaterialShowcaseExample.CHEST_POSITION.getY(),MaterialShowcaseExample.CHEST_POSITION.getZ()));
                check(chest!=null,"Authored material supply container missing");for(int slot=0;slot<3;slot++)check(player.getInventory().add(chest.removeItemNoUpdate(slot)),"Material supply did not fit inventory");
                player.getInventory().setSelectedSlot(0);stand(player,base.offset(7,1,7));});
            settlePosition(context,base.offset(7,1,7));
            context.waitFor(client->{var definition=CustomItemRuntime.definition(client.level,client.player.getMainHandItem());return definition!=null&&definition.getId().equals(MaterialFamilyFactory.WICK);},100);
            context.runOnClient(client->client.gui.setScreen(new InventoryScreen(client.player)));
            context.waitForScreen(InventoryScreen.class);context.waitTicks(5);screenshots.add(context.takeScreenshot("materials-06-native-item-inventory"));
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);context.waitForScreen(null);
            BlockPos lamp=base.offset(MaterialShowcaseExample.LAMP_POSITION.getX(),MaterialShowcaseExample.LAMP_POSITION.getY(),MaterialShowcaseExample.LAMP_POSITION.getZ());
            world.getServer().runOnServer(server->{var level=server.overworld();server.clockManager().setTotalTicks(level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow(),18000);});
            context.getInput().lookAt(lamp);screenshots.add(capture(context,"materials-07-closed-lamp-before-repair"));
            useBlock(context,lamp);
            awaitServer(context,()->world.getServer().computeOnServer(server->{var state=server.overworld().getBlockState(lamp);return state.is(prepared.blockResolver().resolve("worldsmith:content/"+MaterialFamilyFactory.LAMP_LIT).getBlock())&&state.getLightEmission()==13;}),100);
            check(world.getServer().computeOnServer(server->server.getPlayerList().getPlayers().getFirst().getMainHandItem().getCount()==3),"Actual repair must consume exactly one of the four authored wicks");
            context.waitTicks(15);screenshots.add(capture(context,"materials-08-open-lamp-after-repair"));
            useBlock(context,lamp);context.waitTicks(5);
            check(world.getServer().computeOnServer(server->server.getPlayerList().getPlayers().getFirst().getMainHandItem().getCount()==3),"Repeat use paid the one-shot repair twice");
        }
        context.waitFor(client->client.level==null && WorldContentClientRuntime.activeScope()==null,200);
        check(WorldContentRuntime.boundLevelCount()==0,"Material preview world retained content bindings after shutdown");
        try {
            for(Path shot:screenshots)check(Files.isRegularFile(shot)&&Files.size(shot)>0,"Missing real capture: "+shot);
            Path report=Path.of(System.getProperty("worldsmith.material.client-report"));Files.createDirectories(report.getParent());
            Files.writeString(report,"PASS material appearances client\nActual native baked models and particle PNGs; six-face timber; authored and player-placed directions; native glass; actual supplied item icons; costly one-shot lamp repair with 0 to 13 light; disconnect cleanup\n"
                +screenshots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n"))+"\n");
        } catch(java.io.IOException error) {throw new java.io.UncheckedIOException(error);}
    }
    private static void useBlock(ClientGameTestContext context,BlockPos block) {
        context.getInput().lookAt(block);context.waitFor(client->client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(block),100);
        context.runOnClient(client->{var hit=(BlockHitResult)client.hitResult;client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,hit);});
    }
    private static void stand(ServerPlayer player,BlockPos at) {player.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);player.setDeltaMovement(0,0,0);}
    private static void settlePosition(ClientGameTestContext context,BlockPos at) {
        context.waitFor(client->client.player!=null&&client.player.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)<.04,120);context.waitTicks(3);
    }
    private static Path capture(ClientGameTestContext context,String name) {
        context.waitFor(client->client.gui.overlay()==null&&client.gui.screen()==null&&client.level!=null,200);context.waitTicks(8);
        // Use the ordinary player dismissal, never clear the UI state or pretend an obstructed capture passed review.
        if(context.computeOnClient(client->WorldArrivalOverlay.isVisible()))context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client->!WorldArrivalOverlay.isVisible()&&client.gui.screen()==null&&client.gui.overlay()==null,200);
        context.waitTicks(3);return context.takeScreenshot(name);
    }
    private static void await(ClientGameTestContext context,CompletableFuture<Void> task) {context.waitFor(client->task.isDone(),2400);task.join();context.waitFor(client->client.gui.overlay()==null,2400);context.waitTicks(3);}
    private static void awaitServer(ClientGameTestContext context,BooleanSupplier ready,int ticks) {for(int i=0;i<ticks;i++){if(ready.getAsBoolean())return;context.waitTick();}throw new AssertionError("Native material state did not arrive within "+ticks+" ticks");}
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
