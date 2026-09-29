package com.wjz.worldsmith.content.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import java.util.List;

/**
 * Native crafting and cooking whose result is a world item. A recipe file can
 * only freeze a partial component patch, while a world item's tool rules and
 * abilities are built from its definition, so these subclasses keep vanilla
 * matching, stations and the recipe book, and build the result when it is made.
 * The frozen `result` is only the icon and name the recipe book shows.
 */
public final class WorldItemRecipes {
    public static final RecipeSerializer<ShapedRecipe> SHAPED = new RecipeSerializer<>(Shaped.CODEC, Shaped.STREAM_CODEC);
    public static final RecipeSerializer<ShapelessRecipe> SHAPELESS = new RecipeSerializer<>(Shapeless.CODEC, Shapeless.STREAM_CODEC);
    public static final RecipeSerializer<SmeltingRecipe> SMELTING = cooking(Smelting::new);
    public static final RecipeSerializer<BlastingRecipe> BLASTING = cooking(Blasting::new);
    public static final RecipeSerializer<SmokingRecipe> SMOKING = cooking(Smoking::new);
    public static final RecipeSerializer<CampfireCookingRecipe> CAMPFIRE = cooking(Campfire::new);
    private static boolean registered;

    private WorldItemRecipes() {}

    static synchronized void register() {
        if (registered) return;
        register("world_shaped", SHAPED);
        register("world_shapeless", SHAPELESS);
        register("world_smelting", SMELTING);
        register("world_blasting", BLASTING);
        register("world_smoking", SMOKING);
        register("world_campfire_cooking", CAMPFIRE);
        registered = true;
    }

