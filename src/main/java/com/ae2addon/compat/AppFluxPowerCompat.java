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

    /** 给机器充能（多轮，可配单轮上限）：网络 FE → 机器能量槽；返回本次实际传输 FE。 */
    public static long feedEnergy(BlockEntity target, Direction side,
            appeng.api.networking.IGrid grid, IActionSource source, int passes, long perPassCap) {
        if (!isLoaded() || target == null || grid == null || passes <= 0) {
            return 0;
        }
        long total = 0;
        try {
            for (int i = 0; i < passes; i++) {
                long fe = feedEnergyOnce(target, side, grid, source, perPassCap);
                total += fe;
                if (fe <= 0) {
                    break; // 机器满了/网络空/不可收，继续轮无意义
                }
            }
        } catch (RuntimeException ignored) {
        }
        return total;
    }

    /** 给机器充能（多轮，默认每轮无上限）；兼容旧调用。 */
    public static long feedEnergy(BlockEntity target, Direction side,
            appeng.api.networking.IGrid grid, IActionSource source, int passes) {
        return feedEnergy(target, side, grid, source, passes, Long.MAX_VALUE);
    }

    /** 给机器充能（单轮）；保留原签名兼容旧调用。 */
    public static long feedEnergy(BlockEntity target, Direction side,
            appeng.api.networking.IGrid grid, IActionSource source) {
        return feedEnergy(target, side, grid, source, 1, Long.MAX_VALUE);
    }

    /**
     * 单轮充能：上限 perPassCap（long；无上限传 Long.MAX）。
     * 机器能量槽是 int 容量，缺口本身 ≤ int max；cap 超过缺口时等价灌满缺口。
     */
    private static long feedEnergyOnce(BlockEntity target, Direction side,
            appeng.api.networking.IGrid grid, IActionSource source, long perPassCap) {
        if (!isLoaded() || target == null || grid == null || perPassCap <= 0) {
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
            int gap = machine.getMaxEnergyStored() - machine.getEnergyStored();
            if (System.getProperty("ae2addon.debugPower") != null) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][feeder] 供电诊断: networkEnergy={} canExtract={} stored={}/{} cap={}FE/轮",
                        networkEnergy, networkEnergy.canExtract(),
                        machine.getEnergyStored(), machine.getMaxEnergyStored(), perPassCap);
            }
            if (gap <= 0) {
                return 0;
            }
            // 本轮上限（long）与机器缺口（int）取小；cap≥缺口时灌满缺口
            int need = (int) Math.min((long) gap, perPassCap);
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
