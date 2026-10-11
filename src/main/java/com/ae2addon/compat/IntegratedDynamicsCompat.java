package com.ae2addon.compat;

import appeng.api.stacks.GenericStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Four static Integrated Dynamics machine families; dynamic facade/special recipes are excluded. */
public final class IntegratedDynamicsCompat {
    public record Stat(GenericStack stack, float chance) {}

    private static final String ROOT = "org.cyclops.integrateddynamics.core.recipe.type.";
    private static final Map<String, String> TYPES = Map.of(
            "squeezer", "RecipeSqueezer",
            "mechanical_squeezer", "RecipeMechanicalSqueezer",
            "drying_basin", "RecipeDryingBasin",
            "mechanical_drying_basin", "RecipeMechanicalDryingBasin");
    private static final Map<Class<?>, Boolean> MATCHES = new ConcurrentHashMap<>();

    private IntegratedDynamicsCompat() {}

    public static boolean isIntegratedDynamicsRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return MATCHES.computeIfAbsent(recipe.getClass(), owner -> {
            String type = ForeignCompatSupport.type(recipe);
            if (!type.startsWith("integrateddynamics:")) return false;
            String expected = TYPES.get(type.substring("integrateddynamics:".length()));
            return expected != null && owner.getName().equals(ROOT + expected);
        });
    }

    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        if (recipe == null || recipe.getId() == null
                || !"integrateddynamics".equals(recipe.getId().getNamespace())) return false;
        String name = recipe.getClass().getName();
        if (name.equals(ROOT + "RecipeSqueezerFacade")
                || name.equals(ROOT + "RecipeMechanicalSqueezerFacade")) return true;
        try {
            var serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            return serializer != null && serializer.getNamespace().equals("integrateddynamics")
                    && serializer.getPath().startsWith("crafting_special_");
        } catch (Throwable error) {
            ForeignCompatSupport.warn("integrateddynamics serializer", error);
            return false;
        }
    }

    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (!isIntegratedDynamicsRecipe(recipe)) return slots;
        try {
            var item = ForeignCompatSupport.items(ForeignCompatSupport.call(recipe, "getInputIngredient"), 0);
            if (!item.isEmpty()) slots.add(item);
            if (ForeignCompatSupport.type(recipe).contains("drying_basin")) {
                var fluid = ForeignCompatSupport.fluids(ForeignCompatSupport.call(recipe, "getInputFluid"), 0);
                if (!fluid.isEmpty()) slots.add(fluid);
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("integrateddynamics inputs", error);
        }
        return List.copyOf(slots);
    }

    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        return false;
    }

    public static List<Stat> outputs(Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        if (!isIntegratedDynamicsRecipe(recipe)) return out;
        try {
            String type = ForeignCompatSupport.type(recipe);
            if (type.contains("squeezer")) {
                for (Object entry : ForeignCompatSupport.elements(
                        ForeignCompatSupport.call(recipe, "getOutputItems"))) {
                    GenericStack stack = ForeignCompatSupport.stack(
                            ForeignCompatSupport.call(entry, "getIngredientFirst"));
                    float chance = ForeignCompatSupport.decimal(
                            ForeignCompatSupport.call(entry, "getChance"), 1f);
                    add(out, stack, chance);
                }
            } else {
                add(out, ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "getOutputItemFirst")), 1f);
            }
            add(out, ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "getOutputFluid")), 1f);
        } catch (Throwable error) {
            ForeignCompatSupport.warn("integrateddynamics outputs", error);
        }
        return List.copyOf(out);
    }

    private static void add(List<Stat> out, @Nullable GenericStack stack, float chance) {
        if (stack != null && stack.what() != null && stack.amount() > 0)
            out.add(new Stat(stack, chance));
    }
}
