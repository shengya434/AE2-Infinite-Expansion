package com.ae2addon.compat.ae2wtlib;

import appeng.menu.ISubMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;

/**
 * 自家**无线千机·样板终端**在 AE2WTLib 加持下的宿主（2026-09-21 v274）。
 * <p>
 * 为什么要换成这个（而不是原来的 {@code QianJiWirelessHost}）：
 * AE2 自带的无线终端宿主只有"无线访问点范围"这一种连法，而 sensei 要的是
 * **量子网络桥**那套（量子缠绕奇点槽 + 量子桥卡）→ 只有 AE2WTLib 的
 * {@code WTMenuHost} 才有这套逻辑。装了 AE2WTLib 就用它，没装就还是 AE2 那份。
 * <p>
 * 与 {@link QianJiUniversalHost} 的差别只有一条：这不是通用终端的状态，
 * 界面不画「打开下一个终端」按钮。
 */
public class QianJiWTStandaloneHost extends QianJiWTMenuHost {

    public QianJiWTStandaloneHost(Player player, Integer slot, ItemStack stack,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(player, slot, stack, returnToMainMenu);
    }
}
