package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.Action;
import mekanism.api.chemical.gas.GasStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Mekanism 化学物实际操作（独立类 = 惰性加载，2026-09-04 崩溃修复）。
 * <p>
 * 本类所有方法都引用 mekanism/appmek 类（签名或方法体）——**只能在
 * {@link MekanismGasCompat#isLoaded()} 为 true 后调用**，此时本类才被加载。
 * 门面 MekanismGasCompat 保持零 mekanism 引用（无依赖环境每 tick 调用安全）。
 */
public final class MekanismChemCompat {

    /** 诊断日志节流：距上次日志的毫秒数。 */
    private static long lastDiagLog;

    private MekanismChemCompat() {
    }

    /** 节流诊断日志（每 5 秒最多一条）。 */
    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastDiagLog < 5000) {
            return;
        }
        lastDiagLog = now;
        com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][feeder] 化学物喂出失败: {}", msg);
    }

    /** 标记槽：化学容器（气罐/灌注罐/颜料罐/泥浆罐）→ 内部化学物 AEKey；非容器返回 null。 */
    public static AEKey chemicalInContainer(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        try {
            AEKey key = chemicalInHandler(stack, Capabilities.GAS_HANDLER);
            if (key != null) {
                return key;
            }
            key = chemicalInHandler(stack, Capabilities.INFUSION_HANDLER);
            if (key != null) {
                return key;
            }
            key = chemicalInHandler(stack, Capabilities.PIGMENT_HANDLER);
            if (key != null) {
                return key;
            }
            key = chemicalInHandler(stack, Capabilities.SLURRY_HANDLER);
            return key;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static AEKey chemicalInHandler(ItemStack stack,
            net.minecraftforge.common.capabilities.Capability<?> cap) {
        var lo = stack.getCapability(cap);
        if (!lo.isPresent()) {
            return null;
        }
        Object handler = lo.orElse(null);
        if (!(handler instanceof mekanism.api.chemical.IChemicalHandler<?, ?> ch)
                || ch.getTanks() <= 0) {
            return null;
        }
        var chemical = (mekanism.api.chemical.ChemicalStack<?>) ch.getChemicalInTank(0);
        if (chemical != null && !chemical.isEmpty()) {
            return MekanismKey.of(chemical);
        }
        return null;
    }

    /** 是否气体形态（侧面抽取预览用；调用方需保证 isLoaded）。 */
    public static boolean isGas(AEKey key) {
        if (!(key instanceof MekanismKey mk)) {
            return false;
        }
        try {
            return mk.getForm() == MekanismKey.GAS;
        } catch (RuntimeException e) {
            return false;
        }
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

    /** 喂出：把化学物的 amount 量插入机器对应槽（气体/灌注/颜料/泥浆）；
     * 返回实际喂出量（0=机器满/拒收）。带失败原因诊断（节流）。调用方需保证 isLoaded。 */
    public static long feed(BlockEntity target, Direction side, AEKey key, long amount) {
        if (!(key instanceof MekanismKey) || amount <= 0) {
            // 早退也诊断：key 类型/form 不对或蓄水池里根本没化学物
            diag("非化学 key 或 amount<=0: key="
                    + (key == null ? "null" : key.getClass().getSimpleName())
                    + " amount=" + amount);
            return 0;
        }
        try {
            MekanismKey mekKey = (MekanismKey) key;
            byte form = mekKey.getForm();
            var stack = mekKey.getStack();
            if (stack == null || stack.isEmpty()) {
                diag("key 内部化学物为空");
                return 0;
            }
            long insertAmount = Math.min(amount, Integer.MAX_VALUE);
            long fed = switch (form) {
                case MekanismKey.GAS -> {
                    if (!(stack instanceof GasStack gasStack)) {
                        diag("form=气体 但内部不是 GasStack: " + stack.getClass().getSimpleName());
                        yield 0;
                    }
                    var handler = findHandler(target, side, Capabilities.GAS_HANDLER);
                    if (handler == null) {
                        diag("机器无气体槽（" + machineName(target) + "）");
                        yield 0;
                    }
                    var leftover = handler.insertChemical(
                            new GasStack(gasStack, insertAmount), Action.EXECUTE);
                    yield insertAmount - leftover.getAmount();
                }
                case MekanismKey.INFUSION -> {
                    if (!(stack instanceof mekanism.api.chemical.infuse.InfusionStack infuseStack)) {
                        diag("form=灌注 但内部不是 InfusionStack");
                        yield 0;
                    }
                    var handler = findHandler(target, side, Capabilities.INFUSION_HANDLER);
                    if (handler == null) {
                        diag("机器无灌注槽（" + machineName(target) + "）");
                        yield 0;
                    }
                    var leftover = handler.insertChemical(
                            new mekanism.api.chemical.infuse.InfusionStack(infuseStack, insertAmount),
                            Action.EXECUTE);
                    yield insertAmount - leftover.getAmount();
                }
                case MekanismKey.PIGMENT -> {
                    if (!(stack instanceof mekanism.api.chemical.pigment.PigmentStack pigmentStack)) {
                        diag("form=颜料 但内部不是 PigmentStack");
                        yield 0;
                    }
                    var handler = findHandler(target, side, Capabilities.PIGMENT_HANDLER);
                    if (handler == null) {
                        diag("机器无颜料槽（" + machineName(target) + "）");
                        yield 0;
                    }
                    var leftover = handler.insertChemical(
                            new mekanism.api.chemical.pigment.PigmentStack(pigmentStack, insertAmount),
                            Action.EXECUTE);
                    yield insertAmount - leftover.getAmount();
                }
                case MekanismKey.SLURRY -> {
                    if (!(stack instanceof mekanism.api.chemical.slurry.SlurryStack slurryStack)) {
                        diag("form=泥浆 但内部不是 SlurryStack");
                        yield 0;
                    }
                    var handler = findHandler(target, side, Capabilities.SLURRY_HANDLER);
                    if (handler == null) {
                        diag("机器无泥浆槽（" + machineName(target) + "）");
                        yield 0;
                    }
                    var leftover = handler.insertChemical(
                            new mekanism.api.chemical.slurry.SlurryStack(slurryStack, insertAmount),
                            Action.EXECUTE);
                    yield insertAmount - leftover.getAmount();
                }
                default -> {
                    diag("未知 form=" + form);
                    yield 0;
                }
            };
            if (fed <= 0 && form == MekanismKey.GAS) {
                diag("机器拒收气体（尝试 " + insertAmount + " mB；槽满或不吃）");
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

    /** 查找机器化学 handler（泛型）：指定面优先，找不到遍历其余面。 */
    private static <H> H findHandler(BlockEntity target, Direction primary,
            net.minecraftforge.common.capabilities.Capability<H> cap) {
        if (target == null) {
            return null;
        }
        var lo = target.getCapability(cap, primary);
        if (lo.isPresent()) {
            H handler = lo.orElse(null);
            if (handler != null) {
                return handler;
            }
        }
        for (Direction side : Direction.values()) {
            if (side == primary) {
                continue;
            }
            var c = target.getCapability(cap, side);
            if (c.isPresent()) {
                H handler = c.orElse(null);
                if (handler != null) {
                    return handler;
                }
            }
        }
        return null;
    }
}
