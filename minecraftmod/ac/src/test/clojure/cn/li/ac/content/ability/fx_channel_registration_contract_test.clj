(ns cn.li.ac.content.ability.fx-channel-registration-contract-test
  "Shared contract for content ability FX init!: each registers level-effect and FX handlers."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private fx-init-symbols
  '[cn.li.ac.content.ability.electromaster.arc-gen-fx/init!
    cn.li.ac.content.ability.electromaster.body-intensify-fx/init!
    cn.li.ac.content.ability.electromaster.current-charging-fx/init!
    cn.li.ac.content.ability.electromaster.mag-manip-fx/init!
    cn.li.ac.content.ability.electromaster.mag-movement-fx/init!
    cn.li.ac.content.ability.electromaster.mine-detect-fx/init!
    cn.li.ac.content.ability.electromaster.railgun-fx/init!
    cn.li.ac.content.ability.electromaster.thunder-bolt-fx/init!
    cn.li.ac.content.ability.electromaster.thunder-clap-fx/init!
    cn.li.ac.content.ability.meltdowner.electron-bomb-fx/init!
    cn.li.ac.content.ability.meltdowner.electron-missile-fx/init!
    cn.li.ac.content.ability.meltdowner.jet-engine-fx/init!
    cn.li.ac.content.ability.meltdowner.light-shield-fx/init!
    cn.li.ac.content.ability.meltdowner.meltdowner-fx/init!
    cn.li.ac.content.ability.meltdowner.mine-ray-fx/init!
    cn.li.ac.content.ability.meltdowner.rad-intensify-fx/init!
    cn.li.ac.content.ability.meltdowner.ray-barrage-fx/init!
    cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/init!
    cn.li.ac.content.ability.teleporter.flashing-fx/init!
    cn.li.ac.content.ability.teleporter.flesh-ripping-fx/init!
    cn.li.ac.content.ability.teleporter.location-teleport-fx/init!
    cn.li.ac.content.ability.teleporter.mark-teleport-fx/init!
    cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/init!
    cn.li.ac.content.ability.teleporter.shift-teleport-fx/init!
    cn.li.ac.content.ability.teleporter.teleporter-crit-fx/init!
    cn.li.ac.content.ability.teleporter.threatening-teleport-fx/init!
    cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/init!
    cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/init!
    cn.li.ac.content.ability.vecmanip.directed-shock-fx/init!
    cn.li.ac.content.ability.vecmanip.groundshock-fx/init!
    cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/init!
    cn.li.ac.content.ability.vecmanip.storm-wing-fx/init!
    cn.li.ac.content.ability.vecmanip.vec-accel-fx/init!
    cn.li.ac.content.ability.vecmanip.vec-deviation-fx/init!
    cn.li.ac.content.ability.vecmanip.vec-reflection-fx/init!])

(deftest all-content-ability-fx-inits-register-runtime-hooks-test
  (is (= 35 (count fx-init-symbols)))
  (doseq [init-sym fx-init-symbols]
    (let [level* (atom nil)
          channel* (atom nil)
          channels* (atom nil)]
      (with-redefs [level-effects/register-level-effect!
                    (fn [effect-id effect-map]
                      (reset! level* [effect-id effect-map])
                      nil)
                    fx-registry/register-fx-channel!
                    (fn [channel handler]
                      (reset! channel* {:channel channel :handler handler})
                      nil)
                    fx-registry/register-fx-channels!
                    (fn [channels handler]
                      (reset! channels* {:channels channels :handler handler})
                      nil)]
        ((requiring-resolve init-sym))
        (is (or (some? @level*) (some? @channel*) (some? @channels*))
            (str init-sym " should register level effect and/or FX channels"))))))
