package com.ae2addon.mixin;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.util.inv.AppEngInternalInventory;
import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.init.UnlimitedCellHandler;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 元件隔离：**无限元件只能插在「驱动器（无限级）」里**（2026-09-14 sensei 定）。
 * <p>
 * 背景：我们的元件在 {@code UnlimitedCellHandler} 里对 AE2 全局注册（{@code StorageCells.addCellHandler}），
 * 所以原版 AE2 驱动器 / ME 箱都能识别并使用它 —— 与设计语义相反。
 * <p>
 * 为什么不在 {@code ICellHandler#getCellInventory(stack, ISaveProvider)} 里判宿主：
 * 实测（javap DriveBlockEntity）AE2 传进来的是 **lambda**（{@code this::saveChanges}），
 * 不是驱动器方块实体本身，判不出宿主。
 * <p>
 * 因此改拦**槽位校验**：AE2 的所有元件槽（驱动器 / ME 箱 / 导入导出端口…）都走
 * {@link AppEngInternalInventory#isItemValid}，而它的 host 就是宿主方块实体
 * （{@code AppEngCellInventory} 构造时把自身 BE 当 host 传进去）。
 * <p>
 * 规则（窄化到「元件存储宿主」，避免误伤元件工作台等正常用法）：
 * <ul>
 *   <li>宿主是实现 {@link IChestOrDrive} 的**别人的**驱动器/ME 箱 → 拒收（插不进去）</li>
 *   <li>宿主是 {@link InfiniteDriveBE}（自家无限驱动器）→ 放行，交给原逻辑</li>
 *   <li>其它宿主（元件工作台、端口等）→ 不动</li>
 * </ul>
 * 已知遗留：改动前就已经插在普通驱动器里的老元件仍会继续工作（只拦新增插入）。
 */
@Mixin(value = AppEngInternalInventory.class, remap = false)
public abstract class InfiniteCellSlotGuardMixin {

    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true)
    private void ae2addon$guardInfiniteCellSlot(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty() || !UnlimitedCellHandler.isUnlimitedCell(stack)) return;

        var self = (AppEngInternalInventory) (Object) this;
        Object host = self.getHost();
        if (!(host instanceof IChestOrDrive)) return;      // 非元件存储宿主：不拦
        if (host instanceof InfiniteDriveBE) return;      // 自家无限驱动器：放行

        cir.setReturnValue(false);
    }
}
