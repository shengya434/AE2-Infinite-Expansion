package com.ae2addon.mixin;

import java.text.NumberFormat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.ae2addon.cell.ChannelCellCore;

import appeng.client.gui.AEBaseMEGui;

/**
 * Makes the ME terminal's item TOOLTIP say {@code Inf} for an infinite amount.
 *
 * <p>{@code Inf} and not the infinity symbol: the client font cannot draw U+221E, so the symbol came out as
 * nothing (sensei's measurement, 2026-10-10). ASCII draws everywhere -- same reasoning as
 * {@link MixinReadableNumberConverter}.
 *
 * <p><b>Why a second display hook</b> (sensei, 2026-10-05: "终端内『存储物品』的 tooltip 似乎没改"):
 * {@code MixinReadableNumberConverter} covers the counts AE2 draws through its
 * {@code ReadableNumberConverter} (the {@code 9.2E} style text in the terminal rows), but the tooltip that
 * appears when you hover a row is built in {@code AEBaseMEGui#renderToolTip} and formats the amount with a plain
 * {@code java.text.NumberFormat} -- so it kept showing the raw number. Four call sites in that one method
 * (stored / available / ... lines); one redirect covers them all, because the target is the call itself.
 *
 * <p>The threshold is the same {@link ChannelCellCore#INFINITE_SIGHT} the row display uses, so the row and its
 * tooltip always agree.
 *
 * <p>Client only (listed under {@code client} in the mixin config). {@code remap = false}: the target class is
 * AE2's, and the method name is the SRG name the release jar uses for the vanilla override.
 */
@Mixin(value = AEBaseMEGui.class, remap = false)
public abstract class MixinAEBaseMEGui {

    @Redirect(method = "func_146285_a", at = @At(value = "INVOKE",
            target = "Ljava/text/NumberFormat;format(J)Ljava/lang/String;"))
    private String ae2addon$showInfinityInTooltip(NumberFormat format, long size) {
        if (size >= ChannelCellCore.INFINITE_SIGHT) {
            return "Inf";
        }
        return format.format(size);
    }
}
