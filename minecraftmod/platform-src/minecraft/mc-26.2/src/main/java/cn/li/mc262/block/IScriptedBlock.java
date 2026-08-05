package cn.li.mc262.block;

import cn.li.mc262.block.logic.TileLogicBundle;

public interface IScriptedBlock {
    TileLogicBundle getTileLogic();

    String getTileId();

    String getBlockId();

    void installTileLogic(TileLogicBundle bundle);
}