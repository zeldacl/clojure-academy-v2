package cn.li.mc262.client.font.msdf;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.2 stub: RenderType moved to rendertype package and pipeline rewrite.
 * Keeps MSDF_TEXT_FORMAT for shader registration call sites.
 */
@OnlyIn(Dist.CLIENT)
public final class MsdfRenderTypes {
    private MsdfRenderTypes() {}

    public static final VertexFormat MSDF_TEXT_FORMAT = DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;

    public static void setMsdfShader(Object shader) {}
    public static Object getMsdfShader() { return null; }
    public static Object msdfText(Identifier texture) { return null; }
    public static Object glyphRenderTypes(Identifier texture) { return null; }
}
