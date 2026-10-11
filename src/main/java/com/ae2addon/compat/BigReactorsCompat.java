package com.ae2addon.compat;

import appeng.api.stacks.GenericStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Four ExtremeReactors2 recipe families, including fluid inputs and outputs. */
public final class BigReactorsCompat {
    public record Stat(GenericStack stack, float chance) {}

    private static final Map<String, String> SERIALIZERS = Map.of(
            "fluidizersolid", "it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerSolidRecipe",
            "fluidizersolidmixing", "it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerSolidMixingRecipe",
            "fluidizerfluidmixing", "it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerFluidMixingRecipe",
            "reprocessor", "it.zerono.mods.extremereactors.gamecontent.multiblock.reprocessor.recipe.ReprocessorRecipe");

    private BigReactorsCompat() {}

    @Nullable
    private static String kind(@Nullable Recipe<?> recipe) {
        if (recipe == null) return null;
        try {
            var id = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            if (id == null || !id.getNamespace().equals("bigreactors")) return null;
            String kind = id.getPath();
            String expected = SERIALIZERS.get(kind);
            if (expected == null || !recipe.getClass().getName().equals(expected)) return null;
            String type = ForeignCompatSupport.type(recipe);
            return type.equals(kind.equals("reprocessor") ? "bigreactors:reprocessor" : "bigreactors:fluidizer")
                    ? kind : null;
        } catch (Throwable error) {
            ForeignCompatSupport.warn("bigreactors kind", error);
            return null;
        }
    }

    public static boolean isBigReactorsRecipe(@Nullable Recipe<?> recipe) {
        return kind(recipe) != null;
    }

    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        return false;
    }

    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        String kind = kind(recipe);
        if (kind == null) return slots;
        try {
            if (kind.equals("fluidizersolid")) {
                add(slots, ForeignCompatSupport.call(recipe, "getIngredient"));
            } else {
                add(slots, ForeignCompatSupport.call(recipe, "getIngredient1"));
                add(slots, ForeignCompatSupport.call(recipe, "getIngredient2"));
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("bigreactors inputs", error);
        }
        return List.copyOf(slots);
    }

    private static void add(List<List<GenericStack>> slots, @Nullable Object ingredient) {
        Object matches = ForeignCompatSupport.call(ingredient, "getMatchingElements");
        var options = new ArrayList<GenericStack>();
        for (Object value : ForeignCompatSupport.elements(matches)) {
            GenericStack stack = ForeignCompatSupport.stack(value);
            if (stack != null) options.add(stack);
        }
        if (!options.isEmpty()) slots.add(List.copyOf(options));
    }

    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        return false;
    }

    public static List<Stat> outputs(Recipe<?> recipe) {
        if (kind(recipe) == null) return List.of();
        try {
            Object result = ForeignCompatSupport.call(recipe, "getResult");
            GenericStack stack = ForeignCompatSupport.stack(ForeignCompatSupport.call(result, "getResult"));
            return stack == null ? List.of() : List.of(new Stat(stack, 1));
        } catch (Throwable error) {
            ForeignCompatSupport.warn("bigreactors output", error);
            return List.of();
        }
    }

    /** Normalized input alternatives and output; reverse mixing recipes share one key. */
    @Nullable
    public static String duplicateKey(Recipe<?> recipe) {
        String kind = kind(recipe);
        if (!"fluidizersolidmixing".equals(kind) && !"fluidizerfluidmixing".equals(kind)) return null;
        var inputs = new ArrayList<String>();
        for (var slot : inputSlots(recipe)) {
            var alternatives = new ArrayList<String>();
            for (var stack : slot) alternatives.add(stack.what() + "@" + stack.amount());
            alternatives.sort(Comparator.naturalOrder());
            inputs.add(String.join("|", alternatives));
        }
        inputs.sort(Comparator.naturalOrder());
        var outputs = new ArrayList<String>();
        for (var stat : outputs(recipe)) outputs.add(stat.stack().what() + "@" + stat.stack().amount());
        return kind + ':' + String.join(";", inputs) + "->" + String.join(";", outputs);
    }
}
