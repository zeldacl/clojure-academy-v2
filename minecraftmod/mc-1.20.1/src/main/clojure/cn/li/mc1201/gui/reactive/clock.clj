(ns cn.li.mc1201.gui.reactive.clock
  "Clock driver — sset the three clock signals each frame."
  (:require [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client Minecraft]))

(defn tick!
  "Update clock signals from Minecraft game state. Called once per frame.
   partial-ticks from Minecraft#getFrameTime."
  [^UiRt rt partial-ticks]
  (let [^Minecraft mc (Minecraft/getInstance)
        game-time-ms (if-let [level (.level mc)]
                       (* (.getGameTime level) 50)
                       (System/currentTimeMillis))
        pt (double (or partial-ticks 0.0))]
    (sig/sset-l! (cn.li.mcmod.ui.runtime/clock-ms-sig rt) (long game-time-ms))
    (sig/sset-d! (cn.li.mcmod.ui.runtime/partial-ticks-sig rt) pt)
    (sig/sset-l! (cn.li.mcmod.ui.runtime/game-ticks-sig rt)
                 (long (if-let [level (.level mc)] (.getGameTime level) 0)))))
