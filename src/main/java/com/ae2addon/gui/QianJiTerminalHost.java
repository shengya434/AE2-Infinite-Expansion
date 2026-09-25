package com.ae2addon.gui;

import appeng.api.networking.IGrid;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 千机·样板终端的**宿主**接口（2026-09-21 v272：线缆面板 + 无线终端共用一张菜单）。
 * <p>
 * 为什么要有它：同一个界面有两种"载体"——
 * <ul>
 *   <li>**线缆面板**（{@code QianJiTerminalPart}）：槽位存在 part 的 NBT 里，网络来自 part 的主节点；</li>
 *   <li>**无线终端**（{@code QianJiWirelessTerminalItem}）：槽位存在**物品的 NBT** 里，
 *       网络来自它绑定的无线访问点（AE2 的 {@code WirelessTerminalMenuHost}）。</li>
 * </ul>
 * AE2 自己的做法是给两种载体各注册一个菜单类型（part 一个、无线一个）。我们发现
 * AE2 15.4 的 {@code MenuLocator#locate(player, Class<T>)} 是**按目标类型取宿主**的，
 * 于是只要两边都实现这个接口，**一个菜单类型就够了** —— 菜单、界面（Screen）、
 * 全网列表/搜索包、编码链路（{@code QianJiEncodedSlotHolder}）全部零改动复用。
 * <p>
 * 槽位规则（"只收空白样板 / 只收千机样板"）不在这里，在
 * {@link QianJiTerminalHandlers}（菜单槽 + 库存两层一起卡）。
 */
public interface QianJiTerminalHost {

    /**
     * 终端所见的那张网络（服务端才有真值）。
     *
     * @return 网络；没接入 / 客户端 / 超出无线范围一律 null（调用方必须容忍 null）
     */
    @Nullable
    IGrid getTerminalGrid();

    /** 空白样板槽的库存（1 格） */
    IItemHandler blankInv();

    /** 编码样板槽的库存（1 格，只收千机样板） */
    IItemHandler encodedInv();

    /** 槽位内容变了要落盘（part → markForSave；物品 → 写回物品 NBT） */
    void saveHost();

    /**
     * 这个宿主是不是**无线通用终端（AE2WTLib 的 WUT）**上的一种状态。
     * <p>
     * 客户端要靠它决定"要不要画『打开下一个终端』按钮"（界面高度也随它变），
     * 所以这是个**普通布尔**，不引用 AE2WTLib 的任何类型 —— 没装 AE2WTLib 也照样能编译/运行。
     */
    default boolean isUniversal() {
        return false;
    }

    /**
     * 升级卡槽（AE2WTLib 的"量子桥卡"就插在这里）。
     *
     * @return 升级卡库存；没有就 null（线缆面板形态没有这条）
     */
    default appeng.api.upgrades.IUpgradeInventory upgradeInv() {
        return null;
    }

    /**
     * **量子缠绕奇点**槽（2026-09-21 v274）。
     * <p>
     * 有它 + 量子桥卡，无线终端就能通过量子网络桥连到远处的网络
     * （AE2WTLib 的 {@code WTMenuHost#rangeCheck} = 普通无线范围 **或** 量子链接），
     * 否则终端只能在无线访问点附近用 —— 这正是 sensei 说的"可访问范围非常有限"。
     *
     * @return 奇点库存；没有就 null
     */
    default appeng.api.inventories.InternalInventory singularityInv() {
        return null;
    }
}
