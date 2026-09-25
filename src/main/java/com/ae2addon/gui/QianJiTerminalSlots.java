package com.ae2addon.gui;

import appeng.api.networking.IGrid;
import com.ae2addon.AE2Addon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.function.Supplier;

/**
 * 无线形态的**物品 NBT 样板槽**（2026-09-21 v273）。
 * <p>
 * 谁用：自家无线终端（{@code QianJiWirelessHost}）与 AE2WTLib 通用终端上的千机状态
 * （{@code QianJiUniversalHost}）。两者的差别只是宿主基类，槽位规则与持久化完全一样 ——
 * 所以逻辑只留这一份（槽位规则本身在 {@link QianJiTerminalHandlers}）。
 * <p>
 * 存哪：**宿主物品自己的 NBT**（自家终端存自己的；通用终端状态下存在**通用终端物品**上），
 * 键是 {@link #TAG_BLANK} / {@link #TAG_ENCODED} —— 于是"槽里的板子跟着终端走"。
 */
public final class QianJiTerminalSlots {

    /** 空白样板槽在物品 NBT 里的键 */
    public static final String TAG_BLANK = "qianjiBlankSlot";
    /** 编码样板槽在物品 NBT 里的键 */
    public static final String TAG_ENCODED = "qianjiEncodedSlot";

    /** 宿主物品（玩家背包里的那个栈，写 NBT 就是"落盘"） */
    private final ItemStack hostStack;
    /** 网络来源（自动推入用；超范围/没绑定时返回 null） */
    private final Supplier<IGrid> gridSupplier;
    /** 宿主对应的玩家（自动推入成功后要通知谁，v277；拿不到就给 null） */
    private final Supplier<net.minecraft.world.entity.player.Player> playerSupplier;

    private final ItemStackHandler blankPatterns;
    private final ItemStackHandler encodedPatterns;

    /** 读盘期间抑制回调（deserializeNBT 可能触发 onContentsChanged） */
    private boolean loading = true;

    public QianJiTerminalSlots(ItemStack hostStack, Supplier<IGrid> gridSupplier,
            Supplier<net.minecraft.world.entity.player.Player> playerSupplier) {
        this.hostStack = hostStack;
        this.gridSupplier = gridSupplier;
        this.playerSupplier = playerSupplier;
        this.blankPatterns = QianJiTerminalHandlers.blankHandler(this::onSlotChanged);
        this.encodedPatterns = QianJiTerminalHandlers.encodedHandler(this::onSlotChanged);
        load();
        this.loading = false;
    }

    public ItemStackHandler blank() {
        return blankPatterns;
    }

    public ItemStackHandler encoded() {
        return encodedPatterns;
    }

    /** 把两个槽写回宿主物品的 NBT */
    public void save() {
        try {
            final CompoundTag tag = hostStack.getOrCreateTag();
            tag.put(TAG_BLANK, blankPatterns.serializeNBT());
            tag.put(TAG_ENCODED, encodedPatterns.serializeNBT());
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] 无线终端槽位写回失败：{}", t.toString());
        }
    }

    private void onSlotChanged() {
        if (loading) return;
        save();
        // 空白样板槽 = 网络里空白样板的"映射"（v282）：放进来就送进网络，槽里不留东西
        QianJiTerminalHandlers.flushBlankToNetwork(gridSupplier.get(), blankPatterns, this::save);
        // 编码槽有千机样板就自动推入一台有空位的千机（与线缆面板同一份逻辑）；
        // 推成功后顺带通知玩家：终端列表跳过去并高亮（v277）
        QianJiTerminalHandlers.autoPushEncoded(gridSupplier.get(), encodedPatterns, this::save,
                playerSupplier == null ? null : playerSupplier.get());
    }

    private void load() {
        final CompoundTag tag = hostStack.getTag();
        if (tag == null) return;
        if (tag.contains(TAG_BLANK)) {
            blankPatterns.deserializeNBT(tag.getCompound(TAG_BLANK));
        }
        if (tag.contains(TAG_ENCODED)) {
            encodedPatterns.deserializeNBT(tag.getCompound(TAG_ENCODED));
        }
    }
}
