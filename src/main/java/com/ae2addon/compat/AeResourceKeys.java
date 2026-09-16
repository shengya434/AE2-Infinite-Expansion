package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桥 mod 的「非物品资源」AEKey 单例：**魔力**（Applied Botanics）与**魔源**（Ars Énergistique）。
 * <p>
 * 2026-09-16 sensei 指派：千机要能从 ME 网络抽取魔力/魔源。这两样都不是物品，但桥 mod 把它们注册成了
 * AE2 的 {@link AEKey}，所以直接塞进现有的 {@link GenericStack} 样板模型即可 —— **不用改数据结构**；
 * 抽取由 AE2 合成 CPU 负责，执行层也不用动。
 * <p>
 * <b>sensei 定调：只在加载了对应 AE 附属时才产生这些槽</b> —— 类找不到就返回 {@code null}，
 * 配方自然不加这一项。全部反射，零硬依赖（跟 {@link CreateCompat} 一个风格）。
 * <p>
 * 取证（读 jar 字节码，2026-09-16）：
 * <pre>
 *   appbot.ae2.ManaKey                  extends appeng.api.stacks.AEKey，静态单例字段 KEY
 *   gripe._90.arseng.me.key.SourceKey   extends appeng.api.stacks.AEKey，&lt;clinit&gt; 里 new → KEY
 * </pre>
 */
public final class AeResourceKeys {

    /** Applied Botanics（应用植物学）：魔力 key */
    private static final String MANA_KEY_CLASS = "appbot.ae2.ManaKey";
    /** Ars Énergistique：魔源 key */
    private static final String SOURCE_KEY_CLASS = "gripe._90.arseng.me.key.SourceKey";

    /** 已解析到的单例（类名 → AEKey） */
    private static final Map<String, AEKey> RESOLVED = new ConcurrentHashMap<>();
    /** 「这个类根本不存在」= 没装那个 mod，永久判定（有类但单例还没初始化的不记这里，下次再试） */
    private static final Map<String, Boolean> ABSENT = new ConcurrentHashMap<>();

    private AeResourceKeys() {}

    /** 魔力输入槽；没装 Applied Botanics → {@code null}（= 不加槽） */
    @Nullable
    public static GenericStack mana(long amount) {
        return stack(MANA_KEY_CLASS, amount);
    }

    /** 魔源输入槽；没装 Ars Énergistique → {@code null}（= 不加槽） */
    @Nullable
    public static GenericStack source(long amount) {
        return stack(SOURCE_KEY_CLASS, amount);
    }

    /** 魔力能不能用（装了桥且单例已就绪） */
    public static boolean manaAvailable() {
        return key(MANA_KEY_CLASS) != null;
    }

    /** 魔源能不能用（装了桥且单例已就绪） */
    public static boolean sourceAvailable() {
        return key(SOURCE_KEY_CLASS) != null;
    }

    @Nullable
    private static GenericStack stack(String className, long amount) {
        if (amount <= 0) return null;
        AEKey key = key(className);
        return key == null ? null : new GenericStack(key, amount);
    }

    @Nullable
    private static AEKey key(String className) {
        AEKey cached = RESOLVED.get(className);
        if (cached != null) return cached;
        if (Boolean.TRUE.equals(ABSENT.get(className))) return null;
        AEKey found = resolve(className);
        if (found != null) RESOLVED.put(className, found);
        return found;
    }

    /**
     * 拿单例：先扫静态字段里「类型就是这个类自己」的那个（不写死字段名，KEY / INSTANCE 都能中），
     * 没有就退回无参构造。
     */
    @Nullable
    private static AEKey resolve(String className) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, AeResourceKeys.class.getClassLoader());
        } catch (Throwable notPresent) {
            ABSENT.put(className, Boolean.TRUE);   // 没有这个 mod 的类 → 不必反复重试
            return null;
        }

        AEKey firstNonNull = null;
        try {
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!AEKey.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object value = f.get(null);
                if (!(value instanceof AEKey key)) continue;
                if ("KEY".equals(f.getName())) return key;   // 约定名优先
                if (firstNonNull == null) firstNonNull = key;
            }
        } catch (Throwable ignored) {
            // 字段读不到 → 走下面的构造回退
        }
        if (firstNonNull != null) return firstNonNull;

        try {
            var ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            if (ctor.newInstance() instanceof AEKey key) return key;
        } catch (Throwable ignored) {
            // 没有无参构造 / 还不能实例化 → 当作「暂时拿不到」，下次再试
        }
        return null;
    }
}