    private static void register(String path, RecipeSerializer<?> serializer) {
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath("worldsmith", path), serializer);
    }

    /** The world item a recipe makes; the count is the frozen display's. */
    public record WorldResult(String bundle, String item) {
        static final Codec<WorldResult> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("bundle").forGetter(WorldResult::bundle),
            Codec.STRING.fieldOf("item").forGetter(WorldResult::item)
        ).apply(i, WorldResult::new));
        static final StreamCodec<RegistryFriendlyByteBuf, WorldResult> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WorldResult::bundle, ByteBufCodecs.STRING_UTF8, WorldResult::item, WorldResult::new);

        public WorldResult {
            new WorldItemIdentity(bundle, item);
        }

        /** Empty while no level of that world is running, so a stale recipe crafts nothing. */
        public ItemStack create(int count) {
            return create(CustomItemRuntime.boundSnapshot(bundle), count);
        }

        ItemStack create(CustomItemRuntime.Snapshot snapshot, int count) {
            return snapshot == null || !snapshot.definitions().containsKey(item) ? ItemStack.EMPTY
                : snapshot.stack(CustomItemRuntime.LOGICAL_PREFIX + item, count);
        }
    }

    private interface Made {
        ItemStackTemplate shown();
        WorldResult world();
    }

    public static final class Shaped extends ShapedRecipe implements Made {
        static final MapCodec<ShapedRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> new Recipe.CommonInfo(o.showNotification())),
            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> new CraftingRecipe.CraftingBookInfo(o.category(), o.group())),
            ShapedRecipePattern.MAP_CODEC.forGetter(o -> ((Shaped) o).pattern),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> ((Shaped) o).display),
            WorldResult.CODEC.fieldOf("world_result").forGetter(o -> ((Shaped) o).world)
        ).apply(i, Shaped::new));
        static final StreamCodec<RegistryFriendlyByteBuf, ShapedRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> new Recipe.CommonInfo(o.showNotification()),
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> new CraftingRecipe.CraftingBookInfo(o.category(), o.group()),
            ShapedRecipePattern.STREAM_CODEC, o -> ((Shaped) o).pattern,
            ItemStackTemplate.STREAM_CODEC, o -> ((Shaped) o).display,
            WorldResult.STREAM_CODEC, o -> ((Shaped) o).world,
            Shaped::new);
        private final ShapedRecipePattern pattern;
        private final ItemStackTemplate display;
        private final WorldResult world;

        Shaped(Recipe.CommonInfo common, CraftingRecipe.CraftingBookInfo book, ShapedRecipePattern pattern, ItemStackTemplate display, WorldResult world) {
            super(common, book, pattern, display);
            this.pattern = pattern; this.display = display; this.world = world;
        }
        @Override public ItemStack assemble(CraftingInput input) { return world.create(display.count()); }
        @Override public RecipeSerializer<ShapedRecipe> getSerializer() { return SHAPED; }
        @Override public ItemStackTemplate shown() { return display; }
        @Override public WorldResult world() { return world; }
    }

    public static final class Shapeless extends ShapelessRecipe implements Made {
        static final MapCodec<ShapelessRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> new Recipe.CommonInfo(o.showNotification())),
            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> new CraftingRecipe.CraftingBookInfo(o.category(), o.group())),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> ((Shapeless) o).display),
            Ingredient.CODEC.listOf(1, 9).fieldOf("ingredients").forGetter(o -> ((Shapeless) o).ingredients),
            WorldResult.CODEC.fieldOf("world_result").forGetter(o -> ((Shapeless) o).world)
        ).apply(i, Shapeless::new));
        static final StreamCodec<RegistryFriendlyByteBuf, ShapelessRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> new Recipe.CommonInfo(o.showNotification()),
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> new CraftingRecipe.CraftingBookInfo(o.category(), o.group()),
            ItemStackTemplate.STREAM_CODEC, o -> ((Shapeless) o).display,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), o -> ((Shapeless) o).ingredients,
            WorldResult.STREAM_CODEC, o -> ((Shapeless) o).world,
            Shapeless::new);
        private final ItemStackTemplate display;
        private final List<Ingredient> ingredients;
        private final WorldResult world;

        Shapeless(Recipe.CommonInfo common, CraftingRecipe.CraftingBookInfo book, ItemStackTemplate display, List<Ingredient> ingredients, WorldResult world) {
            super(common, book, display, ingredients);
            this.display = display; this.ingredients = ingredients; this.world = world;
        }
        @Override public ItemStack assemble(CraftingInput input) { return world.create(display.count()); }
        @Override public RecipeSerializer<ShapelessRecipe> getSerializer() { return SHAPELESS; }
        @Override public ItemStackTemplate shown() { return display; }
        @Override public WorldResult world() { return world; }
    }

    @FunctionalInterface private interface CookingFactory<R extends AbstractCookingRecipe> {
        R create(Recipe.CommonInfo common, AbstractCookingRecipe.CookingBookInfo book, Ingredient input, ItemStackTemplate display, float experience, int ticks, WorldResult world);
    }

    private static <R extends AbstractCookingRecipe> RecipeSerializer<R> cooking(CookingFactory<R> factory) {
        MapCodec<R> codec = RecordCodecBuilder.mapCodec(i -> i.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> new Recipe.CommonInfo(o.showNotification())),
            AbstractCookingRecipe.CookingBookInfo.MAP_CODEC.forGetter(o -> new AbstractCookingRecipe.CookingBookInfo(o.category(), o.group())),
            Ingredient.CODEC.fieldOf("ingredient").forGetter(AbstractCookingRecipe::input),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> ((Made) o).shown()),
            Codec.FLOAT.optionalFieldOf("experience", 0.0F).forGetter(AbstractCookingRecipe::experience),
            Codec.intRange(1, Short.MAX_VALUE).fieldOf("cookingtime").forGetter(AbstractCookingRecipe::cookingTime),
            WorldResult.CODEC.fieldOf("world_result").forGetter(o -> ((Made) o).world())
        ).apply(i, factory::create));
        StreamCodec<RegistryFriendlyByteBuf, R> stream = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> new Recipe.CommonInfo(o.showNotification()),
            AbstractCookingRecipe.CookingBookInfo.STREAM_CODEC, o -> new AbstractCookingRecipe.CookingBookInfo(o.category(), o.group()),
            Ingredient.CONTENTS_STREAM_CODEC, AbstractCookingRecipe::input,
            ItemStackTemplate.STREAM_CODEC, o -> ((Made) o).shown(),
            ByteBufCodecs.FLOAT, AbstractCookingRecipe::experience,
            ByteBufCodecs.INT, AbstractCookingRecipe::cookingTime,
            WorldResult.STREAM_CODEC, o -> ((Made) o).world(),
            factory::create);
        return new RecipeSerializer<>(codec, stream);
    }

    public static final class Smelting extends SmeltingRecipe implements Made {
        private final ItemStackTemplate display; private final WorldResult world;
        Smelting(Recipe.CommonInfo c, AbstractCookingRecipe.CookingBookInfo b, Ingredient in, ItemStackTemplate display, float xp, int ticks, WorldResult world) {
            super(c, b, in, display, xp, ticks); this.display = display; this.world = world;
        }
        @Override public ItemStack assemble(SingleRecipeInput input) { return world.create(display.count()); }
        @Override public RecipeSerializer<SmeltingRecipe> getSerializer() { return SMELTING; }
        @Override public ItemStackTemplate shown() { return display; }
        @Override public WorldResult world() { return world; }
    }

    public static final class Blasting extends BlastingRecipe implements Made {
        private final ItemStackTemplate display; private final WorldResult world;
        Blasting(Recipe.CommonInfo c, AbstractCookingRecipe.CookingBookInfo b, Ingredient in, ItemStackTemplate display, float xp, int ticks, WorldResult world) {
            super(c, b, in, display, xp, ticks); this.display = display; this.world = world;
        }
        @Override public ItemStack assemble(SingleRecipeInput input) { return world.create(display.count()); }
        @Override public RecipeSerializer<BlastingRecipe> getSerializer() { return BLASTING; }
        @Override public ItemStackTemplate shown() { return display; }
        @Override public WorldResult world() { return world; }
    }

    public static final class Smoking extends SmokingRecipe implements Made {
        private final ItemStackTemplate display; private final WorldResult world;
        Smoking(Recipe.CommonInfo c, AbstractCookingRecipe.CookingBookInfo b, Ingredient in, ItemStackTemplate display, float xp, int ticks, WorldResult world) {
            super(c, b, in, display, xp, ticks); this.display = display; this.world = world;
        }
        @Override public ItemStack assemble(SingleRecipeInput input) { return world.create(display.count()); }
        @Override public RecipeSerializer<SmokingRecipe> getSerializer() { return SMOKING; }
        @Override public ItemStackTemplate shown() { return display; }
        @Override public WorldResult world() { return world; }
    }

    public static final class Campfire extends CampfireCookingRecipe implements Made {
        private final ItemStackTemplate display; private final WorldResult world;
        Campfire(Recipe.CommonInfo c, AbstractCookingRecipe.CookingBookInfo b, Ingredient in, ItemStackTemplate display, float xp, int ticks, WorldResult world) {
            super(c, b, in, display, xp, ticks); this.display = display; this.world = world;
        }
        @Override public ItemStack assemble(SingleRecipeInput input) { return world.create(display.count()); }
        @Override public RecipeSerializer<CampfireCookingRecipe> getSerializer() { return CAMPFIRE; }
        @Override public ItemStackTemplate shown() { return display; }
        @Override public WorldResult world() { return world; }
    }
}
