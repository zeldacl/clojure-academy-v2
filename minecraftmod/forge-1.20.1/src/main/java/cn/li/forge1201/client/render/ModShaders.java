package cn.li.forge1201.client.render;

import cn.li.forge1201.MyMod1201;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModShaders {
    private static ShaderInstance plasmaBodyShader;

    private ModShaders() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), new ResourceLocation(MyMod1201.MODID, "plasma_body"), ModRenderTypes.PLASMA_BODY_FORMAT),
                shader -> plasmaBodyShader = shader
        );
    }

    public static ShaderInstance getPlasmaBodyShader() {
        return plasmaBodyShader;
    }
}
