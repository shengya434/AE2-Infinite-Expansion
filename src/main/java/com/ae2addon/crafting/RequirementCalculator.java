package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * BigInteger 需求树估算器。
 * <p>
 * 用途：下单前用 BigInteger 完整展开配方树，算出「最坏情况下」每个材料的
 * 总需求量（纯合成路径，不抵扣库存）。当任一材料需求超过 long 记账上限时，
 * AE2 原版模拟（long 记账）会溢出/卡死——本计算器在拦截点重新估算需求，
 * 从而把订单拆成多个安全批次。
 * <p>
 * 重要设计决策（2026-08-20 教训）：
 * <b>不抵扣库存</b>——无限存储（Mode 3）会把库存报告成 Long.MAX_VALUE 哨兵值，
 * 若按库存抵扣，需求树会因「库存全覆盖」而提前终止，链式配方（9a→1b→…）的
 * 指数级需求永远算不出来，导致拦截失效。纯配方树展开给出最坏上界，
 * 对「库存充足」的订单最多造成多余的拆批（每批快速从库存提取完成，无害）。
 * <p>
 * 保守策略（宁高勿低，保证拆批后每批都安全）：
 * - 多配方时选「单次材料总消耗最大」的配方
 * - 多选输入槽（possibleInputs）取数量最大的选项
 * - 忽略副产物抵扣
 * <p>
 * 防爆：递归路径集合防环（DFS 栈）；展开总次数上限 MAX_EXPANSIONS 兜底；
 * 每层只沿「最坏配方」单分支展开，不会因多候选配方分支爆炸。
 */
public final class RequirementCalculator {

    /** 单次估算最大展开节点数（防配方环/超深树导致卡顿） */
    private static final int MAX_EXPANSIONS = 10_000;

    private RequirementCalculator() {}

    /**
     * 估算下单 (what, amount) 的最坏材料总需求（纯合成路径，不抵扣库存）。
     *
     * @return key → 最坏总需求（BigInteger）
     */
    public static Map<AEKey, BigInteger> estimate(IGrid grid, AEKey what, long amount) {
        var needs = new HashMap<AEKey, BigInteger>();
        expand(grid, what, BigInteger.valueOf(amount),
                new HashSet<>(), needs, new int[]{0});
        return needs;
    }

    /**
     * 判断订单是否超限：任一材料的最坏总需求超过 safeLimit。
     * 纯配方树展开，反映 AE2 模拟阶段的溢出风险（与库存无关）。
     */
    public static boolean isOversized(IGrid grid, AEKey what, long amount, long safeLimit) {
        var needs = estimate(grid, what, amount);
        BigInteger limit = BigInteger.valueOf(safeLimit);
        for (var entry : needs.entrySet()) {
            if (entry.getValue().compareTo(limit) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算单批安全产物量：保证批内任一材料需求 ≤ safeLimit。
     * 以「每单位产物的最坏需求」为瓶颈反推。
     */
    public static long maxSafeBatch(IGrid grid, AEKey what, long amount, long safeLimit) {
        var needs = estimate(grid, what, amount);
        if (needs.isEmpty()) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 估算为空 what={} amount={}", what, amount);
            return amount;
        }
        BigInteger perBatch = BigInteger.valueOf(amount);
        BigInteger amountBI = BigInteger.valueOf(amount);
        StringBuilder detail = new StringBuilder();
        for (var entry : needs.entrySet()) {
            BigInteger need = entry.getValue();
            detail.append(entry.getKey()).append(" x").append(need).append(" ");
            if (need.signum() <= 0) {
                continue;
            }
            // 每单位产物需求 = ceil(need / amount)
            BigInteger perUnit = need.add(amountBI.subtract(BigInteger.ONE))
                    .divide(amountBI);
            if (perUnit.signum() <= 0) {
                continue;
            }
            BigInteger maxBatch = BigInteger.valueOf(safeLimit).divide(perUnit);
            if (maxBatch.compareTo(perBatch) < 0) {
                perBatch = maxBatch;
            }
        }
        if (perBatch.signum() <= 0) {
            return 1;
        }
        long batch = perBatch.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        long result = Math.max(1, Math.min(batch, amount));
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][debug] 估算结果: what={} amount={} needs大小={} perBatch={} 明细={}",
                what, amount, needs.size(), result, detail);
        return result;
    }

    private static void expand(IGrid grid, AEKey key, BigInteger need,
                               Set<AEKey> path, Map<AEKey, BigInteger> needs, int[] budget) {
        if (++budget[0] > MAX_EXPANSIONS) {
            return;
        }
        needs.merge(key, need, BigInteger::add);

        // 防环：当前路径已展开过该 key（如 EMC 互转 A→B→A）
        if (!path.add(key)) {
            return;
        }

        var patterns = grid.getCraftingService().getCraftingFor(key);
        if (patterns.isEmpty()) {
            // 无配方：外部提供（如无尽桶）或缺失，无法展开，需求照记
            path.remove(key);
            return;
        }

        // 选单次材料总消耗最大的配方（保守上界；单分支展开，避免候选爆炸）
        IPatternDetails worst = null;
        BigInteger worstCost = BigInteger.ZERO;
        for (var pattern : patterns) {
            BigInteger cost = patternCost(pattern);
            if (cost.compareTo(worstCost) > 0) {
                worstCost = cost;
                worst = pattern;
            }
        }
        if (worst == null) {
            path.remove(key);
            return;
        }

        long outAmount = outputAmount(worst, key);
        if (outAmount <= 0) {
            path.remove(key);
            return;
        }

        // 该配方需执行次数 = ceil(need / 单次产出)
        BigInteger times = need.add(BigInteger.valueOf(outAmount - 1))
                .divide(BigInteger.valueOf(outAmount));

        for (var input : worst.getInputs()) {
            var possible = input.getPossibleInputs();
            GenericStack chosen = null;
            for (var candidate : possible) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                if (chosen == null || candidate.amount() > chosen.amount()) {
                    chosen = candidate;
                }
            }
            if (chosen == null) {
                continue;
            }
            BigInteger perUnit = BigInteger.valueOf(chosen.amount())
                    .multiply(BigInteger.valueOf(Math.max(1, input.getMultiplier())));
            expand(grid, chosen.what(), times.multiply(perUnit),
                    path, needs, budget);
        }

        path.remove(key);
    }

    /** 单次配方的材料总消耗（多选槽取最大选项，保守上界）。 */
    private static BigInteger patternCost(IPatternDetails pattern) {
        BigInteger cost = BigInteger.ZERO;
        for (var input : pattern.getInputs()) {
            long best = 0;
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null) {
                    best = Math.max(best, candidate.amount());
                }
            }
            cost = cost.add(BigInteger.valueOf(best)
                    .multiply(BigInteger.valueOf(Math.max(1, input.getMultiplier()))));
        }
        return cost;
    }

    /** 配方输出中目标 key 的产出量（忽略副产物抵扣）。 */
    private static long outputAmount(IPatternDetails pattern, AEKey key) {
        for (var out : pattern.getOutputs()) {
            if (out != null && out.what() != null && out.what().equals(key)) {
                return out.amount();
            }
        }
        return 0;
    }
}
