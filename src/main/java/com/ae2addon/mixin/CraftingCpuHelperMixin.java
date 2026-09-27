package com.ae2addon.mixin;

import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ICraftingInventory;
import com.ae2addon.crafting.SeedConsumptionLedger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记账「合成真实吃掉的产物」（2026-09-27）。
 * <p>
 * AE2 {@code CraftingCpuHelper.extractTemplates(ICraftingInventory, InputTemplate, long)}
 * 返回**真实扣走的份数**（javap 实测：{@code extractPatternInputs} 里
 * {@code extractTemplates} 的返回值用来累加"本次扣了什么"）。自指配方里产物=输入，
 * 所以这个返回值就是"被合成自己吃掉的产物"。
 * <p>
 * 用途见 {@link SeedConsumptionLedger}：把吃掉的量折成任务值补回，让净交付等于订单量。
 * 这里只记账、不改任何行为（返回值原样透传）。
 */
@Mixin(CraftingCpuHelper.class)
public class CraftingCpuHelperMixin {

    // ⚠ remap = false：CraftingCpuHelper 是 AE2 自己的类（不在 MC 混淆映射表里），
    // AE2 自身类不在 MC 混淆映射表里，故使用相同的 remap 设置。
    @Inject(method = "extractTemplates(Lappeng/crafting/inv/ICraftingInventory;"
                    + "Lappeng/crafting/execution/InputTemplate;J)J",
            at = @At("RETURN"), require = 1, remap = false)
    private static void ae2addon$noteEatenTemplates(ICraftingInventory inventory,
            InputTemplate template, long amount, CallbackInfoReturnable<Long> cir) {
        try {
            Long got = cir.getReturnValue();
            if (got != null && got > 0 && template != null) {
                SeedConsumptionLedger.record(template.key(), got);
            }
        } catch (Throwable ignored) {
            // 记账绝不影响游戏（包含 key() 可能抛异常的情况）
        }
    }
}
