package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;

/**
 * Mekanism 化学物 key 转换（独立类 = 惰性加载）。
 * <p>
 * 2026-09-04 崩溃修复：MekanismGasCompat 的 mekKeyOf/keyOfChemical 方法签名直接引用
 * mekanism/appmek 类 → 无 Mekanism 环境加载门面类时 NoClassDefFoundError（类加载验证期
 * 解析方法签名中的类）。这两个方法移到本独立类——只有 isLoaded() 为 true 后才会被调用，
 * 本类才被加载（方法体/方法签名引用可选依赖的规则：被加载类的「签名」必须干净，
 * 「方法体」引用惰性安全）。
 */
public final class MekanismChemCompat {

    private MekanismChemCompat() {
    }

    /** AEKey → MekanismKey（无/非化学返回 null）。调用方需保证 isLoaded() 后调用。 */
    public static MekanismKey mekKeyOf(AEKey key) {
        if (!(key instanceof MekanismKey mk)) {
            return null;
        }
        return mk;
    }

    /** 化学物（任意形态）→ AEKey（MekanismKey.of）。调用方需保证 isLoaded() 后调用。 */
    public static AEKey keyOfChemical(mekanism.api.chemical.ChemicalStack<?> stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return MekanismKey.of(stack);
    }
}
