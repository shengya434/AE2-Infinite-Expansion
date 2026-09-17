package com.ae2addon.mixin;

import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ME 样板编码器接入（2026-09-15）—— **2026-09-17 sensei 要求收敛后，这里的自动转换已停用**。
 * <p>
 * 原行为：只要编码出来的（输入, 输出）能匹配上千机配方，就把成品换成千机样板。
 * 后果（sensei 实测）：在**任何**配方页编码，出来的都是千机样板。
 * <p>
 * 新规则（sensei 定）——**只有这两种情形**才产出千机样板：
 * <ol>
 *   <li>千机·自用配方页点「编码」按钮 → 走 {@code QianJiPatternPacket}，不经过这里</li>
 *   <li>编码终端里在千机·自用配方页点「+」→ 见 {@link EncodePatternTransferHandlerMixin}，也不经过这里</li>
 * </ol>
 * 其余页面编码出来的仍是 AE2 原生处理样板 / 合成样板（千机照样能执行原生处理样板，
 * 只是不再带我们的概率副产元数据）。
 * <p>
 * 这里保留注入点但**什么都不做**：留在 mixins.json 里以免动配置，行为上等于停用。
 */
@Mixin(value = appeng.crafting.pattern.ProcessingPatternItem.class, remap = false)
public abstract class ProcessingPatternItemMixin {

    @Inject(method = "encode", at = @At("TAIL"), cancellable = true)
    private void ae2addon$noAutoConvertToQianJi(GenericStack[] inputs, GenericStack[] outputs,
                                                CallbackInfoReturnable<ItemStack> cir) {
        // 故意留空：不再无差别把样板换成千机样板（2026-09-17 sensei 收敛）
    }
}
