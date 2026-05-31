(ns cn.li.ac.content.ability.teleporter.location-teleport
  "LocationTeleport skill - teleport to saved locations.

  Original-aligned mechanics:
  - CP consume: lerp(200,150,exp) * dim-penalty(2x cross-dim) * max(8, sqrt(min(800, distance)))
  - Overload consume: 240
  - Cross-dimension requires exp > 0.8
  - Teleport nearby entities in radius 5 with relative offsets preserved
  - Cooldown: lerp(30,20,exp)
  - Exp gain: 0.015 (dist<200) or 0.03 (dist>=200)
  - Location name max length: 16
  - UI opened from key-down; actual add/remove/perform via RPC requests

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.util.math.vec3 :as vec3]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.named-position-store :as position-store]
            [cn.li.mcmod.network.server :as net-srv]
            [cn.li.ac.ability.messages :as catalog]
            [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]))

(def ^:private location-teleport-skill-id :location-teleport)

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id location-teleport-skill-id))

(defn- can-cross-dimension? [exp]
  (> (double exp) (helper/cfg-double location-teleport-skill-id
                                     :targeting.cross-dimension-exp-threshold)))

(defn- norm-name [s]
  (let [trimmed (-> (or s "") str str/trim)]
    (subs trimmed 0 (min (helper/cfg-int location-teleport-skill-id
                                         :ui.max-location-name-length)
                         (count trimmed)))))

(defn- compute-cp-cost [exp distance cross-dimension?]
  (let [base (helper/cfg-lerp location-teleport-skill-id :cost.perform.cp-base exp)
        dim-penalty (if cross-dimension?
                      (helper/cfg-double location-teleport-skill-id
                                         :cost.perform.cross-dimension-multiplier)
                      1.0)
        dist-mult (max (helper/cfg-double location-teleport-skill-id
                                          :cost.perform.min-distance-multiplier)
                       (Math/sqrt (min (helper/cfg-double location-teleport-skill-id
                                                          :cost.perform.distance-cap)
                                       (double distance))))]
    (* base dim-penalty dist-mult)))

(defn- compute-cooldown [exp]
  (helper/cfg-lerp-int location-teleport-skill-id :cooldown.ticks exp))

(defn- compute-exp-gain [distance]
  (if (>= (double distance) (helper/cfg-double location-teleport-skill-id
                                               :progression.long-distance-threshold))
    (helper/cfg-double location-teleport-skill-id :progression.exp-long)
    (helper/cfg-double location-teleport-skill-id :progression.exp-short)))

(defn- add-exp! [player-id amount]
  (skill-effects/add-skill-exp! player-id :location-teleport (double amount)))

(defn- consume-resource! [player-id overload cp]
  (boolean (:success? (skill-effects/perform-resource! player-id overload cp false))))

(defn- can-consume-resource?
  [player-id overload cp]
  (if-let [state (skill-effects/get-player-state player-id)]
    (boolean (rdata/can-perform? (:resource-data state)
                                 (double overload)
                                 (double cp)
                                 false))
    false))

(defn- current-pos [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))

