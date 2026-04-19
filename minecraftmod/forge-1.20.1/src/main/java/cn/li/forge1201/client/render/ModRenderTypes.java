package cn.li.forge1201.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.function.Function;

public final class ModRenderTypes extends RenderType {
    public static final VertexFormat PLASMA_BODY_FORMAT = DefaultVertexFormat.POSITION;

    private static final ShaderStateShard PLASMA_BODY_SHADER_STATE =
            new ShaderStateShard(ModShaders::getPlasmaBodyShader);

    private static final CompositeState PLASMA_BODY_STATE = CompositeState.builder()
            .setShaderState(PLASMA_BODY_SHADER_STATE)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setWriteMaskState(COLOR_WRITE)
            .createCompositeState(false);

    private static final Function<String, RenderType> PLASMA_BODY_BY_KEY =
            Util.memoize(key -> create(
                    key,
                    PLASMA_BODY_FORMAT,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    PLASMA_BODY_STATE
            ));

    private ModRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                           boolean affectsCrumbling, boolean sortOnUpload,
                           Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        throw new IllegalStateException("ModRenderTypes is utility-only");
    }

    public static RenderType plasmaBody() {
        return PLASMA_BODY_BY_KEY.apply("my_mod_plasma_body");
    }
}
