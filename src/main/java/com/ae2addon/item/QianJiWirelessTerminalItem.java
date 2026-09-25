package com.ae2addon.item;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.core.AEConfig;
import appeng.items.tools.powered.WirelessTerminalItem;
import com.ae2addon.gui.QianJiWirelessHost;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 千机·样板无线终端（2026-09-21 v272）。
 * <p>
 * 就是"把线缆上的千机·样板终端揣兜里"：界面、列表、搜索、取放、编码、自动推入
 * 与线缆面板**完全同一套**（同一个菜单类型 + 同一个 Screen）。
 * <p>
 * 为什么继承 AE2 的 {@link WirelessTerminalItem} 而不是自己搓一个：
 * <ul>
 *   <li>右键开界面的链路、**距离判定**（超出无线访问点范围就报错并关界面）、
 *       **按距离耗电**、充能（{@code IAEItemPowerStorage}，丢进 AE2 的充能器就能充）；</li>
 *   <li>绑定协议：AE2 的 {@code LinkableHandler} 判的是 {@code instanceof WirelessTerminalItem}，
 *       所以子类**自动**能被无线访问点绑定/解绑，不用另注册；</li>
 *   <li>升级卡、配置（{@code IUpgradeableItem}）全都白拿。</li>
 * </ul>
 * 我们只覆写两处：{@code getMenuType()} 指向千机终端菜单，{@code getMenuHost()}
 * 交出带两个样板槽的 {@link QianJiWirelessHost}。
 * <p>
 * ⚠ 打开界面走的是 AE2 的 {@code MenuOpener}：我们必须在初始化时给
 * {@code QIAN_JI_TERMINAL} 注册一个 opener（见 {@code AE2Addon#onCommonSetup}），
 * 否则 AE2 只会日志一句 "unknown menu type" 然后什么都不发生。
 */
public class QianJiWirelessTerminalItem extends WirelessTerminalItem {

    public QianJiWirelessTerminalItem() {
        // 容量基数沿用 AE2 无线终端那一档（AEConfig.instance().getWirelessTerminalBattery()）；
        // 实际上限与充电速率在下面两个覆写里按我们的 config 调整（v281）
        super(() -> AEConfig.instance().getWirelessTerminalBattery().getAsDouble(),
                new Item.Properties().stacksTo(1));
    }

    /**
     * 能源上限（2026-09-22 v281，sensei 要求"把上限拉高一些"）。
     * <p>
     * AE2 的取值顺序是：**物品 NBT 里有 {@code internalMaxPower} 就用它**，否则用构造时传进来的容量。
     * 这里取 {@code max(AE2 的值, 我们 config 里的值)} —— 用 max 而不是覆盖，
     * 是为了不把别的机制（比如以后有卡片把上限顶高）压回去。
     * config 里 {@code wirelessTerminalCapacity = 0} 表示完全跟随 AE2。
     */
    @Override
    public double getAEMaxPower(ItemStack stack) {
        final double base = super.getAEMaxPower(stack);
        final long ours = com.ae2addon.config.AE2AddonConfig.wirelessTerminalCapacity();
        return ours > 0L ? Math.max(base, (double) ours) : base;
    }

    /**
     * 充电速率：容量拉高后**按同一比例放大**，免得"充满"变得又慢又难受（v281）。
     * <p>
     * AE2 无线终端的基础速率是 800 AE/tick（再乘能量卡倍率），充电器那边还会乘
     * {@code chargerChargeRate}；我们只放大自己这一份。
     */
    @Override
    public double getChargeRate(ItemStack stack) {
        final double base = super.getChargeRate(stack);
        final long ours = com.ae2addon.config.AE2AddonConfig.wirelessTerminalCapacity();
        if (ours <= 0L) return base;
        final double ae2Base = com.ae2addon.config.AE2AddonConfig.ae2WirelessTerminalCapacity();
        final double factor = Math.max(1.0, ours / ae2Base);
        return base * factor;
    }

    /** 打开的就是千机·样板终端那张菜单（因此界面/列表/搜索/编码零改动复用） */
    @Override
    public MenuType<?> getMenuType() {
        return com.ae2addon.init.ModMenuTypes.QIAN_JI_TERMINAL.get();
    }

    @Override
    public ItemMenuHost getMenuHost(Player player, int inventorySlot, ItemStack stack, BlockPos pos) {
        // 「返回主菜单」回调照 AE2 的写法（子界面返回时重开本终端）
        return new QianJiWirelessHost(player, inventorySlot, stack,
                (p, menu) -> openFromInventory(p, inventorySlot, true));
    }
}
