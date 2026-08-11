package com.ae2addon.mixin;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * OmniSequence（molecularmanipulator）共存兼容插件。
 * <p>
 * OmniSequence 与本模组 mixin 了同一批 AE2 核心类（CraftingCpuLogic /
 * CraftingService / CraftingCPUCluster / CPUSelectionList），双重重定向会
 * 导致应用崩溃。检测到该 mod 加载时，跳过目标类重叠的 mixin。
 * <p>
 * 参考实现：OmniSequence 的 MolecularManipulatorMixinPlugin（MIT）。
 */
public final class OmniCompatMixinPlugin implements IMixinConfigPlugin {

    /** OmniSequence 的 mod id（gradle.properties: mod_id） */
    private static final String OMNI_MOD_ID = "molecularmanipulator";

    /** 与 OmniSequence 目标类重叠、需要跳过的本模组 mixin */
    private static final Set<String> CONFLICTING_MIXINS = Set.of(
            "com.ae2addon.mixin.CraftingCpuLogicMixin",
            "com.ae2addon.mixin.CraftingServiceMixin",
            "com.ae2addon.mixin.CraftingCPUClusterMixin",
            "com.ae2addon.mixin.CraftingCPUClusterAccessor",
            "com.ae2addon.mixin.CPUSelectionListMixin"
    );

    private boolean omniLoaded = false;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            var loadingModList = FMLLoader.getLoadingModList();
            omniLoaded = loadingModList != null
                    && loadingModList.getModFileById(OMNI_MOD_ID) != null;
        } catch (Throwable t) {
            // FMLLoader 不可用时按未加载处理，mixin 全部正常应用
            omniLoaded = false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (omniLoaded && CONFLICTING_MIXINS.contains(mixinClassName)) {
            System.out.println("[AE2Addon] OmniCompat: OmniSequence 已加载，跳过冲突 mixin "
                    + mixinClassName);
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
