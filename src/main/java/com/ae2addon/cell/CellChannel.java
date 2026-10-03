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
     * Rebuilds one key from NBT. Needed by the v1 save format and by the pre-per-channel data layout, so it
     * has to come from the channel: only the channel knows how its stacks deserialize.
     */
    T readNbt(NBTTagCompound tag);

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

    /** Threshold applied to a key of this channel when neither the item override nor the global one is set. */
    long defaultThreshold();
}
