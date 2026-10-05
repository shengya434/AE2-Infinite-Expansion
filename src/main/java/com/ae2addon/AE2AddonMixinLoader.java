package com.ae2addon;

import java.util.Collections;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;
import zone.rong.mixinbooter.MixinLoader;

/**
 * Registers {@code ae2addon.mixins.json} the way MixinBooter actually discovers it.
 *
 * <p><b>Why this class exists</b> (found 2026-10-05, and it had been silently true for every pack run until
 * then): the mixin config was only announced through the {@code MixinConfigs} jar manifest attribute. That works
 * under MixinBooter 11.x, whose service scans every mod jar for that attribute -- the test instance runs 11.17, so
 * the drive patch and every other mixin applied there. In sensei's MeatballCraft pack (MixinBooter 10.7) the mixin
 * subsystem reports {@code Service=LaunchWrapper}, the plain Mixin service, which only looks at configs registered
 * through a loader or at a coremod's own manifest entry. Our jar is not a coremod and had no loader, so
 * <b>none of our mixins ever applied in that pack</b> -- silently, because there was nothing to fail:
 * <ul>
 * <li>the drive patch never bound our cell's extra channels (the log line "驱动器补丁未登记该绑定" appeared every
 *     single run there);</li>
 * <li>the temporary "who posts this change" diagnostic (2026-10-05) produced not one line in that pack while the
 *     same build printed them on the test instance;</li>
 * <li>and {@code MixinDriveWatcher} could not stop the {@code +-n} posts, which is why the infinite row kept
 *     "flashing" in the pack after it had been fixed on the test instance.</li>
 * </ul>
 *
 * <p>Implementing {@code ILateMixinLoader} and marking the class with {@code @MixinLoader} is MixinBooter's
 * documented route for a normal (non-coremod) mod to register its own config, and it is honoured by both 10.7 and
 * 11.x. "Late" is right for us: every target ({@code TileDrive}, {@code DriveWatcher}, {@code NetworkMonitor}) is
 * an AE2 class loaded well after the coremod phase.
 *
 * <p><b>This class must NOT live in the mixin package.</b> First attempt put it in {@code com.ae2addon.mixin} and
 * the game crashed on startup: Mixin treats every package owned by a config as its own, and refuses to let
 * anything load a normal class from there --
 * <pre>IllegalClassLoadError: com.ae2addon.mixin.AE2AddonMixinLoader is in a defined mixin package
 * com.ae2addon.mixin.* owned by ae2addon.mixins.json and cannot be referenced directly</pre>
 * (MixinBooter 10.7 happens not to check it, so the pack ran; 11.17 enforces it, so the test instance crashed.)
 * Keeping the loader one package up is the fix, and the reason it sits in {@code com.ae2addon} here.
 */
@MixinLoader
public class AE2AddonMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("ae2addon.mixins.json");
    }
}
