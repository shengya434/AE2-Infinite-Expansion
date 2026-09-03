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
 * 合成确认界面（下单时的 CPU01/02 选择）显示：
 * 集成 CPU 的「无限存储 / 拉满并行」默认渲染 ∞；config cpuDisplayBytes /
 * cpuDisplayThreads 填了具体值则显示具体值（2026-09-03 sensei）。
 * <p>
 * 双保险：
 * 1. @Redirect GuiText.ConfirmCraftCpuStatus.text(...) 调用（LocalizationEnum 接口方法），
 *    参数携带哨兵值时替换为 config 显示值/∞。
 * 2. @Inject updateBeforeRender RETURN 后反射调用 setTextContent 强制覆盖 cpu_status，
 *    防止其他 mod（如 ExtendedAE Plus）的 mixin 干扰显示。
 */
@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenMixin {

    @Unique
    private static Method ae2addon$setTextContentMethod;

    /** config 存储显示值：MAX → 无限哨兵；填具体值 → 该值。 */
    @Unique
    private static long ae2addon$dispBytes() {
        return com.ae2addon.config.AE2AddonConfig.cpuDisplayBytes();
    }

    /** config 并行显示值：0（拉满）→ MAX-1 哨兵；填 N → N。 */
    @Unique
    private static int ae2addon$dispThreads() {
        return com.ae2addon.config.AE2AddonConfig.cpuDisplayThreads();
    }

    /** 状态行组件：存储段 + 并行段（∞ 或 config 显示值）。 */
    @Unique
    private static MutableComponent ae2addon$statusComponent() {
        long bytes = ae2addon$dispBytes();
        int threads = ae2addon$dispThreads();
        Component bytesPart = bytes == Long.MAX_VALUE
                ? Component.literal("∞")
                : appeng.core.localization.Tooltips.ofBytes(bytes);
        Component threadsPart = threads == Integer.MAX_VALUE - 1
                ? Component.literal("∞")
                : Component.literal(String.valueOf(threads));
        return Component.translatable("gui.ae2addon.cpu.status", bytesPart, threadsPart);
    }

    @Redirect(method = "updateBeforeRender",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/LocalizationEnum;"
                            + "text([Ljava/lang/Object;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"),
            require = 0)
    private MutableComponent ae2addon$infiniteConfirmStatus(
            LocalizationEnum instance, Object[] args) {
        boolean hasSentinel = false;
        if (args != null && args.length >= 2) {
            hasSentinel = (args[0] instanceof Long storage && storage == Long.MAX_VALUE)
                    || (args[1] instanceof Integer processors
                            && processors == Integer.MAX_VALUE - 1);
        }
        if (!hasSentinel) {
            return instance.text(args);
        }
        // 哨兵（集成 CPU 真实无限）：按 config 显示值渲染
        return ae2addon$statusComponent();
    }

    @Inject(method = "updateBeforeRender", at = @At("RETURN"), require = 0)
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
            method.invoke(screen, "cpu_status", ae2addon$statusComponent());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AE2Addon.LOGGER.warn("[ae2addon] 强制覆盖 cpu_status 失败", exception);
        }
    }
}
