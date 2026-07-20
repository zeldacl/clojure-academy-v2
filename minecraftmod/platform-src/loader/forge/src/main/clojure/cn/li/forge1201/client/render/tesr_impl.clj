(ns cn.li.forge1201.client.render.tesr-impl
  "Forge 1.20.1: exposes `new-renderer` returning Java `ScriptedBlockEntityBer`
  (see ScriptedBlockEntityBer.java). Clojure `reify` on `BlockEntityRenderer` was
  skipped by the engine at runtime (no errors, no draw)."
  (:import [cn.li.forge1201.client.render ScriptedBlockEntityBer]
           [net.minecraft.client.renderer.blockentity BlockEntityRenderer]))

(defn new-renderer
  "Return a Java BlockEntityRenderer for ScriptedBlockEntity.
  Avoids Clojure `reify` on the generic BlockEntityRenderer interface,
  which can leave `render` uncalled at runtime on Forge 1.20.1."
  ^BlockEntityRenderer
  []
  (ScriptedBlockEntityBer.))
