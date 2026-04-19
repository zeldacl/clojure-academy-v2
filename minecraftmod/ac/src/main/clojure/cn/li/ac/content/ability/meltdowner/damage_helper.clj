(ns cn.li.ac.content.ability.meltdowner.damage-helper
  "Meltdowner damage helper: mark targets and amplify incoming damage while marked."
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(def ^:private mark-duration-ms 3000)
(defonce ^:private marks (atom {}))
(defonce ^:private handler-registered? (atom false))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- learned-rad-intensify?
  [player-id]
  (boolean
    (when-let [state (ps/get-player-state player-id)]
      (adata/is-learned? (:ability-data state) :rad-intensify))))

(defn mark-target!
  [attacker-id target-id]
  (when (and attacker-id target-id (learned-rad-intensify? attacker-id))
    (let [expire (+ (now-ms) mark-duration-ms)
          mark-rate (rad/rate attacker-id)]
      (swap! marks assoc target-id {:expire-at expire :rate mark-rate}))))

(defn- active-mark-rate
  [target-id]
  (let [t (now-ms)]
    (when-let [{:keys [expire-at rate]} (get @marks target-id)]
      (if (> (long expire-at) t)
        (double rate)
        (do
          (swap! marks dissoc target-id)
          nil)))))

(defn- damage-handler
  [player-id _attacker-id damage _damage-source]
  (if-let [mark-rate (active-mark-rate player-id)]
    [(* (double damage) mark-rate)
     {:handler :meltdowner/rad-intensify :rate mark-rate}]
    [(double damage) nil]))

(defn ensure-damage-handler!
  []
  (when (compare-and-set! handler-registered? false true)
    (damage-runtime/register-damage-handler!
      :meltdowner/rad-intensify
      damage-handler
      90)))

(ensure-damage-handler!)
