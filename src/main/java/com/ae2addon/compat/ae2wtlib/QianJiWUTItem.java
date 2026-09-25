package com.ae2addon.compat.ae2wtlib;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import de.mari_023.ae2wtlib.terminal.IUniversalWirelessTerminalItem;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 无线千机·样板终端"通用终端感知"版本（2026-09-21 v273）。
 * <p>
 * 只在 AE2WTLib 存在时才会被实例化（见 {@link AE2WTLibItemFactory}）：
 * 它比 {@code QianJiWirelessTerminalItem} 多两件事 ——
 * <ol>
 *   <li>实现 AE2WTLib 的 {@link IUniversalWirelessTerminalItem}，
 *       好让通用终端（WUT）能把它当成"一种状态"来切换；</li>
 *   <li>把宿主换成 {@link QianJiWTStandaloneHost}（{@code WTMenuHost}），
 *       于是自家无线终端也支持**量子网络桥**（量子缠绕奇点槽 + 量子桥卡）——
 *       这是 v274 按 sensei 反馈补的：原来只有无线访问点范围，"可访问范围非常有限"。</li>
 * </ol>
 * 接口里两个抽象方法：
 * <ul>
 *   <li>{@code getMenuType(ItemStack)} —— 显式实现：**不管手上是自己的终端还是通用终端，
 *       打开的都是同一张千机终端菜单**；</li>
 *   <li>{@code getLinkedGrid(ItemStack, Level, Player)} —— **不用写**：AE2 的
 *       {@code WirelessTerminalItem} 已经有一模一样的公开实现（读物品 NBT 里的访问点），
 *       继承下来就自动满足了接口。</li>
 * </ul>
 * 其余（{@code open}/{@code tryOpen}/{}@code checkUniversalPreconditions}）都是接口默认实现，
 * 走的正是我们自己注册的那个 {@code MenuOpener}。
 */
public class QianJiWUTItem extends com.ae2addon.item.QianJiWirelessTerminalItem
        implements IUniversalWirelessTerminalItem {

    @Override
    public MenuType<?> getMenuType(ItemStack stack) {
        return com.ae2addon.init.ModMenuTypes.QIAN_JI_TERMINAL.get();
    }

    /** 宿主换成 AE2WTLib 那份（量子桥/奇点/升级卡都在里面） */
    @Override
    public ItemMenuHost getMenuHost(Player player, int inventorySlot, ItemStack stack, BlockPos pos) {
        // ⚠ WTMenuHost 构造里会读物品 NBT 的两个子库存，而 AppEngInternalInventory.readFromNBT 不认 null
        //   （tag.contains → NPE）→ 先把 tag 备好，免得刚搓出来的终端一开界面就崩
        stack.getOrCreateTag();
        return new QianJiWTStandaloneHost(player, inventorySlot, stack,
                (p, menu) -> openFromInventory(p, inventorySlot, true));
    }

    /**
     * 升级卡库存：跟 {@code WTMenuHost} 内部那份（{@code ItemWUT} 同款大小 = 通用终端升级卡数）
     * 落在**同一个 NBT 键**（{@code upgrades}）上，这样界面里插的量子桥卡才会被
     * {@code isQuantumLinked()} 认账（它读的是宿主自己那份视图）。
     */
    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, WUTHandler.getUpgradeCardCount());
    }
}
