package com.ae2addon.cell;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * A storage channel whose whole "registry" is ONE key: no variants, nothing to enumerate.
 *
 * <p>Mana (Botania Applie) and FE (Flux Applied) are like this: the resource is a single thing, so the key is
 * always the same and only the amount differs. Everything else about them is the ordinary judgement layer, so
 * the only per-channel work left is a name, a namespace and how to build that one key.
 *
 * <p>The five channel-specific answers that remain identical are implemented here once:
 * <ul>
 * <li>{@link #readNbt} delegates to the channel's own {@code createFromNBT} (it wrote the tag, it reads it),
 *     with a guard: a bridge mod's parser must never be able to cost a whole cell its save data.</li>
 * <li>{@link #hasExtraData} is false -- a single-key resource has no NBT variants.</li>
 * <li>{@link #matchedOreRules} is empty -- there is no ore dictionary for mana or energy.</li>
 * <li>{@link #allKeys} is that one key, cached.</li>
 * <li>{@link #defaultThreshold} is the cell default; note the threshold is SHARED across channels
 *     (sensei's "storage separate, settings shared"), so this is a reference value only.</li>
 * </ul>
 */
public abstract class SingleKeyCellChannel<T extends IAEStack<T>> implements CellChannel<T> {

    /** The one key this channel can ever hand out, at size 1 for identity. */
    protected abstract T singleKey();

    /** Namespace reported for mod rules (usually the bridge mod's own id). */
    protected abstract String namespaceId();

    /** Human-readable name, used by the panel/commands (server-side callable, so no client-only calls). */
    protected abstract String displayNameText();

    @Override
    public T readNbt(NBTTagCompound tag) {
        // Nothing in the tag identifies a single-key resource, and its key class may compare by INSTANCE
        // (FluxStack overrides equals without hashCode, see normalize below), so a freshly parsed instance would
        // never match what AE2 hands us later. Hand back the canonical instance instead.
        return singleKey();
    }

    /**
     * Identity form of this channel's key: the canonical instance, NOT a copy.
     *
     * <p>Copying is wrong here. {@code com.flux_applied.ae2.FluxStack} overrides {@code equals} but not
     * {@code hashCode} (javap, 2026-10-03), so two equal-valued instances hash differently and a copied key
     * never matches the stored one in our maps -- that was self-test P2 ("store 512, take 512 -> null") and P4
     * ("never reaches the threshold"). Single-key channels therefore canonicalise instead of copying.
     */
    @Override
    public T normalize(T key) {
        return singleKey();
    }

    @Override
    public boolean hasExtraData(T key) {
        return false;
    }

    @Override
    public Set<String> matchedOreRules(T key, Set<String> oreRules) {
        return Collections.emptySet();
    }

    @Override
    public String namespace(T key) {
        return namespaceId();
    }

    @Override
    public String displayName(T key) {
        return displayNameText();
    }

    @Override
    public long defaultThreshold() {
        return 65536L;
    }

    private transient List<T> allKeyCache = null;

    @Override
    public List<T> allKeys() {
        if (allKeyCache == null) {
            T key = singleKey();
            allKeyCache = key == null ? Collections.<T>emptyList()
                    : Collections.singletonList(normalize(key));
        }
        return allKeyCache;
    }
}