package com.ae2addon.cell.essentia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ae2addon.cell.CellChannel;
import com.ae2addon.cell.ChannelCellCore;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;
import thaumcraft.api.aspects.Aspect;
import thaumicenergistics.api.EssentiaStack;
import thaumicenergistics.api.storage.IAEEssentiaStack;
import thaumicenergistics.api.storage.IEssentiaStorageChannel;
import thaumicenergistics.integration.appeng.AEEssentiaStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The essentia channel, provided by Thaumic Energistics ({@code thaumicenergistics}).
 *
 * <p>Optional-dependency code: only reachable through {@link EssentiaChannelDoor}, which never names a type
 * from here (or from Thaumcraft) so that a pack without the bridge mod does not load this class at all.
 *
 * <p>Channel-specific facts:
 * <ul>
 * <li><b>the key is an aspect</b> ({@code Aspect}), not a resource: {@code Aer}, {@code Ignis}, ... Aspect tags
 *     carry no namespace, so a missing colon means {@code thaumcraft} -- while a Thaumcraft addon that
 *     registers its own aspects still gets its own mod id as the namespace and can be reached by a mod rule.</li>
 * <li><b>no ore dictionary</b>: mod rules only, like fluids and gases.</li>
 * <li><b>no NBT variants</b>: an essentia stack is (aspect, amount).</li>
 * <li><b>display name</b>: {@code Aspect.getLocalizedDescription()} goes through the translation-side I18n
 *     (verified by javap: {@code net.minecraft.util.text.translation.I18n.func_74838a}), so it is safe to call
 *     from the server -- which is where the panel and the commands ask for it.</li>
 * </ul>
 */
public final class EssentiaCellChannel implements CellChannel<IAEEssentiaStack> {

    public static final EssentiaCellChannel INSTANCE = new EssentiaCellChannel();

    /** Essentia is counted in single units, not millibuckets; see the class javadoc of the core for the shared threshold. */
    private static final long DEFAULT_ESSENTIA_THRESHOLD = 65536L;

    private EssentiaCellChannel() {
    }

    /**
     * Essentia publishes a sum-safe value for its infinite rows ({@link ChannelCellCore#SAFE_INFINITE}).
     *
     * <p>Why only essentia (sensei's call, 2026-10-04): the Thaumic Energistics terminal is where the overflow
     * actually bit. AE2's {@code NetworkMonitor} sums what cells report, so a {@code Long.MAX_VALUE} row wraps
     * negative the second time the same key is aggregated -- and since mode 3 covers EVERY aspect at once, the
     * whole essentia channel went negative, taking a normal Thaumic Energistics cell in the same network down
     * with it (sensei's three-way experiment). Every other channel is left exactly as it was; if another
     * terminal shows the same symptom, opting it in is a one-line override here.
     */
    @Override
    public long reportedInfinite() {
        return ChannelCellCore.SAFE_INFINITE;
    }

    @Override
    public String id() {
        return "essentia";
    }

    @Override
    public IStorageChannel<IAEEssentiaStack> storage() {
        return AEApi.instance().storage().getStorageChannel(IEssentiaStorageChannel.class);
    }

    @Override
    public IAEEssentiaStack readNbt(NBTTagCompound tag) {
        return AEEssentiaStack.fromNBT(tag);
    }

    @Override
    public IAEEssentiaStack normalize(IAEEssentiaStack key) {
        IAEEssentiaStack copy = key.copy();
        copy.setStackSize(1);
        return copy;
    }

    @Override
    public String namespace(IAEEssentiaStack key) {
        Aspect aspect = key.getAspect();
        if (aspect == null) {
            return null;
        }
        String tag = aspect.getTag();
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        int colon = tag.indexOf(':');
        return colon < 0 ? "thaumcraft" : tag.substring(0, colon);
    }

    @Override
    public boolean hasExtraData(IAEEssentiaStack key) {
        // (aspect, amount) only -- nothing to make a variant out of.
        return false;
    }

    @Override
    public Set<String> matchedOreRules(IAEEssentiaStack key, Set<String> oreRules) {
        return Collections.emptySet();
    }

    @Override
    public String displayName(IAEEssentiaStack key) {
        Aspect aspect = key.getAspect();
        if (aspect == null) {
            return String.valueOf(key);
        }
        String localized = aspect.getLocalizedDescription();
        return localized == null || localized.isEmpty() ? aspect.getName() : localized;
    }

    @Override
    public long defaultThreshold() {
        // NOTE: the cell's threshold is SHARED across channels (sensei's "storage separate, settings shared"),
        // so this per-channel default is not wired in yet -- the shared value wins. Reported to sensei as an
        // open question, because 65536 essentia is far more than a jar holds.
        return DEFAULT_ESSENTIA_THRESHOLD;
    }

    /** Cached forever: aspects are registered during mod loading. Read-only for callers. */
    private transient List<IAEEssentiaStack> allKeyCache = null;

    /** Every registered aspect (primal + compound + addon ones) -- mode 3's listing for this channel. */
    @Override
    public List<IAEEssentiaStack> allKeys() {
        if (allKeyCache != null) {
            return allKeyCache;
        }
        List<IAEEssentiaStack> found = new ArrayList<>();
        Set<IAEEssentiaStack> seen = new LinkedHashSet<>();
        for (Aspect aspect : Aspect.aspects.values()) {
            if (aspect == null) {
                continue;
            }
            IAEEssentiaStack key = AEEssentiaStack.fromEssentiaStack(new EssentiaStack(aspect, 1));
            if (key == null) {
                continue;
            }
            if (seen.add(normalize(key))) {
                found.add(key);
            }
        }
        allKeyCache = found;
        return found;
    }
}