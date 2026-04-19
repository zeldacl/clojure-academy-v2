(ns cn.li.ac.content.ability-client
  "Client-side ability content bootstrap.

  Requires all _fx.clj namespaces so they self-register their FX channels,
  level effects, and hand effects at load time.

  This namespace must ONLY be required from client-side code paths
  (e.g. platform client entry points), never from dedicated-server code."
  (:require [cn.li.ac.content.ability.electromaster.railgun-fx]
            [cn.li.ac.content.ability.electromaster.mag-movement-fx]
            [cn.li.ac.content.ability.electromaster.thunder-bolt-fx]
            [cn.li.ac.content.ability.electromaster.thunder-clap-fx]
            [cn.li.ac.content.ability.electromaster.mine-detect-fx]
            [cn.li.ac.content.ability.meltdowner.meltdowner-fx]
            [cn.li.ac.content.ability.meltdowner.electron-bomb-fx]
            [cn.li.ac.content.ability.meltdowner.scatter-bomb-fx]
            [cn.li.ac.content.ability.meltdowner.light-shield-fx]
            [cn.li.ac.content.ability.meltdowner.mine-ray-fx]
            [cn.li.ac.content.ability.meltdowner.ray-barrage-fx]
            [cn.li.ac.content.ability.meltdowner.jet-engine-fx]
            [cn.li.ac.content.ability.meltdowner.electron-missile-fx]
            [cn.li.ac.content.ability.teleporter.mark-teleport-fx]
            [cn.li.ac.content.ability.teleporter.threatening-teleport-fx]
            [cn.li.ac.content.ability.teleporter.penetrate-teleport-fx]
            [cn.li.ac.content.ability.teleporter.flesh-ripping-fx]
            [cn.li.ac.content.ability.teleporter.shift-teleport-fx]
            [cn.li.ac.content.ability.teleporter.flashing-fx]
            [cn.li.ac.content.ability.vecmanip.blood-retrograde-fx]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave-fx]
            [cn.li.ac.content.ability.vecmanip.directed-shock-fx]
            [cn.li.ac.content.ability.vecmanip.groundshock-fx]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx]
            [cn.li.ac.content.ability.vecmanip.storm-wing-fx]
            [cn.li.ac.content.ability.vecmanip.vec-accel-fx]
            [cn.li.ac.content.ability.vecmanip.vec-deviation-fx]
            [cn.li.ac.content.ability.vecmanip.vec-reflection-fx]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private fx-initialized? (atom false))

(defn init-client-fx!
  "Ensure all client FX registrations have been loaded.
  Safe to call multiple times."
  []
  (when (compare-and-set! fx-initialized? false true)
    (log/info "Ability client FX content initialized")))
