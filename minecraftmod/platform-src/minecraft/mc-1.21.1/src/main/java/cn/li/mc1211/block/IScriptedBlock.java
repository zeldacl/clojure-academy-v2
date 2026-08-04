package cn.li.mc1211.block;

import cn.li.mc1211.block.logic.TileLogicBundle;

public interface IScriptedBlock {
    TileLogicBundle getTileLogic();

    String getTileId();

    String getBlockId();

    void installTileLogic(TileLogicBundle bundle);
}
