package com.ae2addon.compat.ae2wtlib;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.ISubMenu;
import com.ae2addon.AE2Addon;
import com.ae2addon.gui.QianJiTerminalHost;
import com.ae2addon.gui.QianJiTerminalSlots;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * 千机终端在 **AE2WTLib 的 WTMenuHost** 上的公共宿主（2026-09-21 v274）。
 * <p>
 * AE2WTLib 的终端宿主比 AE2 的多三样东西，正好都是 sensei 要的：
 * <ul>
 *   <li>**量子桥**：{@code rangeCheck()} = 普通无线范围 **或** 量子链接；
 *       量子链接时 {@code getActionableNode()} 直接返回量子桥的节点 → 网络 = 远端网络，
 *       于是"走到哪都能连"（这正是"可访问范围非常有限"的解药）；</li>
 *   <li>**量子缠绕奇点槽**（{@code INV_SINGULARITY}）与**升级卡槽**（量子桥卡插这里）；</li>
 *   <li>观察卡槽位等。</li>
 * </ul>
 * 槽位规则与自家无线终端**共用一份**（{@link QianJiTerminalSlots}），不存在第二套逻辑。
 * <p>
 * 两个子类只在"是不是通用终端的一种状态"上不同（决定界面要不要画「下一个终端」按钮）：
 * {@link QianJiUniversalHost}（通用终端上）/ {@link QianJiWTStandaloneHost}（自家无线终端）。
 * <p>
 * ⚠ 这个类只在 AE2WTLib 存在时才会被加载（由 {@link AE2WTLibItemFactory} / {@link QianJiWUTRegistrar} 把门）。
 */
public abstract class QianJiWTMenuHost extends WTMenuHost implements QianJiTerminalHost {

    private final QianJiTerminalSlots slots;

    protected QianJiWTMenuHost(Player player, Integer slot, ItemStack stack,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(player, slot, stack, returnToMainMenu);
        // ⚠⚠ 2026-09-22 v276（sensei 报"奇点放进槽里、重开就不见了"的真因）：
        // **必须在构造里自己调 readFromNbt()** —— WTMenuHost 的构造器**不读**物品 NBT：
        // readFromNbt() 是 protected，AE2WTLib 自己的子类（WETMenuHost 等）都是在构造里自己调。
        // 不调的话 singularity / viewcells 这两份库存**永远是空的** ——
        // 关界面时 writeToNBT 明明写进了物品 NBT，下次打开却从来不读回来 → 看起来就是"东西凭空消失"。
        // 另：readFromNbt() 内部用 getTag()（不认 null），所以先把 tag 备好。
        stack.getOrCreateTag();
        readFromNbt();
        // 槽位存在宿主物品自己的 NBT 里 → 自家终端存在终端上，通用终端状态存在通用终端物品上
        // 第三个参数 = 自动推入成功后要通知谁（列表跳转 + 高亮，v277）
        this.slots = new QianJiTerminalSlots(stack, this::getTerminalGrid, this::getPlayer);
    }

    @Override
    public @Nullable IGrid getTerminalGrid() {
        try {
            // WTMenuHost 的 getActionableNode()：量子链接时给量子桥的节点（= 远端网络），
            // 否则给无线访问点的节点；都不行就是 null
            final var node = getActionableNode();
            return node == null ? null : node.getGrid();
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

    /**
     * 落盘（2026-09-22 v275 加强）。
     * <p>
     * v274 只写了我们自己的两个样板槽，结果 sensei 实测：**升级卡槽/量子缠绕奇点槽的内容在重开界面后不见了**
     * —— 因为那两个槽的库存是 AE2WTLib/AE2 的**内存对象**（宿主每次开界面都新建、从物品 NBT 读），
     * 不显式写回物品 NBT 就随宿主一起丢了。
     * 这里把三样都写回去：
     * <ul>
     *   <li>升级卡 → AE2 的键 {@code upgrades}（与 {@code ItemUpgradeInventory} 自己用的是同一个）；</li>
     *   <li>量子缠绕奇点 / 观察卡 → {@code WTMenuHost#saveChanges()}（写 {@code singularity}/{@code viewcells}）；</li>
     *   <li>我们自己的两个样板槽 → {@link QianJiTerminalSlots#save()}。</li>
     * </ul>
     * 调用时机：菜单关闭时（{@code QianJiTerminalMenu#removed}）。
     */
    @Override
    public void saveHost() {
        slots.save();
        try {
            final ItemStack stack = getItemStack();
            final net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();

            final IUpgradeInventory upgrades = getUpgrades();
            if (upgrades != null) {
                upgrades.writeToNBT(tag, "upgrades");
            }
            // 写 singularity / viewcells（WTMenuHost 自己那两个子库存）
            saveChanges();

            if (getPlayer() != null) {
                getPlayer().getInventory().setChanged();
            }
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][wut] 终端槽位写回失败：{}", t.toString());
        }
    }

    // ── 量子桥相关（界面据此多画一行槽） ──

    /** 升级卡槽（量子桥卡插这里） */
    @Override
    public IUpgradeInventory upgradeInv() {
        return getUpgrades();
    }

    /** 量子缠绕奇点槽（WTMenuHost 内部那份子库存，键是 {@code singularity}） */
    @Override
    public InternalInventory singularityInv() {
        try {
            return getSubInventory(WTMenuHost.INV_SINGULARITY);
        } catch (Throwable t) {
            return null;
        }
    }
}
