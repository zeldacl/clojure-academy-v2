package cn.li.mc262.block.logic;

import cn.li.mc262.block.entity.AbstractScriptedBlockEntity;
import javax.annotation.Nullable;

public interface ITileCapabilityLogic {
    @Nullable
    Object resolve(AbstractScriptedBlockEntity be, String capKey, @Nullable Object side);
}
