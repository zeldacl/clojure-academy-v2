(ns cn.li.mc262.gui.reactive.clock
  "Clock driver — set the three clock signals each frame."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mc262.bridge McAccess]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client DeltaTracker Minecraft]))

(defn- resolve-partial-ticks
  ^double
  [^Minecraft mc partial-ticks]
  (if (some? partial-ticks)
    (double partial-ticks)
    (let [^DeltaTracker dt (.getDeltaTracker mc)]
      (double (if dt (.getGameTimeDeltaPartialTick dt false) 0.0)))))

(defn- resolve-game-ticks
  ^long
  [^Minecraft mc]
  (or (when-let [server (.getSingleplayerServer mc)]
        (long (McAccess/serverTickCount server)))
      (when-let [level (.level mc)]
        (long (McAccess/dayTime level)))
      0))

(defn tick!
  "Update clock signals from Minecraft game state. Called once per frame.

   clock-ms uses System/currentTimeMillis (wall clock) so UI animations keep
   running even when the game is paused in a GUI screen.  game-ticks prefers
   integrated-server tick count; falls back to overworld clock time on remote
   clients (Level.getGameTime removed in 26.2)."
  [^UiRt runtime partial-ticks]
  (let [^Minecraft mc (Minecraft/getInstance)
        pt (resolve-partial-ticks mc partial-ticks)]
    (sig/sset-l! (rt/clock-ms-sig runtime) (System/currentTimeMillis))
    (sig/sset-d! (rt/partial-ticks-sig runtime) pt)
    (sig/sset-l! (rt/game-ticks-sig runtime) (resolve-game-ticks mc))))
