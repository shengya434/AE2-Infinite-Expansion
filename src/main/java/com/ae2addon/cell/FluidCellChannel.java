package com.ae2addon.cell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.fluids.util.AEFluidStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * The fluid channel: AE2-UEL's own second storage channel, so this one needs no bridge mod.
 *
 * <p>Differences from {@link ItemCellChannel} that the judgement layer must not care about:
 * <ul>
 * <li><b>ore rules are item-only</b> (sensei's decision, 2026-10-03) -- there is no OreDictionary for
 *     fluids, so {@link #matchedOreRules} always answers empty and a fluid can never be reached by one.</li>
 * <li><b>counts are millibuckets</b>: 1000 mB = 1 bucket, which is why {@link #defaultThreshold} is 65536
 *     (about 64 buckets) instead of a stack-shaped number.</li>
 * <li><b>namespace</b>: {@code Fluid#getName()} is namespaced for mod fluids but plain {@code "water"} /
 *     {@code "lava"} for vanilla, so a missing colon means {@code minecraft} -- otherwise a {@code minecraft}
 *     mod rule would silently miss the two most common fluids in the pack.</li>
 * <li><b>extra data</b>: a {@code FluidStack} can carry an NBT tag; such a variant must stay out of the rule
 *     path exactly like a tagged item.</li>
 * </ul>
 */
public final class FluidCellChannel implements CellChannel<IAEFluidStack> {

    public static final FluidCellChannel INSTANCE = new FluidCellChannel();

    /** 65536 mB = 64 buckets, the 1.20 line's default threshold expressed in the fluid unit. */
    private static final long DEFAULT_FLUID_THRESHOLD = 65536L;

    private FluidCellChannel() {
    }

    @Override
    public String id() {
        return "fluid";
    }

    @Override
    public IStorageChannel<IAEFluidStack> storage() {
        return AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
    }

    @Override
    public IAEFluidStack readNbt(NBTTagCompound tag) {
        return AEFluidStack.fromNBT(tag);
    }

    @Override
    public IAEFluidStack normalize(IAEFluidStack key) {
        IAEFluidStack copy = key.copy();
        copy.setStackSize(1);
        return copy;
    }

    @Override
    public String namespace(IAEFluidStack key) {
        Fluid fluid = key.getFluid();
        if (fluid == null) {
            return null;
        }
        String name = fluid.getName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        int colon = name.indexOf(':');
        return colon < 0 ? "minecraft" : name.substring(0, colon);
    }

    /**
     * Fluid name as the registry search index's id (2026-10-10), with vanilla's bare names normalised to
     * {@code minecraft:} -- the same rule {@link #namespace} uses for mod rules, so "water",
     * "minecraft:water" and "minecraft" all work in the registry search.
     */
    @Override
    public String registryId(IAEFluidStack key) {
        Fluid fluid = key.getFluid();
        if (fluid == null) {
            return null;
        }
        String name = fluid.getName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }

    @Override
    public boolean hasExtraData(IAEFluidStack key) {
        FluidStack def = key.getFluidStack();
        return def != null && def.tag != null;
    }

    @Override
    public Set<String> matchedOreRules(IAEFluidStack key, Set<String> oreRules) {
        // 1.12.2 has no ore dictionary for fluids, and sensei decided ore rules stay item-only.
        return Collections.emptySet();
    }

    @Override
    public String displayName(IAEFluidStack key) {
        FluidStack def = key.getFluidStack();
        return def == null ? String.valueOf(key) : def.getLocalizedName();
    }

    @Override
    public long defaultThreshold() {
        return DEFAULT_FLUID_THRESHOLD;
    }

    /** Cached forever: the fluid registry is frozen once the game has loaded. Read-only for callers. */
    private transient List<IAEFluidStack> allKeyCache = null;

    /**
     * Every registered fluid, one key each -- this is what mode 3 ("everything is infinite") and the
     * Instant-rule scan walk for the fluid channel.
     *
     * <p>Cost is measured by the self-test's L group before it is trusted, the same way the item channel's
     * mode 3 was measured (H2): a big pack can register hundreds of fluids, including every mod's
     * intermediates.
     */
    @Override
    public List<IAEFluidStack> allKeys() {
        if (allKeyCache != null) {
            return allKeyCache;
        }
        List<IAEFluidStack> found = new ArrayList<>();
        Set<IAEFluidStack> seen = new LinkedHashSet<>();
        for (Fluid fluid : FluidRegistry.getRegisteredFluids().values()) {
            if (fluid == null) {
                continue;
            }
            IAEFluidStack key = AEFluidStack.fromFluidStack(new FluidStack(fluid, 1));
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
