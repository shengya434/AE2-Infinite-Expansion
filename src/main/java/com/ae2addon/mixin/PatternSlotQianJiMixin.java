package com.ae2addon.mixin;

import appeng.menu.slot.RestrictedInputSlot;
import com.ae2addon.init.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 AE2 的样板槽**接受千机样板**（2026-09-20 sensei 实测需求②）。
 * <p>
 * 症状：千机配方样板放不进**样板编码终端**的「编码样板槽」（也放不进其它样板槽）。
 * 原因：那些槽是 AE2 的 {@link RestrictedInputSlot}，自带物品过滤（`m_5857_` = mayPlace /
 * `m_8010_` = mayPickup），只认 AE2 自己的样板物品 → 我们的 `ae2addon:qianji_pattern` 被拒。
 * <p>
 * 处置：**只对我们自己的这个物品**放宽这两处检查，且**只在样板类槽位**
 * （{@code ENCODED_PATTERN} / {@code PATTERN}）上放宽；其它物品、其它槽位一律走 AE2 原逻辑
 * —— 不动别人的行为，避免把存储元件、升级卡之类的过滤一起放开。
 * <p>
 * 为什么两个检查都要放：只放 `mayPlace` 的话，玩家能放进去却**拿不出来**（mayPickup 仍拒），
 * 那就成了"吞物品"。
 */
@Mixin(value = RestrictedInputSlot.class, remap = false)
public abstract class PatternSlotQianJiMixin {

    @Shadow
    @Final
    private RestrictedInputSlot.PlacableItemType which;

    /** 这个槽是不是"样板类"槽位（只在这两类上放宽，别碰存储元件/升级卡那些） */
    private boolean ae2addon$isPatternSlot() {
        return which == RestrictedInputSlot.PlacableItemType.ENCODED_PATTERN
                || which == RestrictedInputSlot.PlacableItemType.PATTERN;
    }

    private static boolean ae2addon$isQianJiPattern(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == ModItems.QIAN_JI_PATTERN.get();
    }

    /** mayPlace：允许放千机样板 */
    @Inject(method = "m_5857_", at = @At("HEAD"), cancellable = true)
    private void ae2addon$allowPlaceQianJiPattern(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (ae2addon$isPatternSlot() && ae2addon$isQianJiPattern(stack)) {
            cir.setReturnValue(true);
        }
    }

    /** mayPickup：允许把千机样板再拿出来（否则等于吞物品） */
    @Inject(method = "m_8010_", at = @At("HEAD"), cancellable = true)
    private void ae2addon$allowTakeQianJiPattern(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (ae2addon$isPatternSlot() && ae2addon$isQianJiPattern(((Slot) (Object) this).getItem())) {
            cir.setReturnValue(true);
        }
    }
}
