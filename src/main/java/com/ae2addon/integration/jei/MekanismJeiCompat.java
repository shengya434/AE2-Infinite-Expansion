package com.ae2addon.integration.jei;

import appeng.api.stacks.AEKey;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.ingredients.IIngredientType;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Mekanism 化学物 ↔ JEI ingredient 的桥（**反射**，Mekanism 自带 JEI 插件注册了化学物 ingredient 类型）。
 * <p>
 * 2026-09-15 sensei 纠正：「非物品全归类到流体槽位」是既定设计 —— 所以千机 JEI 页里的
 * 气体/灌注/颜料/浆液要**照设计落进流体槽渲染**，不能只写一行文字。
 * <ul>
 *   <li>AE 侧：Applied-Mekanistics 的 {@code MekanismKey.withAmount(long)} → 带数量的 ChemicalStack</li>
 *   <li>JEI 侧：{@code MekanismJEI.TYPE_GAS / TYPE_INFUSION / TYPE_PIGMENT / TYPE_SLURRY}</li>
 * </ul>
 * 任一侧缺失（没装 MEK / 没装 Applied-Mekanistics）→ {@link #available()} false，调用方退回文字提示。
 */
final class MekanismJeiCompat {

    private static final Class<?> MEK_KEY = classOrNull("me.ramidzkh.mekae2.ae2.MekanismKey");
    private static final Method WITH_AMOUNT = methodOrNull(MEK_KEY, "withAmount", long.class);
    private static final Class<?> MEK_JEI = classOrNull("mekanism.client.jei.MekanismJEI");

    /** 化学物 stack 类名 → MekanismJEI 的 IIngredientType 字段名 */
    private static final Map<String, String> TYPE_FIELDS = Map.of(
            "GasStack", "TYPE_GAS",
            "InfusionStack", "TYPE_INFUSION",
            "PigmentStack", "TYPE_PIGMENT",
            "SlurryStack", "TYPE_SLURRY");

    private MekanismJeiCompat() {}

    /** 两侧都在位才谈得上渲染化学槽 */
    static boolean available() {
        return WITH_AMOUNT != null && MEK_JEI != null;
    }

    /**
     * 把 AE 侧的化学物键塞进 JEI 槽位。
     *
     * @return false = 没渲染（调用方退回文字提示）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean addChemical(IIngredientAcceptor<?> acceptor, AEKey key, long amount) {
        if (acceptor == null || key == null || !available() || MEK_KEY == null) return false;
        if (!MEK_KEY.isInstance(key)) return false;
        try {
            Object stack = WITH_AMOUNT.invoke(key, Math.max(1, amount));
            if (stack == null) return false;
            IIngredientType type = typeFor(stack);
            if (type == null) return false;
            ((IIngredientAcceptor) acceptor).addIngredient(type, stack);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IIngredientType typeFor(Object chemicalStack) {
        String field = TYPE_FIELDS.get(chemicalStack.getClass().getSimpleName());
        if (field == null) return null;
        try {
            Object value = MEK_JEI.getField(field).get(null);
            return value instanceof IIngredientType<?> type ? type : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method methodOrNull(Class<?> clazz, String name, Class<?>... params) {
        if (clazz == null) return null;
        try {
            return clazz.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }
}
