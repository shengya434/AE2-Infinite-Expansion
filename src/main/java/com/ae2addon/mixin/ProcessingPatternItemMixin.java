package com.ae2addon.mixin;

import appeng.api.stacks.GenericStack;
import com.ae2addon.recipe.QianJiPatternCodec;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ME 样板编码器接入（2026-09-15）：挂在**更靠外**的公开入口
 * {@code ProcessingPatternItem.encode(GenericStack[] 输入, GenericStack[] 输出)} ——
 * 样板编码终端的流程是
 * {@code PatternEncodingTermMenu.encode() → encodePattern() → encodeProcessingPattern()}，
 * 最终产出的样板物品由这里生成，所以在 TAIL 直接给成品挂我们的元数据最稳。
 * <p>
 * 挂上的内容：输出重写为**只含主产物** + 写入千机元数据（几率表 + 来源配方 id），
 * 于是 AE2 不会为概率副产等待，而千机可精确执行。
 */
@Mixin(value = appeng.crafting.pattern.ProcessingPatternItem.class, remap = false)
public abstract class ProcessingPatternItemMixin {

    private static final Logger LOGGER = LogManager.getLogger("ae2addon");

    @Inject(method = "encode", at = @At("TAIL"), cancellable = true)
    private void ae2addon$attachQianJiData(GenericStack[] inputs, GenericStack[] outputs,
                                           CallbackInfoReturnable<ItemStack> cir) {
        LOGGER.info("[ae2addon] encode(样板编码器) 触发: 输入 {} / 输出 {}",
                inputs == null ? 0 : inputs.length, outputs == null ? 0 : outputs.length);
        if (outputs == null || outputs.length == 0) return;

        QianJiPatternData data = QianJiPatternCodec.matchCurrent(inputs, outputs);
        if (data == null) {
            LOGGER.info("[ae2addon] encode: 未匹配到千机配方（保持 AE2 原生样板）");
            return;
        }
        // 命中千机配方 → **直接出我们自己的样板物品**（数据自洽，千机精确执行）
        ItemStack pattern = new ItemStack(com.ae2addon.init.ModItems.QIAN_JI_PATTERN.get());
        data.writeTo(pattern);
        cir.setReturnValue(pattern);
        LOGGER.info("[ae2addon] 编码器已改出千机样板: {}（主产物 {} / 概率产出 {}）",
                data.recipeId(), data.primary().size(), data.chanced().size());
    }
}
