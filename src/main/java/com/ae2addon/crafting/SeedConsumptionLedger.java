package com.ae2addon.crafting;

import appeng.api.stacks.AEKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「被合成自己吃掉的产物」台账（2026-09-27）。
 * <p>
 * 背景（sensei：1e12 模板单，材料扣对了但产物少 1.23e9）：
 * 自指配方（本例 {@code 1模板+7钻石+1下界岩 → 2模板}）里，**产物同时是输入**。
 * 模组按订单量"虚拟生产"了 1e12 个模板，但真实执行的那部分合成又从池子里吃掉了
 * 2,145,384,446 个当种子 ⇒ 净交付 = 毛产量 − 被吃掉的量，于是比订单少。
 * <p>
 * 已实测闭合（一位不差）：
 * <pre>
 * 毛产量(回流合计) 1,000,918,218,750 − 吃掉 2,145,384,446 = 交付 998,772,834,304
 * </pre>
 * 修法（sensei 已批准「净交付 = 订单量」）：把被吃掉的量折成"任务值"补回去，
 * 让模组多生产同样多，净交付就回到订单量。
 * <p>
 * 数据来源：{@code CraftingCpuHelper.extractTemplates} 的返回值（= 真实扣走的模板数），
 * 由 {@code CraftingCpuHelperMixin} 记账。放普通类里是为了跨 mixin 安全读取。
 */
public final class SeedConsumptionLedger {

    /** key → 累计被合成吃掉的量（跨任务累计；调用方用"差值"消费，不需要清） */
    private static final Map<AEKey, AtomicLong> EATEN = new ConcurrentHashMap<>();

    private SeedConsumptionLedger() {
    }

    public static void record(AEKey key, long amount) {
        if (key == null || amount <= 0) {
            return;
        }
        EATEN.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(amount);
    }

    public static long total(AEKey key) {
        if (key == null) {
            return 0L;
        }
        AtomicLong v = EATEN.get(key);
        return v == null ? 0L : v.get();
    }
}
