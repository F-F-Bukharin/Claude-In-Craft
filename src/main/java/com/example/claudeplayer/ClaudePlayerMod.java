package com.example.claudeplayer;

import com.example.claudeplayer.client.GlanceRenderer;
import com.example.claudeplayer.net.BrainNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Mod entry. Everything per world starts in {@link AgentRuntime} when a server starts. */
@Mod(ClaudePlayerMod.MODID)
public final class ClaudePlayerMod {
    public static final String MODID = "claudeplayer";
    private static final Logger LOG = LogUtils.getLogger();

    public ClaudePlayerMod(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        BrainNetwork.register();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> GlanceRenderer::install); // a dedicated server has no window to glance with
        LOG.info("Claude Player loaded. Spawn the body with /cp spawn.");
    }
}
