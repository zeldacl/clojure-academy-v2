(ns cn.li.mc1201.gui.reactive.clock
  "Clock driver — sset the three clock signals each frame."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client Minecraft]))

(defn tick!
  "Update clock signals from Minecraft game state. Called once per frame.
   partial-ticks from Minecraft#getFrameTime.

   clock-ms uses System/currentTimeMillis (wall clock) so UI animations keep
   running even when the game is paused in a GUI screen.  game-ticks tracks
   the real game tick count for logic that needs actual in-game time."
  [^UiRt runtime partial-ticks]
  (let [^Minecraft mc (Minecraft/getInstance)
        pt (double (or partial-ticks 0.0))]
    (sig/sset-l! (rt/clock-ms-sig runtime) (System/currentTimeMillis))
    (sig/sset-d! (rt/partial-ticks-sig runtime) pt)
    (sig/sset-l! (rt/game-ticks-sig runtime)
                 (long (if-let [level (.level mc)] (.getGameTime level) 0)))))
