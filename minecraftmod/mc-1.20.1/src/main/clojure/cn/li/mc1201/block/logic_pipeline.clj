(ns cn.li.mc1201.block.logic-pipeline
  "Loader-neutral tile bundle compile/install pipeline (shared by Forge and Fabric)."
  (:require [cn.li.mc1201.block.logic-compile :as logic-compile]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-kind :as tile-kind])
  (:import [cn.li.mc1201.block IScriptedBlock]
           [cn.li.mc1201.block.logic TileLogicBundle]))

(defn compile-all-bundles
  "Pure: tile-id → TileLogicBundle for every registered tile spec."
  []
  (into {}
        (map (fn [[tile-id spec]]
               [tile-id (logic-compile/compile-tile-logic
                          (tile-kind/merge-tile-kind-defaults spec))]))
        (tdsl/snapshot-tiles-by-id)))

(defn install-bundle-to-block!
  "Install a compiled bundle on a single IScriptedBlock instance."
  [^IScriptedBlock block ^TileLogicBundle bundle]
  (.installTileLogic block bundle))

(defn assert-all-blocks-have-bundle!
  "Fail-fast when any IScriptedBlock still has EMPTY logic (registration bug)."
  [blocks allow-empty-tile-ids]
  (doseq [b blocks
          :when (instance? IScriptedBlock b)
          :let [^IScriptedBlock sb b
                tid (.getTileId sb)]]
    (when (and (identical? TileLogicBundle/EMPTY (.getTileLogic sb))
               (not (contains? allow-empty-tile-ids tid)))
      (throw (IllegalStateException.
               (str "tile-id " tid " has empty TileLogicBundle"))))))
