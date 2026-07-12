package com.ae2addon.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * 永恒之心 — 模组的核心基础材料。
 * 蕴含无限能量的奇迹结晶，用于合成所有无限型设备。
 */
public class EternalHeartItem extends Item {

    public EternalHeartItem() {
        super(new Item.Properties().stacksTo(64).rarity(Rarity.EPIC));
    }
}
