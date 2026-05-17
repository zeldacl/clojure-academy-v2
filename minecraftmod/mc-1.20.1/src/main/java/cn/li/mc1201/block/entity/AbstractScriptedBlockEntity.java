package cn.li.mc1201.block.entity;

import clojure.lang.RT;
import clojure.lang.Var;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Objects;

/**
 * Shared scripted block-entity core for 1.20.1 loaders.
 *
 * <p>Contains the common Clojure tile-logic integration (NBT + tick + state)
 * while loader-specific features (Forge capabilities/container wiring, etc.)
 * remain in platform subclasses.</p>
 */
public abstract class AbstractScriptedBlockEntity extends BlockEntity {

    private final String tileId;
    private final String blockId;

    /** Primary state: Clojure persistent map. Null until first NBT load or tick. */
    private Object customState = null;

    protected AbstractScriptedBlockEntity(BlockEntityType<?> type,
                                          BlockPos pos,
                                          BlockState state,
                                          String tileId,
                                          String blockId) {
        super(type, pos, state);
        this.tileId = tileId;
        this.blockId = blockId;
    }

    protected String tileLogicNamespace() {
        return "cn.li.mcmod.block.tile-logic";
    }

    public String getTileId() {
        return tileId;
    }

    public String getBlockId() {
        return blockId;
    }

    public Object getCustomState() {
        return customState;
    }

    public void setCustomState(Object state) {
        if (Objects.equals(this.customState, state)) {
            return;
        }
        this.customState = state;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState blockState = getBlockState();
            level.sendBlockUpdated(worldPosition, blockState, blockState, 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            handleUpdateTag(tag);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            Var readNbt = RT.var(tileLogicNamespace(), "read-nbt");
            Object data = readNbt.invoke(tileId, tag);
            if (data != null) {
                customState = data;
            }
        } catch (Exception ex) {
            customState = null;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        try {
            Var writeNbt = RT.var(tileLogicNamespace(), "write-nbt");
            writeNbt.invoke(tileId, this, tag);
        } catch (Exception ex) {
            // Clojure tile logic logs hook failures; Java fallback state was removed.
        }
    }

    protected static void invokeServerTick(Level level, BlockPos pos, BlockState state, AbstractScriptedBlockEntity be) {
        if (level == null || level.isClientSide || be == null) {
            return;
        }
        try {
            Var invokeTick = RT.var(be.tileLogicNamespace(), "invoke-tick");
            invokeTick.invoke(be.tileId, level, pos, state, be);
        } catch (Exception ex) {
            // Silent; log via Clojure if needed
        }
    }
}
