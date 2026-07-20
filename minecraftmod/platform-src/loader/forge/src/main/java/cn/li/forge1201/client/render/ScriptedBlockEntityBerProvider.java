package cn.li.forge1201.client.render;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import cn.li.forge1201.block.entity.ScriptedBlockEntity;

/**
 * Java skeleton for {@link BlockEntityRendererProvider} — returns {@link ScriptedBlockEntityBer}.
 */
public final class ScriptedBlockEntityBerProvider implements BlockEntityRendererProvider<ScriptedBlockEntity> {
    @Override
    public BlockEntityRenderer<ScriptedBlockEntity> create(BlockEntityRendererProvider.Context ctx) {
        return new ScriptedBlockEntityBer();
    }
}
