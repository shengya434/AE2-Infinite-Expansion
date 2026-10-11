package com.ae2addon.compat;

import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Machine recipes in PneumaticCraft whose vanilla input/result API is empty. */
public final class PneumaticCraftCompat {
    public record Stat(GenericStack stack, float chance) {}

    private static final String ROOT = "me.desht.pneumaticcraft.common.recipes.machine.";
    private static final String FLUID_INGREDIENT =
            "me.desht.pneumaticcraft.api.crafting.ingredient.FluidIngredient";
    private static final Map<String, String> TYPES = Map.of(
            "pressure_chamber", "PressureChamberRecipeImpl",
            "thermo_plant", "ThermoPlantRecipeImpl",
            "assembly_laser", "AssemblyRecipeImpl",
            "assembly_drill", "AssemblyRecipeImpl",
            "refinery", "RefineryRecipeImpl",
            "fluid_mixer", "FluidMixerRecipeImpl",
            "explosion_crafting", "ExplosionCraftingRecipeImpl",
            "heat_frame_cooling", "HeatFrameCoolingRecipeImpl");
    private static final Map<Class<?>, Boolean> MATCHES = new ConcurrentHashMap<>();

    /**
     * 「按设计排除」的集合（类型 id **或**序列化器 id 命中即算）。
     * <p>
     * - {@code pressure_chamber_enchanting} / {@code _disenchanting}：构造器就是
     *   {@code Collections.emptyList()} + {@code new ItemStack[0]}，JSON 只有 {@code {"type","category"}} ——
     *   配方里**压根没有输入产出**（运行时靠覆写的 {@code findIngredients}/{@code craftRecipe} 现算）。
     *   不是"读不到"，是"不存在"，所以不算失败。
     * - {@code heat_properties}（方块热学）/ {@code fuel_quality}（流体热值）：字段里没有物品产出
     * - {@code amadron}：玩家交易表，不是配方
     */
    private static final Set<String> EXCLUDED = Set.of(
            "pneumaticcraft:pressure_chamber_enchanting",
            "pneumaticcraft:pressure_chamber_disenchanting",
            "pneumaticcraft:heat_properties",
            "pneumaticcraft:fuel_quality",
            "pneumaticcraft:amadron");

    private PneumaticCraftCompat() {}

    public static boolean isPneumaticCraftRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return MATCHES.computeIfAbsent(recipe.getClass(), owner -> {
            String type = ForeignCompatSupport.type(recipe);
            String expected = type.startsWith("pneumaticcraft:")
                    ? TYPES.get(type.substring("pneumaticcraft:".length())) : null;
            return expected != null && owner.getName().equals(ROOT + expected);
        });
    }

    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        // ⚠ 类型 id 与**序列化器** id 都要看：附魔/祛魔两条的 getType() 仍是
        // pneumaticcraft:pressure_chamber（它们是 PressureChamberRecipeImpl 的子类，
        // 只有序列化器不同）→ 只看类型 id 会让这 2 条永远挂在「提取失败」列（2026-10-11 实测）。
        return EXCLUDED.contains(ForeignCompatSupport.type(recipe))
                || EXCLUDED.contains(ForeignCompatSupport.serializer(recipe));
    }

    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (!isPneumaticCraftRecipe(recipe)) return slots;
        try {
            switch (ForeignCompatSupport.type(recipe)) {
                case "pneumaticcraft:pressure_chamber" -> {
                    for (Object input : ForeignCompatSupport.elements(
                            ForeignCompatSupport.call(recipe, "getInputsForDisplay"))) add(slots, ingredient(input, 0));
                }
                case "pneumaticcraft:thermo_plant" -> {
                    add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInputFluid"), 0));
                    add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInputItem"), 0));
                }
                case "pneumaticcraft:assembly_laser", "pneumaticcraft:assembly_drill" ->
                        add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInput"),
                                ForeignCompatSupport.integer(ForeignCompatSupport.call(recipe, "getInputAmount"))));
                case "pneumaticcraft:refinery", "pneumaticcraft:heat_frame_cooling" ->
                        add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInput"), 0));
                case "pneumaticcraft:fluid_mixer" -> {
                    add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInput1"), 0));
                    add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInput2"), 0));
                }
                case "pneumaticcraft:explosion_crafting" ->
                        add(slots, ingredient(ForeignCompatSupport.call(recipe, "getInput"),
                                ForeignCompatSupport.integer(ForeignCompatSupport.call(recipe, "getAmount"))));
                default -> { }
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("pneumaticcraft inputs", error);
        }
        return List.copyOf(slots);
    }

    private static List<GenericStack> ingredient(@Nullable Object value, long amount) {
        if (ForeignCompatSupport.isA(value, FLUID_INGREDIENT)) {
            long fluidAmount = ForeignCompatSupport.integer(ForeignCompatSupport.call(value, "getAmount"));
            if (fluidAmount <= 0) return List.of();
            return ForeignCompatSupport.fluids(ForeignCompatSupport.call(value, "getFluidStacks"), fluidAmount);
        }
        return ForeignCompatSupport.items(value, amount);
    }

    private static void add(List<List<GenericStack>> slots, List<GenericStack> options) {
        if (!options.isEmpty()) slots.add(options);
    }

    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        return false;
    }

    public static List<Stat> outputs(Recipe<?> recipe) {
        var result = new ArrayList<Stat>();
        if (!isPneumaticCraftRecipe(recipe)) return result;
        try {
            switch (ForeignCompatSupport.type(recipe)) {
                case "pneumaticcraft:pressure_chamber" -> {
                    for (Object group : ForeignCompatSupport.elements(
                            ForeignCompatSupport.call(recipe, "getResultsForDisplay")))
                        for (Object value : ForeignCompatSupport.elements(group))
                            add(result, ForeignCompatSupport.stack(value), 1);
                }
                case "pneumaticcraft:thermo_plant", "pneumaticcraft:fluid_mixer" -> {
                    add(result, ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "getOutputFluid")), 1);
                    add(result, ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "getOutputItem")), 1);
                }
                case "pneumaticcraft:assembly_laser", "pneumaticcraft:assembly_drill" ->
                        add(result, ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "getOutput")), 1);
                case "pneumaticcraft:heat_frame_cooling" -> {
                    GenericStack stack = ForeignCompatSupport.stack(ForeignCompatSupport.call(recipe, "getOutput"));
                    if (stack != null) add(result, new GenericStack(stack.what(), 1), 0.5f);
                }
                case "pneumaticcraft:refinery" -> {
                    for (Object value : ForeignCompatSupport.elements(ForeignCompatSupport.call(recipe, "getOutputs")))
                        add(result, ForeignCompatSupport.stack(value), 1);
                }
                case "pneumaticcraft:explosion_crafting" -> {
                    float loss = ForeignCompatSupport.decimal(ForeignCompatSupport.call(recipe, "getLossRate"), 0);
                    float chance = Math.max(0.0001f, Math.min(0.9999f, 1f - loss / 100f));
                    for (Object value : ForeignCompatSupport.elements(ForeignCompatSupport.call(recipe, "getOutputs"))) {
                        GenericStack stack = ForeignCompatSupport.stack(value);
                        if (stack != null) add(result, new GenericStack(stack.what(), 1), chance);
                    }
                }
                default -> { }
            }
        } catch (Throwable error) {
            ForeignCompatSupport.warn("pneumaticcraft outputs", error);
        }
        return List.copyOf(result);
    }

    private static void add(List<Stat> out, @Nullable GenericStack stack, float chance) {
        if (stack != null && stack.what() != null && stack.amount() > 0)
            out.add(new Stat(stack, chance));
    }
}
