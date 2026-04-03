(ns cn.li.forge1201.client.ability-hud
  "CLIENT-ONLY ability HUD data model builder.

  Rendering hook integration is intentionally thin here; this file prepares
  stable data for any future GUI overlay renderer."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.forge1201.client.ability-runtime :as runtime]
            [cn.li.mcmod.util.log :as log]))

(defn- source-state [player-uuid]
  (or (ps/get-player-state player-uuid)
      (runtime/latest-sync player-uuid)))

(defn hud-model
  [player-uuid]
  (when-let [s (source-state player-uuid)]
    (let [r (:resource-data s)
          p (:preset-data s)]
      {:cp {:cur (:cur-cp r) :max (:max-cp r)}
       :overload {:cur (:cur-overload r) :max (:max-overload r) :fine (:overload-fine r)}
       :activated (:activated r)
       :active-preset (:active-preset p)
       :active-slots (mapv #(get-in p [:slots [(:active-preset p) %]]) (range 4))})))

(defn init! []
  (log/info "Ability HUD model provider initialized"))
