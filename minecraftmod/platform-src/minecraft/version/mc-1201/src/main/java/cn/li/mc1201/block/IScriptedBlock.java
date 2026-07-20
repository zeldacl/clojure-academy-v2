package cn.li.mc1201.block;

import cn.li.mc1201.block.logic.TileLogicBundle;

public interface IScriptedBlock {
    TileLogicBundle getTileLogic();

    String getTileId();

    String getBlockId();

    void installTileLogic(TileLogicBundle bundle);
}
