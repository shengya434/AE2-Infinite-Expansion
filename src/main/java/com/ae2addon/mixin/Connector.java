package com.ae2addon.mixin;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

/**
 * Mixin Connector — 在 Mixin 启动时显式注册我们的 Mixin 配置。
 * 通过 JAR Manifest 中的 MixinConnector 条目自动发现。
 */
public class Connector implements IMixinConnector {
    @Override
    public void connect() {
        Mixins.addConfiguration("ae2addon.mixins.json");
    }
}
