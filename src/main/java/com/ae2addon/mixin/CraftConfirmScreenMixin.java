package com.ae2addon.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.core.localization.LocalizationEnum;
import com.ae2addon.AE2Addon;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 合成确认界面（下单时的 CPU01/02 选择）显示美化：
 * 集成 CPU 的「无限存储 / 拉满并行」渲染成 ∞。
 * <p>
 * 双保险：
 * 1. @Redirect GuiText.ConfirmCraftCpuStatus.text(...) 调用（LocalizationEnum 接口方法），
 *    参数携带哨兵值时返回 ∞ 文本。
 * 2. @Inject updateBeforeRender RETURN 后反射调用 setTextContent 强制覆盖 cpu_status，
 *    防止其他 mod（如 ExtendedAE Plus）的 mixin 干扰显示。
 */
@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenMixin {

    @Unique
    private static boolean ae2addon$diagLogged;
    @Unique
    private static Method ae2addon$setTextContentMethod;

    @Redirect(method = "updateBeforeRender",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/LocalizationEnum;"
                            + "text([Ljava/lang/Object;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent ae2addon$infiniteConfirmStatus(
            LocalizationEnum instance, Object[] args) {
        if (!ae2addon$diagLogged) {
            ae2addon$diagLogged = true;
            AE2Addon.LOGGER.info("[ae2addon] ConfirmCraftCpuStatus.text @Redirect 被调用！args={}",
                    args == null ? "null" : java.util.Arrays.toString(args));
        }
        boolean infinite = false;
        if (args != null && args.length >= 2) {
            infinite = (args[0] instanceof Long storage && storage == Long.MAX_VALUE)
                    || (args[1] instanceof Integer processors
                            && processors == Integer.MAX_VALUE - 1);
        }
        if (infinite) {
            return Component.translatable("gui.ae2addon.cpu.infinite_status");
        }
        return instance.text(args);
    }

    @Inject(method = "updateBeforeRender", at = @At("RETURN"))
    private void ae2addon$forceInfiniteCpuStatus(CallbackInfo callback) {
        var menu = ((CraftConfirmScreen) (Object) this).getMenu();
        if (menu == null) {
            return;
        }
        if (menu.getCpuAvailableBytes() != Long.MAX_VALUE
                && menu.getCpuCoProcessors() != Integer.MAX_VALUE - 1) {
            return;
        }
        try {
            var screen = (CraftConfirmScreen) (Object) this;
            var method = ae2addon$setTextContentMethod;
            if (method == null) {
                method = AEBaseScreen.class.getDeclaredMethod(
                        "setTextContent", String.class, Component.class);
                method.setAccessible(true);
                ae2addon$setTextContentMethod = method;
            }
            method.invoke(screen, "cpu_status",
                    Component.translatable("gui.ae2addon.cpu.infinite_status"));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AE2Addon.LOGGER.warn("[ae2addon] 强制覆盖 cpu_status 失败", exception);
        }
    }
}
