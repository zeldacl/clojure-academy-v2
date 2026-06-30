package cn.li.mc1201.block.entity;

import cn.li.mc1201.block.IScriptedBlock;
import cn.li.mc1201.block.logic.ITileNbtLogic;
import cn.li.mc1201.block.logic.ITileTickLogic;
import cn.li.mc1201.block.logic.TileLogicBundle;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Objects;

/**
 * Shared scripted block-entity core for 1.20.1 loaders.
 *
 * <p>Server tick / NBT hooks dispatch through {@link IScriptedBlock#getTileLogic()}
 * bundles installed at registration time (no Clojure registry lookup on hot paths).</p>
 */
public abstract class AbstractScriptedBlockEntity extends BlockEntity {

    private final String tileId;
    private final String blockId;

    /** Primary state: Clojure persistent map. Null until first NBT load or tick.
     *  Volatile so render-thread reads see server-thread writes on integrated server. */
    private volatile Object customState = null;

    protected AbstractScriptedBlockEntity(BlockEntityType<?> type,
                                          BlockPos pos,
                                          BlockState state,
                                          String tileId,
                                          String blockId) {
        super(type, pos, state);
        this.tileId = tileId;
        this.blockId = blockId;
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

    private TileLogicBundle bundle() {
        Block block = getBlockState().getBlock();
        return (block instanceof IScriptedBlock scripted) ? scripted.getTileLogic() : TileLogicBundle.EMPTY;
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
        ITileNbtLogic nbt = bundle().nbt;
        if (nbt != null) {
            nbt.readNbt(this, tag);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ITileNbtLogic nbt = bundle().nbt;
        if (nbt != null) {
            nbt.writeNbt(this, tag);
        }
    }

    protected static void invokeServerTick(Level level, BlockPos pos, BlockState state, AbstractScriptedBlockEntity be) {
        if (level == null || level.isClientSide || be == null) {
            return;
        }
        ITileTickLogic tick = be.bundle().tick;
        if (tick != null) {
            tick.serverTick(level, pos, state, be);
        }
    }
}
