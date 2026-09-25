package com.ae2addon.debug;

import appeng.api.crafting.IPatternDetails;
import com.ae2addon.AE2Addon;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务值诊断（2026-09-18 晚）。
 * <p>
 * 为什么单独放一个类、为什么挂在 Forge 的 server tick 上：
 * 之前的 `[stuck]` / `[task开始]` 诊断挂在 {@code CraftingCpuLogicMixin} 的
 * {@code Iterator.hasNext()} 重定向里 —— 那条注入**在这台机器的 AE2 版本上明显没生效**
 * （日志里一条都没打出来）。而 Forge 的 {@code ServerTickEvent} 是必然会被调用的，
 * 所以把"每单第一次看到任务值是多少"这种关键取证放在这里，别再依赖可能不注入的钩子。
 * <p>
 * 它回答的问题：sensei 下单 40000（1粉转1锭）却产出 4195328 = 512 × 8194，
 * 到底是**登记进 CPU 的任务值本身就被放大**，还是我们结算侧多给了。
 */
public final class TaskValueProbe {

    private TaskValueProbe() {}

    /** 已打印过的 CPU（按 identity 去重；任务结束（job 为 null）时移除，让下一单能再打） */
    private static final Set<Object> REPORTED = ConcurrentHashMap.newKeySet();
    private static long lastScanTick = Long.MIN_VALUE;

    private static volatile Field tasksField;
    private static volatile Field valueField;
    private static volatile boolean reflectionBroken;

    private static final java.util.List<Object> LIVE_CPUS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean trackLogged;
    private static volatile boolean tickLogged;

    /** 由 CraftingCpuLogicMixin 的每 tick 钩子登记"当前活跃的 CPU 逻辑对象" */
    public static void track(Object craftingLogic) {
        if (craftingLogic == null) return;
        if (!LIVE_CPUS.contains(craftingLogic)) {
            LIVE_CPUS.add(craftingLogic);
            if (!trackLogged) {
                trackLogged = true;
                AE2Addon.LOGGER.info("[ae2addon][probe] track() 生效：已登记 CPU 逻辑对象 {}（共 {} 个）",
                        craftingLogic.getClass().getName(), LIVE_CPUS.size());
            }
        }
    }

    /**
     * 每秒取证一次。**由 {@code BatchedCraftingQueue.onServerTick} 直接调用** ——
     * 那条路径已被日志证明会执行；本类自己注册 EVENT_BUS 的版本实测没被调用过。
     */
    public static void tick(@Nullable net.minecraft.server.MinecraftServer server) {
        if (server == null || reflectionBroken) {
            return;
        }
        long tick = server.getTickCount();
        if (tick - lastScanTick < 20L) {          // 每秒扫一次
            return;
        }
        lastScanTick = tick;
        if (!tickLogged) {
            tickLogged = true;
            AE2Addon.LOGGER.info("[ae2addon][probe] tick() 生效：已登记 CPU={} 个，reflectionBroken={}",
                    LIVE_CPUS.size(), reflectionBroken);
        }
        for (var logic : LIVE_CPUS) {
            try {
                probe(logic);
            } catch (Throwable t) {
                if (!probeErrorLogged) {
                    probeErrorLogged = true;
                    AE2Addon.LOGGER.info("[ae2addon][probe] 取证异常: {}", t.toString());
                }
            }
        }
    }

    private static volatile boolean probeErrorLogged;
    private static volatile boolean jobFieldMissLogged;

    @SuppressWarnings("unchecked")
    private static void probe(Object logic) throws ReflectiveOperationException {
        var jobField = findJobField(logic.getClass());
        if (jobField == null) {
            if (!jobFieldMissLogged) {
                jobFieldMissLogged = true;
                AE2Addon.LOGGER.info("[ae2addon][probe] 找不到 job 字段：{}（父类链也找过）",
                        logic.getClass().getName());
            }
            return;
        }
        jobField.setAccessible(true);
        var job = jobField.get(logic);
        if (job == null) {
            REPORTED.remove(logic);   // 任务结束：下一单重新取证
            return;
        }
        if (!REPORTED.add(logic)) {
            return;                   // 本单已取证
        }
        if (tasksField == null) {
            tasksField = job.getClass().getDeclaredField("tasks");
            tasksField.setAccessible(true);
        }
        var tasks = tasksField.get(job);
        if (!(tasks instanceof Map<?, ?> map)) {
            AE2Addon.LOGGER.info("[ae2addon][task开始] tasks 字段不是 Map：{}",
                    tasks == null ? "null" : tasks.getClass().getName());
            return;
        }
        var sb = new StringBuilder("[ae2addon][task开始] 任务数=").append(map.size());
        int shown = 0;
        for (var e : map.entrySet()) {
            if (shown++ >= 4) { sb.append(" …"); break; }
            long value = -1L;
            try {
                if (valueField == null) {
                    valueField = e.getValue().getClass().getDeclaredField("value");
                    valueField.setAccessible(true);
                }
                value = valueField.getLong(e.getValue());
            } catch (Throwable ignored) {
            }
            var pattern = e.getKey();
            sb.append(" | ").append(pattern == null ? "null" : pattern.getClass().getSimpleName())
                    .append(" 任务值=").append(value)
                    .append(" 产出=").append(describeOutputs(pattern));
        }
        AE2Addon.LOGGER.info("{}", sb);
    }

    /** 样板声明的产出（AE2 计划就是按这个算"要做多少次"的） */
    private static String describeOutputs(Object pattern) {
        try {
            if (!(pattern instanceof IPatternDetails details)) return "?";
            var outs = details.getOutputs();
            if (outs == null || outs.length == 0) return "无";
            var sb = new StringBuilder("[");
            for (int i = 0; i < outs.length && i < 3; i++) {
                if (outs[i] == null || outs[i].what() == null) continue;
                if (i > 0) sb.append(' ');
                sb.append(outs[i].what().getDisplayName().getString())
                        .append('×').append(outs[i].amount());
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Field findJobField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : new String[]{"job", "craftingJob", "currentJob"}) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }
}
