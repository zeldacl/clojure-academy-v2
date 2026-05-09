package cn.li.forge1201.client.render;

public final class ModRenderTypes {
        private ModRenderTypes() {
        }

        public static final com.mojang.blaze3d.vertex.VertexFormat PLASMA_BODY_FORMAT = cn.li.mc1201.client.render.ModRenderTypes.PLASMA_BODY_FORMAT;

        public static void setPlasmaBodyShader(net.minecraft.client.renderer.ShaderInstance shader) {
                cn.li.mc1201.client.render.ModRenderTypes.setPlasmaBodyShader(shader);
        }

        public static net.minecraft.client.renderer.ShaderInstance getPlasmaBodyShader() {
                return cn.li.mc1201.client.render.ModRenderTypes.getPlasmaBodyShader();
        }

        public static net.minecraft.client.renderer.RenderType plasmaBody() {
                return cn.li.mc1201.client.render.ModRenderTypes.plasmaBody();
        }
}
