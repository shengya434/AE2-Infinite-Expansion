package com.ae2addon.compat;

import appeng.api.stacks.GenericStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Restores SizedIngredient counts, including the optional aggregator input. */
public final class Ae2CsCompat {
    public record Stat(GenericStack stack, float chance) {}

    private static final Map<String, String> TYPES = Map.of(
            "crystal_aggregator_recipe", "io.github.lounode.ae2cs.common.recipe.crystal_aggregator.CrystalAggregatorRecipe",
            "crystal_pulverizer_recipe", "io.github.lounode.ae2cs.common.recipe.crystal_pulverizer.CrystalPulverizerRecipe",
            "circuit_etcher_recipe", "io.github.lounode.ae2cs.common.recipe.circuit_etcher.CircuitEtcherRecipe");

    private Ae2CsCompat() {}

    public static boolean isAe2CsRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        String type = ForeignCompatSupport.type(recipe);
        String expected = type.startsWith("ae2cs:") ? TYPES.get(type.substring(6)) : null;
        if (expected == null || !recipe.getClass().getName().equals(expected)) return false;
        try {
            var serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            return serializer != null && serializer.toString().equals(type + "_serializer");
        } catch (Throwable error) {
            ForeignCompatSupport.warn("ae2cs serializer", error);
            return false;
        }
    }

    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        try {
            var serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            return serializer != null && (serializer.toString().equals("ae2cs:resonating_pattern_upgrade")
                    || serializer.toString().equals("ae2cs:resonating_linker_clear"));
        } catch (Throwable error) {
            ForeignCompatSupport.warn("ae2cs special serializer", error);
            return false;
        }
    }

    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (!isAe2CsRecipe(recipe)) return slots;
        try {
            String type = ForeignCompatSupport.type(recipe);
            if (type.equals("ae2cs:crystal_pulverizer_recipe")) {
                add(slots, ForeignCompatSupport.call(recipe, "input"));
            } else {
                for (Object sized : ForeignCompatSupport.elements(ForeignCompatSupport.call(recipe, "required")))
                    add(slots, sized);
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("ae2cs inputs", error);
        }
        return List.copyOf(slots);
    }

    private static void add(List<List<GenericStack>> slots, @Nullable Object sized) {
        if (sized == null) return;
        int count = ForeignCompatSupport.integer(ForeignCompatSupport.call(sized, "count"));
        Object ingredient = ForeignCompatSupport.call(sized, "ingredient");
        if (count <= 0) return;
        var options = ForeignCompatSupport.items(ingredient, count);
        if (!options.isEmpty()) slots.add(options);
    }

    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        return false;
    }

    public static List<Stat> outputs(Recipe<?> recipe) {
        if (!isAe2CsRecipe(recipe)) return List.of();
        try {
            GenericStack stack = ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "result"));
            return stack == null ? List.of() : List.of(new Stat(stack, 1));
        } catch (Throwable error) {
            ForeignCompatSupport.warn("ae2cs output", error);
            return List.of();
        }
    }
}
