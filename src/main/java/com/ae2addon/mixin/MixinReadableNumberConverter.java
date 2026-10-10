package com.ae2addon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ae2addon.cell.ChannelCellCore;

import appeng.util.ReadableNumberConverter;

/**
 * Renders an "infinite" amount as the ASCII tag {@code Inf} instead of a number.
 *
 * <p><b>Why ASCII and not the infinity symbol</b> (sensei's measurement, 2026-10-10): the client's default
 * font has no glyph for U+221E -- the hook demonstrably fired and returned the symbol, yet the row showed
 * nothing readable where the number used to be. {@code Inf} is pure ASCII and draws in every font and every
 * pack, so the display keeps saying what the number means without depending on a glyph being present.
 *
 * <p><b>Why this exists</b> (sensei, 2026-10-05, copying how the 1.20.1/1.21.1 fork does it): the amount our
 * cells report for an infinite row has to live inside AE2's summing inventory, so it is a real number --
 * {@link ChannelCellCore#INFINITE}, now {@code Long.MAX_VALUE / 4} so that several cells and any number of
 * {@code +-n} movements still add up instead of wrapping negative. A number that big is not what the player
 * means, though: the row IS infinite. So the value stays sum-safe and the DISPLAY says what it means.
 *
 * <p>The hook is {@code ReadableNumberConverter}, AE2's own enum singleton that turns a {@code long} into the
 * {@code 9.2E}-style text the terminal, the tooltip and the ME chest draw. Both forms are covered
 * ({@code toSlimReadableForm} and {@code toWideReadableForm}); anything at or above
 * {@link ChannelCellCore#INFINITE_SIGHT} (half the infinite value, so a row nudged down by a physical movement
 * still reads as infinite) comes out as {@code Inf}.
 *
 * <p>Client only: the config lists this class under {@code client}, so a dedicated server never loads it.
 * Third-party terminals (Thaumic Energistics, Mekanism Energistics...) draw their own way and will keep showing
 * the number; AE2's own terminal - which is what sensei uses for the item channel - shows the tag.
 */
@Mixin(value = ReadableNumberConverter.class, remap = false)
public abstract class MixinReadableNumberConverter {

    @Inject(method = {"toSlimReadableForm", "toWideReadableForm"}, at = @At("HEAD"), cancellable = true)
    private void ae2addon$showInfinity(long size, CallbackInfoReturnable<String> cir) {
        if (size >= ChannelCellCore.INFINITE_SIGHT) {
            cir.setReturnValue("Inf");
        }
    }
}