(defn- all-locations [player-id]
  (if position-store/*named-position-store*
    (vec (position-store/list-locations position-store/*named-position-store* player-id))
    []))

(defn- max-saved-location-count []
  (long (ability-config/max-saved-locations)))

(defn- location-limit-reached?
  [player-id location-name]
  (let [store position-store/*named-position-store*]
    (and store
         (not (position-store/has-location? store player-id location-name))
         (>= (long (or (position-store/get-location-count store player-id) 0))
             (max-saved-location-count)))))

(defn- location-limits []
  {:cross-dimension-exp-threshold
   (helper/cfg-double location-teleport-skill-id
                      :targeting.cross-dimension-exp-threshold)
   :max-location-name-length
   (helper/cfg-int location-teleport-skill-id
                   :ui.max-location-name-length)
   :max-saved-locations
   (max-saved-location-count)})

(defn- location-with-stats [player-id exp cur-pos loc]
  (let [cross-dim? (not= (:world-id cur-pos) (:world-id loc))
        dist (vec3/euclidean-distance (:x cur-pos) (:y cur-pos) (:z cur-pos)
                    (:x loc) (:y loc) (:z loc))
        cp (compute-cp-cost exp dist cross-dim?)
  overload (helper/cfg-double location-teleport-skill-id
            :cost.perform.overload)
        no-exp? (and cross-dim? (not (can-cross-dimension? exp)))
  no-cp? (not (can-consume-resource? player-id overload cp))]
    (assoc loc
           :distance dist
           :cp-cost cp
           :cross-dimension? cross-dim?
           :can-perform? (and (not no-exp?) (not no-cp?))
           :error (cond
                    no-exp? :err-exp
                    no-cp? :err-cp
                    :else nil))))

(defn query-location-teleport
  "Fetch current location list and perform stats for UI.
  Returns {:success? boolean :locations [...] :exp double :current-pos map}."
  [player-id]
  (try
    (let [exp (double (or (skill-exp player-id) 0.0))
          pos (current-pos player-id)
          locations (all-locations player-id)
          with-stats (if pos
                       (mapv #(location-with-stats player-id exp pos %) locations)
                       locations)]
      {:success? true
       :exp exp
        :limits (location-limits)
       :current-pos pos
       :locations with-stats})
    (catch Exception e
      (log/warn "LocationTeleport query failed:" (ex-message e))
      {:success? false
       :error :query-failed
        :limits (location-limits)
       :locations []})))

(defn save-current-location!
  "Save player's current position with a provided name. Returns result map."
  [player-id location-name]
  (try
    (let [name* (norm-name location-name)]
      (cond
        (str/blank? name*)
        {:success? false :error :invalid-name}

        (not position-store/*named-position-store*)
        {:success? false :error :service-unavailable}

        :else
        (if-let [pos (current-pos player-id)]
          (if (location-limit-reached? player-id name*)
            {:success? false
             :error :location-limit-reached
             :max-locations (max-saved-location-count)}
            (let [ok? (position-store/save-location!
                        position-store/*named-position-store*
                        player-id
                        name*
                        (:world-id pos)
                        (:x pos)
                        (:y pos)
                        (:z pos))]
              (if ok?
                {:success? true :name name*}
                {:success? false :error :save-failed})))
          {:success? false :error :player-pos-unavailable})))
    (catch Exception e
      (log/warn "LocationTeleport save failed:" (ex-message e))
      {:success? false :error :save-failed})))

(defn delete-saved-location!
  "Delete a location by name. Returns result map."
  [player-id location-name]
  (try
    (let [name* (norm-name location-name)]
      (cond
        (str/blank? name*)
        {:success? false :error :invalid-name}

        (not position-store/*named-position-store*)
        {:success? false :error :service-unavailable}

        :else
        {:success? (boolean (position-store/delete-location!
                  position-store/*named-position-store*
                              player-id
                              name*))
         :name name*}))
    (catch Exception e
      (log/warn "LocationTeleport delete failed:" (ex-message e))
      {:success? false :error :delete-failed})))

(defn- action-response
  [op action snapshot]
  {:action (assoc action :op op)
   :snapshot snapshot})

(defn- response-for
  [op action-fn player-id]
  (let [action (action-fn)
        snapshot (query-location-teleport player-id)]
    (action-response op action snapshot)))

(defn perform-location-teleport!
  "Perform teleport to a saved location by name.
  Returns {:success? boolean ...} for client RPC callbacks."
  [player-id location-name]
  (try
    (if (or (not teleportation/*teleportation*)
            (not position-store/*named-position-store*))
      {:success? false :error :service-unavailable}
      (let [name* (norm-name location-name)
            exp (double (or (skill-exp player-id) 0.0))
            pos (current-pos player-id)
            dest (when (not (str/blank? name*))
                   (position-store/get-location position-store/*named-position-store* player-id name*))]
        (cond
          (str/blank? name*)
          {:success? false :error :invalid-name}

          (nil? pos)
          {:success? false :error :player-pos-unavailable}

          (nil? dest)
          {:success? false :error :location-not-found}

          :else
          (let [cross-dim? (not= (:world-id pos) (:world-id dest))
                _dist (vec3/euclidean-distance (:x pos) (:y pos) (:z pos)
                                               (:x dest) (:y dest) (:z dest))
                cp (compute-cp-cost exp _dist cross-dim?)
                can-cross? (or (not cross-dim?) (can-cross-dimension? exp))]
            (cond
              (not can-cross?)
              {:success? false :error :err-exp
               :require-exp (helper/cfg-double location-teleport-skill-id
                                               :targeting.cross-dimension-exp-threshold)
               :current-exp exp}

              (not (consume-resource! player-id
                                      (helper/cfg-double location-teleport-skill-id
                                                         :cost.perform.overload)
                                      cp))
              {:success? false :error :err-cp :cp-cost cp}

              :else
              (let [result (teleportation/teleport-with-entities!
                             teleportation/*teleportation*
                             player-id
                             (:world-id dest)
                             (:x dest)
                             (:y dest)
                             (:z dest)
                             (helper/cfg-double location-teleport-skill-id
                                                :targeting.teleport-radius))]
                (if-not (:success result)
                  {:success? false :error :teleport-failed}
                  (do
                    (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)
                    (add-exp! player-id (compute-exp-gain _dist))
                    (when cross-dim?
                      (ach-dispatcher/trigger-custom-event! player-id "teleporter.ignore_barrier"))
                    (skill-effects/set-main-cooldown! player-id location-teleport-skill-id
                                                       (compute-cooldown exp))
                    {:success? true
                     :name name*
                     :distance _dist
                     :teleported-count (:teleported-count result)
                     :target {:world-id (:world-id dest)
                              :x (:x dest) :y (:y dest) :z (:z dest)}}))))))))
    (catch Exception e
      (log/warn "LocationTeleport perform failed:" (ex-message e))
      {:success? false :error :perform-failed})))

(defn location-teleport-on-key-down
  "Open LocationTeleport UI with current locations and perform stats."
  [{:keys [player-id ctx-id]}]
  (try
    (let [payload (query-location-teleport player-id)]
      (ctx/ctx-send-to-client! ctx-id :location-teleport/ui-open payload))
    (catch Exception e
      (log/warn "LocationTeleport key-down failed:" (ex-message e)))))

(defn location-teleport-on-key-tick
  "No-op: interaction is handled by dedicated location teleport GUI RPC actions."
  [_]
  nil)

(defn location-teleport-on-key-up
  "No-op: perform is handled by GUI perform request for original behavior alignment."
  [_]
  nil)

(defn location-teleport-on-key-abort
  "No-op: UI lifecycle is client-managed and independent from key abort."
  [_]
  nil)

(defskill location-teleport
  :id :location-teleport
  :category-id :teleporter
  :name-key "ability.skill.teleporter.location_teleport"
  :description-key "ability.skill.teleporter.location_teleport.desc"
  :icon "textures/abilities/teleporter/skills/location_teleport.png"
  :level 3
  :controllable? false
  :ctrl-id :location-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (compute-cooldown (skill-exp player-id)))
  :pattern :release-cast
  :cooldown {:mode :manual}
  :actions {:down! location-teleport-on-key-down
            :tick! location-teleport-on-key-tick
            :up! location-teleport-on-key-up
            :abort! location-teleport-on-key-abort}
  :prerequisites [{:skill-id :penetrate-teleport :min-exp 0.8}
                  {:skill-id :mark-teleport :min-exp 0.8}])

(defn init!
  []
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-QUERY
    (fn [_payload player]
      (let [player-id (uuid/player-uuid player)
            snapshot (query-location-teleport player-id)]
        (action-response :query
                         {:success? (boolean (:success? snapshot))
                          :error (:error snapshot)}
                         snapshot))))
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-ADD
    (fn [{:keys [name]} player]
      (let [player-id (uuid/player-uuid player)]
        (response-for :add
                      #(save-current-location! player-id name)
                      player-id))))
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-REMOVE
    (fn [{:keys [name]} player]
      (let [player-id (uuid/player-uuid player)]
        (response-for :remove
                      #(delete-saved-location! player-id name)
                      player-id))))
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-PERFORM
    (fn [{:keys [name]} player]
      (let [player-id (uuid/player-uuid player)]
        (response-for :perform
                      #(perform-location-teleport! player-id name)
                      player-id))))
  nil)
