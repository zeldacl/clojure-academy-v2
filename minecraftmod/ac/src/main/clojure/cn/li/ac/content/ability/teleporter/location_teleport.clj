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
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.saved-locations :as saved-locations]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.network.server :as net-srv]
            [cn.li.mcmod.ability.catalog :as catalog]
            [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

(def ^:private overload-cost 240.0)
(def ^:private teleport-radius 5.0)
(def ^:private max-location-name-length 16)

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :location-teleport :exp] 0.0)))

(defn- can-cross-dimension? [exp]
  (> (double exp) 0.8))

(defn- norm-name [s]
  (let [trimmed (-> (or s "") str str/trim)]
    (subs trimmed 0 (min max-location-name-length (count trimmed)))))

(defn- calculate-distance [x1 y1 z1 x2 y2 z2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1))
                (* (- z2 z1) (- z2 z1)))))

(defn- compute-cp-cost [exp distance cross-dimension?]
  (let [base (bal/lerp 200.0 150.0 exp)
        dim-penalty (if cross-dimension? 2.0 1.0)
        dist-mult (max 8.0 (Math/sqrt (min 800.0 (double distance))))]
    (* base dim-penalty dist-mult)))

(defn- compute-cooldown [exp]
  (int (bal/lerp 30.0 20.0 exp)))

(defn- compute-exp-gain [distance]
  (if (>= (double distance) 200.0) 0.03 0.015))

(defn- add-exp! [player-id amount]
  (skill-effects/add-skill-exp! player-id :location-teleport (double amount)))

(defn- consume-resource! [player-id overload cp]
  (boolean (:success? (skill-effects/perform-resource! player-id overload cp false))))

(defn- current-pos [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))

