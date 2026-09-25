package com.ae2addon.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集成 CPU 在**网络侧界面**上的信息叠加（2026-09-24）。
 * <p>
 * sensei 实测：AE2 自己的合成 CPU 列表里，集成 CPU 只有图标、**线程数/存储都没有**
 * （它没有 AE2 主簇，AE2 的列表拿不到数字）。服务端由
 * {@code IntegratedCpuStatusPacket} 把数值推过来，这里在合成状态界面上叠一行文字。
 * <p>
 * 只对"合成状态 / 合成 CPU"这类界面生效（按界面类名判断），不影响别的 GUI。
 */
@Mod.EventBusSubscriber(modid = com.ae2addon.AE2Addon.MODID, value = Dist.CLIENT)
public final class IntegratedCpuStatusHud {

    /** 每个集成 CPU 的最新状态（位置 → 数据） */
    public record Status(int lanes, int activeJobs, long storageBytes, int threads, boolean formed) {}

    private static final Map<BlockPos, Status> STATUS = new ConcurrentHashMap<>();

    private IntegratedCpuStatusHud() {
    }

    public static void update(BlockPos pos, int lanes, int activeJobs,
                              long storageBytes, int threads, boolean formed) {
        STATUS.put(pos.immutable(), new Status(lanes, activeJobs, storageBytes, threads, formed));
    }

    /** 该界面是不是"合成状态/合成 CPU"类界面（AE2 的 CPU 列表就在里面） */
    private static boolean isCraftingCpuScreen(Object screen) {
        String n = screen.getClass().getName();
        return n.contains("CraftingStatus") || n.contains("CraftingCPUScreen");
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (STATUS.isEmpty() || !isCraftingCpuScreen(event.getScreen())) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 只显示"本网络里"的：按距离粗筛（同网络必然在同一玩家附近的可达范围），
        // 避免把别的网络的 CPU 数字画上来。
        GuiGraphics g = event.getGuiGraphics();
        var font = mc.font;
        int y = 4;
        int x = 4;
        for (var entry : STATUS.entrySet()) {
            var st = entry.getValue();
            if (!st.formed()) {
                continue;
            }
            BlockPos pos = entry.getKey();
            double dist = Math.sqrt(mc.player.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (dist > 64) {
                continue;   // 太远，多半不是本网络
            }
            Component line = Component.literal("集成CPU ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("线程 " + st.threads())
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("｜lane " + st.lanes()
                            + "｜活跃 " + st.activeJobs()
                            + "｜存储 " + com.ae2addon.client.IntegratedCpuStatusHud
                                    .formatBytes(st.storageBytes()))
                            .withStyle(ChatFormatting.GRAY));
            g.drawString(font, line, x, y, 0xFFFFFF, true);
            y += 10;
        }
    }

    /** 字节数 → K/M/G/T 简写（无限存储就是 inf） */
    static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0";
        }
        if (bytes >= Long.MAX_VALUE / 2) {
            return "inf";
        }
        String[] units = {"B", "K", "M", "G", "T", "P"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (v >= 100 ? String.format("%.0f", v) : String.format("%.1f", v)) + units[u];
    }
}
