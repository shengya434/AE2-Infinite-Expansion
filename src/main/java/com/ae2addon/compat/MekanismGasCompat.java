package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.Action;
import mekanism.api.chemical.gas.GasStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;

/**
 * Mekanism + Applied Mekanistics 可选集成（2026-08-28 sensei 需求：气体）。
 * <p>
 * - 标记槽：气罐/气桶（GAS_HANDLER 物品能力）→ 标记内部气体（MekanismKey）
 * - 喂出：MekanismKey（气体）→ 机器气体槽 insertChemical
 * - 其余化学形态（灌注/颜料/泥浆）暂不喂出
 * <p>
 * compileOnly 依赖（libs/ 下两个 jar），运行时未装 Mekanism/Applied Mekanistics
 * 时 isLoaded() 为 false，所有方法短路返回，不影响主功能。
 * 注意：引用 Mekanism/appmek 类的方法只能在 isLoaded() 为 true 后调用
 * （JVM 按方法体懒加载类，标准可选集成模式）。
 */
public final class MekanismGasCompat {

    private static boolean checked;
    private static boolean loaded;
    /** 诊断日志节流：距上次日志的毫秒数。 */
    private static long lastDiagLog;

    private MekanismGasCompat() {
    }

    /** 节流诊断日志（每 5 秒最多一条）。 */
    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastDiagLog < 5000) {
            return;
        }
        lastDiagLog = now;
        com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][feeder] 气体喂出失败: {}", msg);
    }

    public static boolean isLoaded() {
        if (!checked) {
            checked = true;
            loaded = ModList.get().isLoaded("mekanism")
                    && ModList.get().isLoaded("appmek");
        }
        return loaded;
    }

    /** 标记槽：气体容器（气罐/气桶）→ 内部气体 AEKey；非气体容器返回 null。 */
    public static AEKey chemicalInContainer(ItemStack stack) {
        if (!isLoaded() || stack.isEmpty()) {
            return null;
        }
        try {
            var cap = stack.getCapability(Capabilities.GAS_HANDLER);
            if (cap.isPresent()) {
                var handler = cap.orElse(null);
                if (handler != null && handler.getTanks() > 0) {
                    var chemical = handler.getChemicalInTank(0);
                    if (chemical != null && !chemical.isEmpty()) {
                        return MekanismKey.of(chemical);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 非气体容器/能力异常：按非气体处理
        }
        return null;
    }

    /** 该 AEKey 是否为可喂出的气体（MekanismKey GAS 形态）。 */
    public static boolean isFeedable(AEKey key) {
        return isLoaded() && key instanceof MekanismKey mekKey
                && mekKey.getForm() == MekanismKey.GAS;
    }

    /** 化学物 JEI 拖取 → AEKey（MekanismKey.of，气体/灌注/颜料/泥浆均可）。 */
    public static AEKey keyOfChemical(mekanism.api.chemical.ChemicalStack<?> stack) {
        if (!isLoaded() || stack == null || stack.isEmpty()) {
            return null;
        }
        return MekanismKey.of(stack);
    }

    /** 喂出：把气体的 amount 量插入机器气体槽；返回实际喂出量（0=机器满/拒收）。
     * 指定面优先，找不到气体槽时遍历机器所有面。带失败原因诊断（节流）。 */
    public static long feed(BlockEntity target, Direction side, AEKey key, long amount) {
        if (!isFeedable(key) || amount <= 0) {
            return 0;
        }
        try {
            MekanismKey mekKey = (MekanismKey) key;
            var handler = findGasHandler(target, side);
            if (handler == null) {
                diag("机器无气体槽（" + machineName(target) + " side=" + side + "）");
                return 0;
            }
            if (handler.getTanks() <= 0) {
                diag("机器气体槽数为0（" + machineName(target) + "）");
                return 0;
            }
            var stack = mekKey.getStack();
            if (!(stack instanceof GasStack gasStack) || gasStack.isEmpty()) {
                diag("key 内部不是 GasStack: " + (stack == null ? "null" : stack.getClass().getSimpleName()));
                return 0;
            }
            long insertAmount = Math.min(amount, Integer.MAX_VALUE);
            // 复制栈：不改动 key 内部缓存的化学物
            var insert = new GasStack(gasStack, insertAmount);
            var leftover = handler.insertChemical(insert, Action.EXECUTE);
            long fed = insertAmount - leftover.getAmount();
            if (fed <= 0) {
                diag("机器拒收气体 " + gasStack.getType() + "（尝试 " + insertAmount
                        + " mB；槽满或不吃该气体）");
            }
            return Math.max(0, fed);
        } catch (RuntimeException e) {
            diag("异常: " + e);
            return 0;
        }
    }

    private static String machineName(BlockEntity target) {
        try {
            return target.getBlockState().getBlock().getName().getString();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 查找机器气体 handler：指定面优先，找不到遍历其余面。 */
    private static mekanism.api.chemical.gas.IGasHandler findGasHandler(
            BlockEntity target, Direction primary) {
        if (target == null) {
            return null;
        }
        var cap = target.getCapability(Capabilities.GAS_HANDLER, primary);
        if (cap.isPresent()) {
            var handler = cap.orElse(null);
            if (handler != null) {
                return handler;
            }
        }
        for (Direction side : Direction.values()) {
            if (side == primary) {
                continue;
            }
            var c = target.getCapability(Capabilities.GAS_HANDLER, side);
            if (c.isPresent()) {
                var handler = c.orElse(null);
                if (handler != null) {
                    return handler;
                }
            }
        }
        return null;
    }
}
