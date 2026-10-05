package com.ae2addon.mixin;

import java.text.NumberFormat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.ae2addon.cell.ChannelCellCore;

import appeng.fluids.client.gui.GuiFluidTerminal;

/**
 * Makes AE2's own <b>fluid terminal</b> tooltip say {@code ∞} for an infinite amount (sensei, 2026-10-05:
 * "最起码把 ae2 原版自己的流体终端也包括进去吧").
 *
 * <p>What was already covered: the fluid terminal's row COUNTS go through
 * {@code FluidStackSizeRenderer} -> {@code ReadableNumberConverter}, which
 * {@link MixinReadableNumberConverter} patches, so rows already read {@code ∞}. The hover tooltip is a separate
 * path: {@code GuiFluidTerminal#renderHoveredToolTip} formats its numbers with a plain
 * {@code java.text.NumberFormat} -- and with {@code format(double)}, not the {@code format(long)} variant
 * {@link MixinAEBaseMEGui} redirects, so it needed its own hook.
 *
 * <p>{@code GuiWirelessFluidTerminal} extends this class, so it is covered too. Client only (listed under
 * {@code client} in the mixin config). {@code remap = false}: AE2's class, and the method name is the SRG name
 * the release jar carries for the vanilla override.
 */
@Mixin(value = GuiFluidTerminal.class, remap = false)
public abstract class MixinGuiFluidTerminal {

    @Redirect(method = "func_191948_b", at = @At(value = "INVOKE",
            target = "Ljava/text/NumberFormat;format(D)Ljava/lang/String;"))
    private String ae2addon$showInfinityInTooltip(NumberFormat format, double size) {
        if (size >= ChannelCellCore.INFINITE_SIGHT) {
            return "\u221e";
        }
        return format.format(size);
    }
}
