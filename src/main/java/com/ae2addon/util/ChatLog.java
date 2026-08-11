package com.ae2addon.util;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 弹出日志工具：把关键事件广播到所有在线玩家的聊天栏，方便排错。
 * <p>
 * 用法：ChatLog.ok(level, pos, "成型成功");
 * 颜色约定：✔ 绿 = 成功 / ⚠ 黄 = 警告 / ✗ 红 = 错误 / ℹ 青 = 信息
 * <p>
 * 自带限频：同一内容 2 秒内最多广播一次，防止高频合成刷屏卡顿。
 */
public final class ChatLog {

    private static final String PREFIX = "§8[§bAE2Addon§8] §r";
    private static final long COOLDOWN_MS = 2000;
    /** 按消息前缀限频（Server thread 单线程访问，HashMap 安全） */
    private static final Map<String, Long> lastBroadcast = new HashMap<>();

    private ChatLog() {}

    /** 成功（绿色 ✔） */
    public static void ok(Level level, BlockPos pos, String msg) {
        log(level, pos, ChatFormatting.GREEN + "✔ " + msg);
    }

    /** 警告（黄色 ⚠） */
    public static void warn(Level level, BlockPos pos, String msg) {
        log(level, pos, ChatFormatting.YELLOW + "⚠ " + msg);
    }

    /** 错误（红色 ✗） */
    public static void err(Level level, BlockPos pos, String msg) {
        log(level, pos, ChatFormatting.RED + "✗ " + msg);
    }

    /** 信息（青色 ℹ） */
    public static void info(Level level, BlockPos pos, String msg) {
        log(level, pos, ChatFormatting.AQUA + "ℹ " + msg);
    }

    /** 原始消息（不带前缀格式） */
    public static void raw(Level level, BlockPos pos, String msg) {
        log(level, pos, msg);
    }

    private static void log(Level level, BlockPos pos, String msg) {
        if (level == null || level.isClientSide) return;

        // 限频：同前缀消息 2 秒内只广播一次（高频合成时防止刷屏/卡顿）
        String key = msg.length() > 20 ? msg.substring(0, 20) : msg;
        long now = System.currentTimeMillis();
        Long last = lastBroadcast.get(key);
        if (last != null && now - last < COOLDOWN_MS) return;
        lastBroadcast.put(key, now);
        if (lastBroadcast.size() > 200) lastBroadcast.clear();

        String location = pos != null
                ? " §7(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")§r"
                : "";
        Component component = Component.literal(PREFIX + msg + location);
        for (var player : level.players()) {
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(component, false);
            }
        }
    }
}
