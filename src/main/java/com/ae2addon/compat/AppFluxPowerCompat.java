package com.ae2addon.compat;

import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import com.glodblock.github.appflux.common.caps.NetworkFEPower;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * AppFlux 可选集成（2026-08-28 sensei：感应卡 = AppFlux 对机器的供电卡）。
 * <p>
 * 感应卡（appflux:induction_card）插入接口后：每 tick 从网络 FE 存储
 * （NetworkFEPower）给正面机器的 IEnergyStorage 充能。
 * <p>
 * compileOnly 依赖（libs/AppliedFlux jar），运行时未装 AppFlux 时短路。
 */
public final class AppFluxPowerCompat {

    private static boolean checked;
    private static boolean loaded;
    /** 每 tick 供电上限（FE；防单 tick 卡顿，可再调）。 */
    private static final long MAX_FE_PER_TICK = 100_000_000L;

    private AppFluxPowerCompat() {
    }

    public static boolean isLoaded() {
        if (!checked) {
            checked = true;
            loaded = ModList.get().isLoaded("appflux");
        }
        return loaded;
    }

    /** AppFlux 感应卡物品（注册表查询，未装返回 null）。 */
    public static net.minecraft.world.item.Item inductionCard() {
        if (!isLoaded()) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("appflux", "induction_card"));
    }

    /** 给机器充能：网络 FE → 机器能量槽；返回本次实际传输 FE。 */
    public static long feedEnergy(BlockEntity target, Direction side,
            appeng.api.networking.IGrid grid, IActionSource source) {
        if (!isLoaded() || target == null || grid == null) {
            return 0;
        }
        try {
            IStorageService storage = grid.getStorageService();
            if (storage == null) {
                return 0;
            }
            IEnergyStorage networkEnergy = NetworkFEPower.of(storage, source);
            if (networkEnergy == null || !networkEnergy.canExtract()) {
                return 0;
            }
            LazyOptional<IEnergyStorage> machineCap = target.getCapability(
                    ForgeCapabilities.ENERGY, side);
            if (!machineCap.isPresent()) {
                return 0;
            }
            IEnergyStorage machine = machineCap.orElse(null);
            if (machine == null || !machine.canReceive()) {
                if (System.getProperty("ae2addon.debugPower") != null) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][feeder] 供电诊断: 机器能量槽不可接收 (cap={} machine={})",
                            machineCap.isPresent(), machine);
                }
                return 0;
            }
            if (System.getProperty("ae2addon.debugPower") != null) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][feeder] 供电诊断: networkEnergy={} canExtract={} stored={}/{} 上限={}FE/t",
                        networkEnergy, networkEnergy.canExtract(),
                        machine.getEnergyStored(), machine.getMaxEnergyStored(), MAX_FE_PER_TICK);
            }
            int need = Math.min((int) MAX_FE_PER_TICK,
                    machine.getMaxEnergyStored() - machine.getEnergyStored());
            if (need <= 0) {
                return 0;
            }
            // 先模拟确认机器能收多少，再按量从网络扣，避免多扣
            int accepted = machine.receiveEnergy(need, true);
            if (accepted <= 0) {
                return 0;
            }
            int extracted = networkEnergy.extractEnergy(accepted, false);
            if (extracted <= 0) {
                return 0;
            }
            machine.receiveEnergy(extracted, false);
            return extracted;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
