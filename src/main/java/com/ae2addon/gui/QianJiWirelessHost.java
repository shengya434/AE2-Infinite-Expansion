package com.ae2addon.gui;

import appeng.api.networking.IGrid;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.menu.ISubMenu;
import com.ae2addon.AE2Addon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * 无线千机·样板终端的宿主（2026-09-21 v272）。
 * <p>
 * 直接继承 AE2 的 {@link WirelessTerminalMenuHost} —— 距离判定、耗电、绑定信息、
 * "主菜单图标/快捷键"这些全部白拿，一行都不用自己写。我们只补三件事：
 * <ol>
 *   <li>给出 {@code IGrid}（AE2 那边是私有的，从 {@code getActionableNode()} 拿无线访问点的节点）；</li>
 *   <li>两个样板槽 —— 存在**物品 NBT** 里（见 {@link QianJiTerminalSlots}）；</li>
 *   <li>编码槽的自动推入（与线缆面板同一份实现，见 {@link QianJiTerminalHandlers}）。</li>
 * </ol>
 * 槽位规则不放宽：编码槽依旧**只认 {@code ae2addon:qianji_pattern}**。
 */
public class QianJiWirelessHost extends WirelessTerminalMenuHost implements QianJiTerminalHost {

    private final QianJiTerminalSlots slots;

    public QianJiWirelessHost(Player player, int slot, ItemStack stack,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(player, slot, stack, returnToMainMenu);
        // 第三个参数 = 自动推入成功后要通知谁（列表跳转 + 高亮，v277）
        this.slots = new QianJiTerminalSlots(stack, this::getTerminalGrid, this::getPlayer);
    }

    // ── QianJiTerminalHost ──

    @Override
    public @Nullable IGrid getTerminalGrid() {
        try {
            // getActionableNode() 内部会先 rangeCheck()：没绑定无线访问点 / 超出范围 → null
            final var wapNode = getActionableNode();
            return wapNode == null ? null : wapNode.getGrid();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public ItemStackHandler blankInv() {
        return slots.blank();
    }

    @Override
    public ItemStackHandler encodedInv() {
        return slots.encoded();
    }

    /** 槽位内容变了 → 写回物品 NBT（物品在玩家背包里，这就是"落盘"） */
    @Override
    public void saveHost() {
        slots.save();
        try {
            if (getPlayer() != null) {
                getPlayer().getInventory().setChanged();
            }
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] 无线终端槽位写回失败：{}", t.toString());
        }
    }
}
