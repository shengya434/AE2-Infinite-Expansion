package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import net.minecraftforge.fml.ModList;

/**
 * Mekanism + Applied Mekanistics 可选集成门面（2026-08-28 sensei 需求：气体）。
 * <p>
 * ⚠️ 本门面类被无 Mekanism 环境高频调用（每 tick isFeedable 判断），必须保证
 * **类可无依赖加载**：字段类型/方法签名/方法体一律不得引用 mekanism/appmek 类
 * （2026-09-04 崩溃教训：签名或方法体引用可选依赖 → 类加载验证期 NoClassDefFoundError）。
 * 需要 mekanism 类的方法全部在 {@link MekanismChemCompat}（独立惰性类，isLoaded 后才加载）。
 * <p>
 * - isFeedable 用「类名前缀」判断 MekanismKey（避免类引用；isLoaded 后与 instanceof 等价）
 * - 化学物实际操作（容器解析/喂出/形态判断）→ MekanismChemCompat
 */
public final class MekanismGasCompat {

    private static boolean checked;
    private static boolean loaded;

    private MekanismGasCompat() {
    }

    /** 运行时是否装了 Mekanism + Applied Mekanistics（唯一被无条件调用的方法）。 */
    public static boolean isLoaded() {
        if (!checked) {
            checked = true;
            loaded = ModList.get().isLoaded("mekanism")
                    && ModList.get().isLoaded("appmek");
        }
        return loaded;
    }

    /** key 的类是否属于 appmek 的化学 key 包（等价 instanceof MekanismKey，但零类引用）。 */
    private static boolean isMekKey(AEKey key) {
        return key != null
                && key.getClass().getName().startsWith("me.ramidzkh.mekae2.ae2.");
    }

    /** 该 AEKey 是否为可喂出的化学物（MekanismKey 任意形态：气体/灌注/颜料/泥浆）。 */
    public static boolean isFeedable(AEKey key) {
        return isLoaded() && isMekKey(key);
    }
}
