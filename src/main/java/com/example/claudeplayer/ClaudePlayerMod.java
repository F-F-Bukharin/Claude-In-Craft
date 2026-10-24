package com.example.claudeplayer;

import com.example.claudeplayer.net.BrainNetwork;
import com.mojang.logging.LogUtils;
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
        LOG.info("Claude Player loaded. Spawn the body with /cp spawn.");
    }
}
