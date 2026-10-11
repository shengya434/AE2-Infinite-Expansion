package com.ae2addon.compat;

import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One dispatch point shared by JEI, ME indexing, and the controller's input checks. */
public final class ForeignRecipeCompat {
    public record Stat(GenericStack stack, float chance) {}

    private ForeignRecipeCompat() {}

    public static boolean isHandled(@Nullable Recipe<?> recipe) {
        return IndustrialForegoingCompat.isIndustrialForegoingRecipe(recipe)
                || IntegratedDynamicsCompat.isIntegratedDynamicsRecipe(recipe)
                || PneumaticCraftCompat.isPneumaticCraftRecipe(recipe)
                || Ae2CsCompat.isAe2CsRecipe(recipe)
                || BigReactorsCompat.isBigReactorsRecipe(recipe);
    }

    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        return IndustrialForegoingCompat.isExcludedByDesign(recipe)
                || IntegratedDynamicsCompat.isExcludedByDesign(recipe)
                || PneumaticCraftCompat.isExcludedByDesign(recipe)
                || Ae2CsCompat.isExcludedByDesign(recipe)
                || BigReactorsCompat.isExcludedByDesign(recipe);
    }

    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        if (IndustrialForegoingCompat.isIndustrialForegoingRecipe(recipe))
            return IndustrialForegoingCompat.inputSlots(recipe);
        if (IntegratedDynamicsCompat.isIntegratedDynamicsRecipe(recipe))
            return IntegratedDynamicsCompat.inputSlots(recipe);
        if (PneumaticCraftCompat.isPneumaticCraftRecipe(recipe))
            return PneumaticCraftCompat.inputSlots(recipe);
        if (Ae2CsCompat.isAe2CsRecipe(recipe))
            return Ae2CsCompat.inputSlots(recipe);
        if (BigReactorsCompat.isBigReactorsRecipe(recipe))
            return BigReactorsCompat.inputSlots(recipe);
        return List.of();
    }

    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        return IndustrialForegoingCompat.isIndustrialForegoingRecipe(recipe)
                && IndustrialForegoingCompat.isCatalystSlot(recipe, slotIndex, options);
    }

    public static List<Stat> outputs(Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        if (IndustrialForegoingCompat.isIndustrialForegoingRecipe(recipe)) {
            for (var value : IndustrialForegoingCompat.outputs(recipe))
                out.add(new Stat(value.stack(), value.chance()));
        }
        if (IntegratedDynamicsCompat.isIntegratedDynamicsRecipe(recipe)) {
            for (var value : IntegratedDynamicsCompat.outputs(recipe))
                out.add(new Stat(value.stack(), value.chance()));
        }
        if (PneumaticCraftCompat.isPneumaticCraftRecipe(recipe)) {
            for (var value : PneumaticCraftCompat.outputs(recipe))
                out.add(new Stat(value.stack(), value.chance()));
        }
        if (Ae2CsCompat.isAe2CsRecipe(recipe)) {
            for (var value : Ae2CsCompat.outputs(recipe))
                out.add(new Stat(value.stack(), value.chance()));
        }
        if (BigReactorsCompat.isBigReactorsRecipe(recipe)) {
            for (var value : BigReactorsCompat.outputs(recipe))
                out.add(new Stat(value.stack(), value.chance()));
        }
        return List.copyOf(out);
    }

    @Nullable
    public static String duplicateKey(Recipe<?> recipe) {
        return BigReactorsCompat.duplicateKey(recipe);
    }
}
