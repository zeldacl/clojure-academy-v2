package cn.li.mc1201.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public final class ModRenderTypes extends RenderType {
    public static final VertexFormat PLASMA_BODY_FORMAT = DefaultVertexFormat.POSITION;

    private static volatile ShaderInstance plasmaBodyShader;
    private static volatile ShaderInstance skillProgbarShader;
    private static volatile ShaderInstance monoShader;
    private static volatile ShaderInstance cpbarOverloadShader;
    private static volatile ShaderInstance alphaDiscardShader;

    private static final ShaderStateShard PLASMA_BODY_SHADER_STATE =
            new ShaderStateShard(ModRenderTypes::getPlasmaBodyShader);

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

    /**
     * Translucent textured-QUADS render type for the tp_mark humanoid — the
     * vanilla textSeeThrough pattern (RENDERTYPE_TEXT_SHADER samples the
     * texture times vertex colour, no lightmap modulation — the same
     * "texture true colours" semantics as upstream's ShaderSimple) with
     * depth test disabled and cull disabled. Upstream MarkRender draws the
     * mark with GL11.glDisable(GL_DEPTH_TEST) + glDisable(GL_CULL_FACE), so
     * the humanoid stays visible through walls when the destination is still
     * inside one (penetrate_teleport's unavailable case) and its back faces
     * never cull away. COLOR_WRITE skips depth writes like the vanilla
     * see-through types.
     */
    private static final Function<ResourceLocation, RenderType> ACADEMY_QUADS_TRANSLUCENT_BY_TEXTURE =
            Util.memoize(texture -> create(
                    "academy_quads_translucent",
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    true,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_TEXT_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)));

    public static RenderType academyQuadsTranslucent(ResourceLocation texture) {
        return ACADEMY_QUADS_TRANSLUCENT_BY_TEXTURE.apply(texture);
    }

    /**
     * Translucent LINES render type for ability aim markers — vanilla
     * RenderType.lines() has no transparency, so marker colors with low alpha
     * (upstream EntityMarker colors) render fully opaque and look wrong.
     * Depth test is disabled so the marker's bottom ring stays visible when it
     * sits flush with a surface hit (upstream shift_teleport's blockMarker
     * uses ignoreDepth = true for the same reason).
     */
    private static final RenderType ACADEMY_LINES_TRANSLUCENT = create(
            "academy_lines_translucent",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    // Upstream RenderMarker draws the corner ticks with
                    // GL11.glLineWidth(3f); let the render type own the GL
                    // state (Minecraft's LineStateShard) instead of raw GL.
                    .setLineState(new LineStateShard(java.util.OptionalDouble.of(3.0D)))
                    .createCompositeState(false));

    public static RenderType academyLinesTranslucent() {
        return ACADEMY_LINES_TRANSLUCENT;
    }

    private ModRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                           boolean affectsCrumbling, boolean sortOnUpload,
                           Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        throw new IllegalStateException("ModRenderTypes is utility-only");
    }

    public static void setPlasmaBodyShader(ShaderInstance shader) {
        plasmaBodyShader = shader;
    }

    public static ShaderInstance getPlasmaBodyShader() {
        return plasmaBodyShader;
    }

    public static void setSkillProgbarShader(ShaderInstance shader) {
        skillProgbarShader = shader;
    }

    public static ShaderInstance getSkillProgbarShader() {
        return skillProgbarShader;
    }

    public static void setMonoShader(ShaderInstance shader) {
        monoShader = shader;
    }

    public static ShaderInstance getMonoShader() {
        return monoShader;
    }

    public static void setCpbarOverloadShader(ShaderInstance shader) {
        cpbarOverloadShader = shader;
    }

    public static ShaderInstance getCpbarOverloadShader() {
        return cpbarOverloadShader;
    }

    public static void setAlphaDiscardShader(ShaderInstance shader) {
        alphaDiscardShader = shader;
    }

    public static ShaderInstance getAlphaDiscardShader() {
        return alphaDiscardShader;
    }

    public static RenderType plasmaBody() {
        return PLASMA_BODY_BY_KEY.apply("academy_plasma_body");
    }
}