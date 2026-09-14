package com.ae2addon.mixin;

import appeng.api.stacks.GenericStack;
import com.ae2addon.recipe.QianJiPatternCodec;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.nbt.CompoundTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ME 样板编码器接入千机配方（2026-09-15 sensei 的 B 方案）。
 * <p>
 * AE2 编码处理样板时会调 {@code ProcessingPatternEncoding.encodeProcessingPattern(tag, 输入, 输出)}
 * 把内容写进样板 NBT。我们在 TAIL 处补一刀：如果这条配方属于千机的归一化配方表，就把
 * **输出重写为只含主产物** + 写入我们的元数据（几率表）——于是编码器里编出来的样板
 * 千机可以精确执行，而 AE2 也不会为概率副产等待。
 * <p>
 * 注：编码在服务端菜单里进行（当前世界从 {@code ServerLifecycleHooks} 取）。
 */
@Mixin(targets = "appeng.crafting.pattern.ProcessingPatternEncoding", remap = false)
public abstract class ProcessingPatternEncodingMixin {

    private static final Logger LOGGER = LogManager.getLogger("ae2addon");

    @Inject(method = "encodeProcessingPattern", at = @At("TAIL"))
    private static void ae2addon$attachQianJiData(CompoundTag tag, GenericStack[] inputs, GenericStack[] outputs,
                                                  CallbackInfo ci) {
        if (tag == null || outputs == null || outputs.length == 0) return;
        QianJiPatternData data = QianJiPatternCodec.matchCurrent(inputs, outputs);
        if (data == null) return;

        QianJiPatternCodec.attachTo(tag, data);
        LOGGER.info("[ae2addon] 样板已挂千机元数据: {}（主产物 {} / 概率产出 {}）",
                data.recipeId(), data.primary().size(), data.chanced().size());
    }
}
