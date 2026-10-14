package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * AE2 附属模组提供的「额外键型」枚举（mode3「全类型无限」用；2026-10-13 sensei：
 * mode3 没覆盖附属存储）。
 * <p>
 * 为什么必须一家一家写：AE2 的 {@code AEKeyType} **没有**「列出本类型全部键」的接口
 * （javap 15.4.11 确认：只有 {@code readFromPacket/loadKeyFromTag/tryCast} 那几个），
 * 所以键只能回到各附属自己的注册表/单例去拿。
 * <p>
 * 三家都走**纯反射 + 注册名探测**（对方缺席返回空表，不 import 对方任何类）——
 * 与 {@link ChemicalCompat} 同一套零硬依赖纪律，避免「缺前置就类加载崩」那类坑。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>AppFlux 能量：{@code FluxKey.of(EnergyType)}，{@code EnergyType} 是枚举
 *       （FE / GTEU 等，各自 {@code from()} 给出所需 modid；未装的类型跳过）</li>
 *   <li>applied-botanics 魔力：{@code appbot.ae2.ManaKey.KEY}（单一键）</li>
 *   <li>arseng 源质（Ars Nouveau Source）：{@code gripe._90.arseng.me.key.SourceKey.KEY}（单一键）</li>
 * </ul>
 * ⚠ BloodMagic 的「生命源质」是**流体**，已由 {@code BuiltInRegistries.FLUID} 扫描覆盖，
 * 不在这里重复（2026-10-13 sensei 指出）。
 */
public final class ExtraKeysCompat {

    private static final String FLUX_KEY_CLASS =
            "com.glodblock.github.appflux.common.me.key.FluxKey";
    private static final String ENERGY_TYPE_CLASS =
            "com.glodblock.github.appflux.common.me.key.type.EnergyType";
    private static final String MANA_KEY_CLASS = "appbot.ae2.ManaKey";
    private static final String SOURCE_KEY_CLASS = "gripe._90.arseng.me.key.SourceKey";

    private ExtraKeysCompat() {
    }

    /** applied-botanics 魔力键（未装返回 null）。 */
    public static AEKey manaKey() {
        return singleKey(MANA_KEY_CLASS);
    }

    /** arseng 源质键（未装返回 null）。 */
    public static AEKey sourceKey() {
        return singleKey(SOURCE_KEY_CLASS);
    }

    /**
     * AppFlux 能量键：{@code FluxKey.of(EnergyType)} × 每个枚举常量。
     * <p>
     * {@code EnergyType.from()} 给出该能量类型需要的 modid（FE→appflux，GTEU→gtceu 之类）：
     * 该 mod 没装就跳过这个类型，避免报出一个永远不会被填的能量通道。
     */
    public static List<AEKey> fluxKeys() {
        List<AEKey> out = new ArrayList<>();
        try {
            Class<?> typeClass = Class.forName(ENERGY_TYPE_CLASS);
            Class<?> keyClass = Class.forName(FLUX_KEY_CLASS);
            var of = keyClass.getMethod("of", typeClass);
            var from = typeClass.getMethod("from");
            Object[] constants = typeClass.getEnumConstants();
            if (constants == null) {
                return out;
            }
            for (Object constant : constants) {
                try {
                    Object mod = from.invoke(constant);
                    if (!(mod instanceof String modId) || !ModList.get().isLoaded(modId)) {
                        continue;   // 该能量类型所需的 mod 没装
                    }
                    Object key = of.invoke(null, constant);
                    if (key instanceof AEKey aeKey && !out.contains(aeKey)) {
                        out.add(aeKey);
                    }
                } catch (Throwable ignored) {
                    // 单个能量类型跳过
                }
            }
        } catch (Throwable ignored) {
            // AppFlux 没装（或版本里没有这两个类）
        }
        return out;
    }

    /** 单例键通道：读 {@code <类>.KEY} 静态字段；类/字段不存在返回 null。 */
    private static AEKey singleKey(String className) {
        try {
            Object value = Class.forName(className).getField("KEY").get(null);
            return (value instanceof AEKey key) ? key : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