(defn- all-locations [player-id]
  (if saved-locations/*saved-locations*
    (vec (saved-locations/list-locations saved-locations/*saved-locations* player-id))
    []))

(defn- location-with-stats [player-id exp cur-pos loc]
  (let [cross-dim? (not= (:world-id cur-pos) (:world-id loc))
        dist (calculate-distance (:x cur-pos) (:y cur-pos) (:z cur-pos)
                                 (:x loc) (:y loc) (:z loc))
        cp (compute-cp-cost exp dist cross-dim?)
        no-exp? (and cross-dim? (not (can-cross-dimension? exp)))
        no-cp? (let [state (ps/get-player-state player-id)]
                 (if state
                   (not (rdata/can-perform? (:resource-data state) overload-cost cp false))
                   true))]
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
       :current-pos pos
       :locations with-stats})
    (catch Exception e
      (log/warn "LocationTeleport query failed:" (ex-message e))
      {:success? false :error :query-failed :locations []})))

(defn save-current-location!
  "Save player's current position with a provided name. Returns result map."
  [player-id location-name]
  (try
    (let [name* (norm-name location-name)]
      (cond
        (str/blank? name*)
        {:success? false :error :invalid-name}

        (not saved-locations/*saved-locations*)
        {:success? false :error :saved-locations-unavailable}

        :else
        (if-let [pos (current-pos player-id)]
          (let [ok? (saved-locations/save-location!
                      saved-locations/*saved-locations*
                      player-id
                      name*
                      (:world-id pos)
                      (:x pos)
                      (:y pos)
                      (:z pos))]
            (if ok?
              {:success? true :name name*}
              {:success? false :error :save-failed}))
          {:success? false :error :player-pos-unavailable})))
    (catch Exception e
      (log/warn "LocationTeleport save failed:" (ex-message e))
      {:success? false :error :save-failed})))

(defn delete-saved-location!
  "Delete a location by name. Returns result map."
  [player-id location-name]
  (try
    (let [name* (norm-name location-name)]
      (if (or (clojure.string/blank? name*) (not saved-locations/*saved-locations*))
        {:success? false :error :invalid-name}
        {:success? (boolean (saved-locations/delete-location!
                              saved-locations/*saved-locations*
                              player-id
                              name*))
         :name name*}))
    (catch Exception e
      (log/warn "LocationTeleport delete failed:" (ex-message e))
      {:success? false :error :delete-failed})))

(defn perform-location-teleport!
  "Perform teleport to a saved location by name.
  Returns {:success? boolean ...} for client RPC callbacks."
  [player-id location-name]
  (try
    (if (or (not teleportation/*teleportation*)
            (not saved-locations/*saved-locations*))
      {:success? false :error :service-unavailable}
      (let [name* (norm-name location-name)
            exp (double (or (skill-exp player-id) 0.0))
            pos (current-pos player-id)
            dest (when (not (str/blank? name*))
                   (saved-locations/get-location saved-locations/*saved-locations* player-id name*))]
        (cond
          (str/blank? name*)
          {:success? false :error :invalid-name}

          (nil? pos)
          {:success? false :error :player-pos-unavailable}

          (nil? dest)
          {:success? false :error :location-not-found}

          :else
          (let [cross-dim? (not= (:world-id pos) (:world-id dest))
                _dist (calculate-distance (:x pos) (:y pos) (:z pos)
                                          (:x dest) (:y dest) (:z dest))
                cp (compute-cp-cost exp _dist cross-dim?)
                can-cross? (or (not cross-dim?) (can-cross-dimension? exp))]
            (cond
              (not can-cross?)
              {:success? false :error :err-exp :require-exp 0.8 :current-exp exp}

              (not (consume-resource! player-id overload-cost cp))
              {:success? false :error :err-cp :cp-cost cp}

              :else
              (let [result (teleportation/teleport-with-entities!
                             teleportation/*teleportation*
                             player-id
                             (:world-id dest)
                             (:x dest)
                             (:y dest)
                             (:z dest)
                             teleport-radius)]
                (if-not (:success result)
                  {:success? false :error :teleport-failed}
                  (do
                    (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)
                    (add-exp! player-id (compute-exp-gain _dist))
                    (skill-effects/set-main-cooldown! player-id :location-teleport
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

(defskill! location-teleport
  :id :location-teleport
  :category-id :teleporter
  :name-key "ability.skill.teleporter.location_teleport"
  :description-key "ability.skill.teleporter.location_teleport.desc"
  :icon "textures/abilities/teleporter/skills/location_teleport.png"
  :level 2
  :controllable? false
  :ctrl-id :location-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 25
  :pattern :release-cast
  :cooldown {:mode :manual}
  :actions {:down! location-teleport-on-key-down
            :tick! location-teleport-on-key-tick
            :up! location-teleport-on-key-up
            :abort! location-teleport-on-key-abort}
  :prerequisites [{:skill-id :mark-teleport :min-exp 0.5}])

;; ============================================================================
;; Network handler self-registration (LocationTeleport GUI RPC)
;; ============================================================================

(defn- uuid-of [player]
  (str (entity/player-get-uuid player)))

(net-srv/register-handler catalog/MSG-REQ-LOCATION-TELEPORT-QUERY
  (fn [_payload player]
    (query-location-teleport (uuid-of player))))

(net-srv/register-handler catalog/MSG-REQ-LOCATION-TELEPORT-ADD
  (fn [{:keys [name]} player]
    (let [uuid (uuid-of player)
          result (save-current-location! uuid name)]
      (merge result (query-location-teleport uuid)))))

(net-srv/register-handler catalog/MSG-REQ-LOCATION-TELEPORT-REMOVE
  (fn [{:keys [name]} player]
    (let [uuid (uuid-of player)
          result (delete-saved-location! uuid name)]
      (merge result (query-location-teleport uuid)))))

(net-srv/register-handler catalog/MSG-REQ-LOCATION-TELEPORT-PERFORM
  (fn [{:keys [name]} player]
    (let [uuid (uuid-of player)
          result (perform-location-teleport! uuid name)]
      (merge result (query-location-teleport uuid)))))
