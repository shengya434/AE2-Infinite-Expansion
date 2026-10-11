package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Industrial Foregoing's six machine recipes bypass the vanilla ingredient API. */
public final class IndustrialForegoingCompat {
    public record Stat(GenericStack stack, float chance) {}

    private static final Map<String, String> TYPES = Map.of(
            "dissolution_chamber", "DissolutionChamberRecipe",
            "crusher", "CrusherRecipe",
            "fluid_extractor", "FluidExtractorRecipe",
            "laser_drill_ore", "LaserDrillOreRecipe",
            "laser_drill_fluid", "LaserDrillFluidRecipe",
            "stonework_generate", "StoneWorkGenerateRecipe");
    private static final Map<Class<?>, Boolean> MATCHES = new ConcurrentHashMap<>();

    private IndustrialForegoingCompat() {}

    public static boolean isIndustrialForegoingRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return MATCHES.computeIfAbsent(recipe.getClass(), owner -> {
            String type = ForeignCompatSupport.type(recipe);
            if (!type.startsWith("industrialforegoing:")) return false;
            String expected = TYPES.get(type.substring("industrialforegoing:".length()));
            return expected != null && owner.getName().equals("com.buuz135.industrial.recipe." + expected);
        });
    }

    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        return false;
    }

    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (!isIndustrialForegoingRecipe(recipe)) return slots;
        try {
            String type = ForeignCompatSupport.type(recipe);
            switch (type) {
                case "industrialforegoing:dissolution_chamber" -> {
                    // Every Ingredient.Value is a separate slot; repeats must remain repeats.
                    for (Object value : ForeignCompatSupport.elements(ForeignCompatSupport.field(recipe, "input")))
                        add(slots, ForeignCompatSupport.items(value, 0));
                    add(slots, ForeignCompatSupport.fluids(ForeignCompatSupport.field(recipe, "inputFluid"), 0));
                }
                case "industrialforegoing:crusher", "industrialforegoing:fluid_extractor" ->
                        add(slots, ForeignCompatSupport.items(ForeignCompatSupport.field(recipe, "input"), 0));
                case "industrialforegoing:laser_drill_ore", "industrialforegoing:laser_drill_fluid" ->
                        add(slots, ForeignCompatSupport.items(ForeignCompatSupport.field(recipe, "catalyst"), 0));
                case "industrialforegoing:stonework_generate" -> {
                    addResource(slots, net.minecraft.world.level.material.Fluids.WATER,
                            ForeignCompatSupport.integer(ForeignCompatSupport.field(recipe, "waterNeed")));
                    addResource(slots, net.minecraft.world.level.material.Fluids.LAVA,
                            ForeignCompatSupport.integer(ForeignCompatSupport.field(recipe, "lavaNeed")));
                }
                default -> { }
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("industrialforegoing inputs", error);
        }
        return List.copyOf(slots);
    }

    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        if (!isIndustrialForegoingRecipe(recipe) || slotIndex < 0 || options.isEmpty()) return false;
        String type = ForeignCompatSupport.type(recipe);
        if (type.equals("industrialforegoing:laser_drill_ore")
                || type.equals("industrialforegoing:laser_drill_fluid")) return slotIndex == 0;
        if (type.equals("industrialforegoing:stonework_generate")) {
            if (options.get(0).what() instanceof AEFluidKey fluid) {
                String field = fluid.getFluid() == net.minecraft.world.level.material.Fluids.WATER
                        ? "waterConsume" : "lavaConsume";
                return ForeignCompatSupport.integer(ForeignCompatSupport.field(recipe, field)) == 0;
            }
        }
        return false;
    }

    public static List<Stat> outputs(Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        if (!isIndustrialForegoingRecipe(recipe)) return out;
        try {
            String type = ForeignCompatSupport.type(recipe);
            switch (type) {
                case "industrialforegoing:dissolution_chamber" -> {
                    add(out, ForeignCompatSupport.stack(ForeignCompatSupport.field(recipe, "output")), 1f);
                    add(out, ForeignCompatSupport.stack(ForeignCompatSupport.field(recipe, "outputFluid")), 1f);
                }
                case "industrialforegoing:crusher", "industrialforegoing:laser_drill_ore" ->
                        addIngredientResult(out, ForeignCompatSupport.field(recipe, "output"), type);
                case "industrialforegoing:fluid_extractor" -> {
                    add(out, ForeignCompatSupport.stack(ForeignCompatSupport.field(recipe, "output")), 1f);
                    float breakChance = ForeignCompatSupport.decimal(
                            ForeignCompatSupport.field(recipe, "breakChance"), 0f);
                    if (breakChance < 1f) add(out,
                            ForeignCompatSupport.stack(ForeignCompatSupport.field(recipe, "result")),
                            Math.max(0.0001f, 1f - breakChance));
                }
                case "industrialforegoing:laser_drill_fluid" -> {
                    Object tag = ForeignCompatSupport.field(recipe, "output");
                    if (tag instanceof CompoundTag compound)
                        add(out, ForeignCompatSupport.stack(
                                FluidStack.loadFluidStackFromNBT(compound)), 1f);
                }
                case "industrialforegoing:stonework_generate" ->
                        add(out, ForeignCompatSupport.stack(ForeignCompatSupport.field(recipe, "output")), 1f);
                default -> { }
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("industrialforegoing outputs", error);
        }
        return List.copyOf(out);
    }

    private static void addIngredientResult(List<Stat> out, Object value, String type) {
        if (!(value instanceof Ingredient ingredient)) return;
        var candidates = ForeignCompatSupport.items(ingredient, 0);
        if (candidates.size() > 1) {
            // TODO: IF's choice among multiple output candidates needs machine-side bytecode proof.
            ForeignCompatSupport.warn(type + " output alternatives",
                    new IllegalStateException("choosing the first representative of " + candidates.size()));
        }
        if (!candidates.isEmpty()) add(out, candidates.get(0), 1f);
    }

    private static void addResource(List<List<GenericStack>> slots,
                                    net.minecraft.world.level.material.Fluid fluid, int amount) {
        if (amount > 0) slots.add(List.of(new GenericStack(AEFluidKey.of(fluid), amount)));
    }

    private static void add(List<List<GenericStack>> slots, List<GenericStack> options) {
        if (!options.isEmpty()) slots.add(options);
    }

    private static void add(List<Stat> out, @Nullable GenericStack stack, float chance) {
        if (stack != null && stack.what() != null && stack.amount() > 0) out.add(new Stat(stack, chance));
    }
}
