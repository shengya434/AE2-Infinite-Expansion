package com.ae2addon.cell;

import java.util.List;
import java.util.Set;

import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * One storage channel, as the judgement layer sees it.
 *
 * <p>The cell's modes, thresholds, rules, whitelist/blacklist, committed amounts and listing logic are all
 * channel-independent: they only ever talk about an {@link IAEStack}'s size, identity, copy and NBT. The only
 * genuinely channel-specific questions are the five below, so those are what an implementation answers.
 *
 * <p>Motivated by sensei's 2026-10-03 decision to make ONE universal cell serve every channel (item, fluid,
 * gas, essentia; CrazyAE's mana/energy/experience are deliberately left for later, and are never re-invented
 * here -- two channels for one resource would show up twice in the terminal).
 *
 * <p>Implementations must be side-safe: the cell is asked about channels on the server tick thread and in
 * the terminal's listing path.
 *
 * @param <T> the channel's stack type ({@code IAEItemStack}, {@code IAEFluidStack}, ...)
 */
public interface CellChannel<T extends IAEStack<T>> {

    /** Stable id used in saved data and by the config UI: {@code "item"}, {@code "fluid"}, ... */
    String id();

    /** The AE2 channel this adapter speaks for. */
    IStorageChannel<T> storage();

    /**
     * Identity normalisation: a copy with the stack size forced to 1.
     *
     * <p>AE2's stack identity ignores the size, and every map/set in the cell is keyed by identity. Doing it
     * per channel keeps the core free of channel types (it cannot call {@code new AEItemStack(...)}).
     */
    T normalize(T key);

    /**
     * Rebuilds one key from NBT. Needed by the v1 save format and by saved data, so it has to come from the
     * channel: only the channel knows how its stacks deserialize.
     */
    T readNbt(NBTTagCompound tag);

    /**
     * Untyped bridge for the data layer, which reads keys for whatever channel a saved entry names (it only
     * has a {@code CellChannel<?>} in hand). Implementations just return {@link #readNbt}.
     */
    default IAEStack<?> readKeyUntyped(NBTTagCompound tag) {
        return readNbt(tag);
    }

    /**
     * Every key this channel can ever hand out, for mode 3 ("everything is infinite") and for the Instant-rule
     * scan. Must be deduplicated, free of NBT-bearing variants, and <b>read-only</b>: callers iterate it, they
     * do not modify it. Implementations cache it for good -- registries are frozen once the game has loaded.
     */
    List<T> allKeys();

    /**
     * Namespace of this key's owner (the mod id), for mod rules; {@code null} when the channel has no such
     * notion, which makes the key ineligible for a mod rule instead of throwing.
     */
    String namespace(T key);

    /**
     * Whether the key carries extra data (an NBT variant). Such keys never take the rule path -- 1.20 recorded
     * that they would otherwise be swallowed by the "accept without recording" branch and vanish.
     */
    boolean hasExtraData(T key);

    /**
     * The ore-dictionary rule names from {@code oreRules} that this key hits, or an empty set when none do.
     * Item channels ask OreDictionary; channels without ore names always answer empty, which is exactly
     * sensei's decision that ore rules are item-only (every channel still gets mod rules, whitelist,
     * blacklist, thresholds and quantity locks).
     *
     * <p>Returning the matched names (rather than a boolean) lets the caller consult each rule's own
     * Instant/Touch mode without re-testing the key, and stays allocation-free in the common case: the
     * implementation returns {@link java.util.Collections#emptySet()} when nothing matched.
     */
    Set<String> matchedOreRules(T key, Set<String> oreRules);

    /** Human-readable name for the panel, tooltips and command output. Server-side callable. */
    String displayName(T key);

    /**
     * Untyped bridge for the config panel, which lists whatever channel the player selected and therefore only
     * has an {@code IAEStack<?>} in hand. The cast is safe because the panel asks the adapter that owns the key.
     */
    @SuppressWarnings("unchecked")
    default String displayNameUntyped(IAEStack<?> key) {
        return key == null ? "" : displayName((T) key);
    }

    /**
     * The size this channel PUBLISHES for an "infinite" row.
     *
     * <p>Default is the historical {@link ChannelCellCore#INFINITE} ({@code Long.MAX_VALUE}). A channel can
     * lower it: AE2's {@code NetworkMonitor} SUMS what cells report into one aggregate list, so a value that is
     * half of {@code Long.MAX} or more wraps to a negative number the second time the same key is aggregated --
     * and a negative row does not exist as far as a terminal is concerned. Channel-wide listings (mode 3) make
     * that fatal for a whole channel: every key goes negative at once, taking normal cells in the same network
     * down with it.
     *
     * <p>Essentia lowers it today (sensei's decision, 2026-10-04: fix the broken channel only), because the
     * Thaumic Energistics terminal was where the overflow actually bit.
     */
    default long reportedInfinite() {
        return ChannelCellCore.INFINITE;
    }

    /** Threshold applied to a key of this channel when neither the item override nor the global one is set. */
    long defaultThreshold();

    /**
     * Name of the CHANNEL itself ("物品", "流体", ...), for the tooltip's per-channel lines and the command
     * output. Translation-side I18n on purpose: this is called from a client tooltip as well as from
     * server-side command output. Falls back to the raw id when the key is missing, so a channel added by a
     * bridge mod without lang entries still shows something readable.
     */
    default String channelName() {
        String key = "gui.ae2addon.channel." + id();
        // Original note (1.12.2): the translation-side I18n returns the key unchanged when it is missing.
        String translated = net.minecraft.util.text.translation.I18n.translateToLocal(key);
        return translated == null || translated.isEmpty() || translated.equals(key) ? id() : translated;
    }
}
