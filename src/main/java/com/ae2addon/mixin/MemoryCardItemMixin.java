package com.ae2addon.mixin;

import appeng.items.tools.MemoryCardItem;
import appeng.util.InteractionUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AE2 内存卡对 ME接口（无限级）的配置复制支持（2026-08-28 sensei）：
 * 手持内存卡右键接口 = 导出（升级卡+每接口参数+标记+开关+方向），
 * 再右键另一接口 = 导入。Alt+右键仍为原版清卡。
 */
@Mixin(MemoryCardItem.class)
public class MemoryCardItemMixin {

    static {
        com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] MemoryCardItemMixin 类已加载");
    }

    @Inject(method = "m_6225_", at = @At("HEAD"), cancellable = true)
    private void ae2addon$handleFeeder(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = ctx.getLevel();
        BlockEntity be = level.getBlockEntity(ctx.getClickedPos());
        if (!(be instanceof com.ae2addon.block.InfiniteInterfaceBE feeder)) {
            return; // 非本 mod 方块：交给原逻辑
        }
        Player player = ctx.getPlayer();
        if (player == null) {
            return;
        }
        if (MemoryCardHelper.handleUse(feeder, player, ctx.getItemInHand())) {
            cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide));
        }
    }
}
