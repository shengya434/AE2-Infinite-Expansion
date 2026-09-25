package com.ae2addon.compat.ae2wtlib;

import appeng.menu.ISubMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;

/**
 * 千机终端在**无线通用终端（AE2WTLib 的 WUT）**上的宿主（2026-09-21 v273）。
 * <p>
 * 与 {@link QianJiWTStandaloneHost} 唯一的差别：这是"通用终端的一种状态"，
 * 界面要多画一个「打开下一个终端」按钮。
 * <p>
 * ⚠ 构造签名必须原样保持四参（AE2WTLib 的 {@code WTDefinition.WTMenuHostFactory} 按它创建）。
 */
public class QianJiUniversalHost extends QianJiWTMenuHost {

    public QianJiUniversalHost(Player player, Integer slot, ItemStack stack,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(player, slot, stack, returnToMainMenu);
    }

    /** 客户端靠它决定要不要画「打开下一个终端」按钮 */
    @Override
    public boolean isUniversal() {
        return true;
    }
}
