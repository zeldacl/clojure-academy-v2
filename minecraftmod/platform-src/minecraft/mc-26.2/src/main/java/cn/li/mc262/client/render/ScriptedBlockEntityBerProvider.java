package cn.li.mc262.client.render;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ScriptedBlockEntityBerProvider {
    private ScriptedBlockEntityBerProvider() {}

    public static <T extends BlockEntity> BlockEntityRendererProvider<T, ?> provider() {
        return context -> new ScriptedBlockEntityBer<>(context);
    }
}
