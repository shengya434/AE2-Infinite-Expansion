package com.ae2addon.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 配置存储卡（2026-08-28 sensei：AE2 内存卡 doesSneakBypassUse=true 写死，
 * 无法稳定接管 → 自研配置卡，完全自己控制交互）。
 * <p>
 * 手持配置卡右键 ME接口（无限级）：
 * - 卡内无配置 → 复制接口配置（升级卡完整NBT + 参数 + 标记 + 缓存目标 + 开关 + 方向）
 * - 卡内有配置 → 粘贴到接口
 * 数据键与 AE2 内存卡共用（MemoryCardHelper.CFG_KEY），两卡数据互通。
 */
public class ConfigCardItem extends Item {

    public ConfigCardItem() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        var feeder = com.ae2addon.network.FeederHostResolver.resolve(level, ctx.getClickedPos());
        if (feeder == null) {
            return InteractionResult.PASS; // 只对本 mod 接口/面板生效
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS; // 客户端不处理，等服务端
        }
        if (ctx.getPlayer() == null) {
            return InteractionResult.FAIL;
        }
        boolean paste = ctx.getPlayer().isShiftKeyDown();
        boolean handled = paste
                ? com.ae2addon.util.MemoryCardHelper.handlePaste(feeder, ctx.getPlayer(), ctx.getItemInHand())
                : com.ae2addon.util.MemoryCardHelper.handleCopy(feeder, ctx.getPlayer(), ctx.getItemInHand());
        if (handled) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }
}
