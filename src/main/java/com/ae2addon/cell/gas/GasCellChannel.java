package com.ae2addon.cell.gas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ae2addon.cell.CellChannel;
import com.mekeng.github.common.me.data.IAEGasStack;
import com.mekeng.github.common.me.data.impl.AEGasStack;
import com.mekeng.github.common.me.storage.IGasStorageChannel;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The gas channel, provided by Mekanism Energistics ({@code mekeng}).
 *
 * <p>This class is OPTIONAL-DEPENDENCY code: it only ever gets loaded through {@link GasChannelDoor}, which
 * checks {@code ModList} without referencing a single type from here. If Mekanism Energistics (or Mekanism
 * itself) is missing, nothing in this package loads and the cell simply does not speak gas.
 *
 * <p>Channel-specific facts the judgement layer must not know:
 * <ul>
 * <li><b>no namespace</b>: Mekanism registers gases under plain names ({@code "hydrogen"}), so a missing colon
 *     means {@code mekanism} -- otherwise a {@code mekanism} mod rule would miss every gas.</li>
 * <li><b>no ore dictionary</b>: like fluids, gases can only be reached by mod rules (sensei's decision).</li>
 * <li><b>no NBT variants</b>: Mekanism's {@code GasStack} carries a gas and an amount, nothing else, so
 *     {@link #hasExtraData} is always false (unlike items and fluids).</li>
 * <li><b>amounts are millibuckets</b> in Mekanism's own accounting, which is why the default threshold is the
 *     same 65536 as the fluid channel.</li>
 * </ul>
 */
public final class GasCellChannel implements CellChannel<IAEGasStack> {

    public static final GasCellChannel INSTANCE = new GasCellChannel();

    private static final long DEFAULT_GAS_THRESHOLD = 65536L;

    private GasCellChannel() {
    }

    @Override
    public String id() {
        return "gas";
    }

    @Override
    public IStorageChannel<IAEGasStack> storage() {
        return AEApi.instance().storage().getStorageChannel(IGasStorageChannel.class);
    }

    @Override
    public IAEGasStack readNbt(NBTTagCompound tag) {
        return AEGasStack.of(tag);
    }

    @Override
    public IAEGasStack normalize(IAEGasStack key) {
        IAEGasStack copy = key.copy();
        copy.setStackSize(1);
        return copy;
    }

    @Override
    public String namespace(IAEGasStack key) {
        Gas gas = key.getGas();
        if (gas == null) {
            return null;
        }
        String name = gas.getName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        int colon = name.indexOf(':');
        return colon < 0 ? "mekanism" : name.substring(0, colon);
    }

    @Override
    public boolean hasExtraData(IAEGasStack key) {
        // Mekanism's GasStack is (gas, amount) only -- there is no tag to make a variant out of.
        return false;
    }

    @Override
    public Set<String> matchedOreRules(IAEGasStack key, Set<String> oreRules) {
        return Collections.emptySet();
    }

    @Override
    public String displayName(IAEGasStack key) {
        Gas gas = key.getGas();
        return gas == null ? String.valueOf(key) : gas.getLocalizedName();
    }

    @Override
    public long defaultThreshold() {
        return DEFAULT_GAS_THRESHOLD;
    }

    /** Cached forever: the gas registry is frozen once the game has loaded. Read-only for callers. */
    private transient List<IAEGasStack> allKeyCache = null;

    /** Every registered gas -- mode 3's listing and the Instant-rule scan for this channel. */
    @Override
    public List<IAEGasStack> allKeys() {
        if (allKeyCache != null) {
            return allKeyCache;
        }
        List<IAEGasStack> found = new ArrayList<>();
        Set<IAEGasStack> seen = new LinkedHashSet<>();
        for (Gas gas : GasRegistry.getRegisteredGasses()) {
            if (gas == null) {
                continue;
            }
            IAEGasStack key = AEGasStack.of(new GasStack(gas, 1));
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
