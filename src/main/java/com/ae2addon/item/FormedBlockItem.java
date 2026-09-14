package com.ae2addon.item;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.Formable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「已成型」变体方块物品（创造模式专用）。
 * <p>
 * 与本体方块共用同一个 block id / BlockEntityType，区别只在放置瞬间：
 * 原版 {@link BlockItem#place} 在方块与 BE 落位后会回调
 * {@link #updateCustomBlockEntityTag}，这里把 BE 直接置为成型 ——
 * 创造模式玩家随取随用，不必搭多方块结构。
 * <p>
 * 双端都置位：客户端 BE 由 {@code newBlockEntity} 新建（不走 NBT 加载），
 * 只在服务端置位会导致客户端显示未成型。
 */
public class FormedBlockItem extends BlockItem {

    /** 独立语言键：BlockItem 默认取方块的键，两个变体会显示同名 */
    private final String descriptionId;

    public FormedBlockItem(Block block, Item.Properties properties, String id) {
        super(block, properties);
        this.descriptionId = "item." + AE2Addon.MODID + "." + id;
    }

    @Override
    public String getDescriptionId() {
        return descriptionId;
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player,
                                                 ItemStack stack, BlockState state) {
        boolean handled = super.updateCustomBlockEntityTag(pos, level, player, stack, state);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Formable formable && !formable.isFormed()) {
            formable.setFormed(true);
            handled = true;
        }
        return handled;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.ae2addon.formed_variant"));
    }
}
