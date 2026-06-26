package cn.li.forge1201.client.render;

import cn.li.forge1201.MyMod1201;
import cn.li.mc1201.client.render.ModRenderTypes;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModShaders {
    private ModShaders() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        ForgeClientRenderRegistry.registerShaders(event);
    }

    public static ShaderInstance getPlasmaBodyShader() {
        return ForgeClientRenderRegistry.getPlasmaBodyShader();
    }

    public static ShaderInstance getSkillProgbarShader() {
        return ForgeClientRenderRegistry.getSkillProgbarShader();
    }

    public static ShaderInstance getMonoShader() {
        return ForgeClientRenderRegistry.getMonoShader();
    }

    public static ShaderInstance getAlphaDiscardShader() {
        return ForgeClientRenderRegistry.getAlphaDiscardShader();
    }
}
