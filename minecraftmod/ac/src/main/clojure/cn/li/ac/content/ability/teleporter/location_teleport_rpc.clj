(ns cn.li.ac.content.ability.teleporter.location-teleport-rpc
  "Persistence/UI bridge for Location Teleport.

   This namespace owns only the saved-name CRUD protocol and the preview
   snapshot used by the screen. The actual teleport is dispatched to the
   migrated Combat Core EDN program; no skill effect is implemented here."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.resource :as resource]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.network.server :as net-srv]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

(def ^:private skill-id :location-teleport)

(defn- td [field] (skill-config/tunable-double skill-id field))
(defn- tdl [field] (skill-config/tunable-double-list skill-id field))
(defn- ti [field] (skill-config/tunable-int skill-id field))
(defn- lerp [field exp]
  (let [[lo hi] (tdl field)]
    (+ (double lo) (* (- (double hi) (double lo)) (double exp)))))
(defn- norm-name [value]
  (let [name* (str/trim (str (or value "")))]
    (subs name* 0 (min (ti :ui.max-location-name-length) (count name*)))))
(defn- position [owner]
  (when (teleportation/available?)
    (teleportation/player-position (str owner))))
(defn- exp [owner]
  (double (or (skill-effects/skill-exp (str owner) skill-id) 0.0)))
(defn- cp-cost [mastery distance cross?]
  (let [base (lerp :cost.perform.cp-base mastery)
        dim (if cross? (td :cost.perform.cross-dimension-multiplier) 1.0)
        distance-factor (max (td :cost.perform.min-distance-multiplier)
                             (Math/sqrt (min (td :cost.perform.distance-cap)
                                             (double distance))))]
    (* base dim distance-factor)))
(defn- distance [a b]
  (let [dx (- (double (:x b)) (double (:x a)))
        dy (- (double (:y b)) (double (:y a)))
        dz (- (double (:z b)) (double (:z a)))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))
(defn- locations [owner]
  (if (teleportation/named-position-available?)
    (vec (teleportation/list-saved-locations owner))
    []))
(defn- limits []
  {:cross-dimension-exp-threshold (td :targeting.cross-dimension-exp-threshold)
   :max-location-name-length (ti :ui.max-location-name-length)})
(defn- with-stats [owner player current mastery location]
  (let [cross? (not= (:world-id current) (:world-id location))
        distance (distance current location)
        cp (cp-cost mastery distance cross?)
        enough-exp? (or (not cross?)
                        (> mastery (td :targeting.cross-dimension-exp-threshold)))
        state (skill-effects/get-player-state (str owner))
        creative? (boolean (and player (entity/player-creative? player)))
        enough-resource? (boolean (and state
                                       (resource/can-perform?
                                        (:resource-data state)
                                        (td :cost.perform.overload)
                                        cp creative?)))]
    (assoc location
           :distance distance
           :cp-cost cp
           :cross-dimension? cross?
           :can-perform? (and enough-exp? enough-resource?)
           :error (cond
                    (not enough-exp?) :err-exp
                    (not enough-resource?) :err-cp
                    :else nil))))

(defn query-location-teleport
  ([owner] (query-location-teleport owner nil))
  ([owner player]
   (try
     (let [mastery (exp owner)
           current (position owner)
           saved (locations owner)]
       {:success? true :exp mastery :limits (limits) :current-pos current
        :locations (if current
                     (mapv #(with-stats owner player current mastery %) saved)
                     saved)})
     (catch Throwable throwable
       (log/warn "Location Teleport snapshot failed:" (ex-message throwable))
       {:success? false :error :query-failed :limits (limits) :locations []}))))

(defn save-current-location! [owner value]
  (let [name* (norm-name value)
        current (position owner)]
    (cond
      (str/blank? name*) {:success? false :error :invalid-name}
      (not (teleportation/named-position-available?)) {:success? false :error :service-unavailable}
      (nil? current) {:success? false :error :player-pos-unavailable}
      (teleportation/save-saved-location! owner name* (:world-id current)
                                          (:x current) (:y current) (:z current))
      {:success? true :name name*}
      :else {:success? false :error :save-failed})))

(defn delete-saved-location! [owner value]
  (let [name* (norm-name value)]
    (cond
      (str/blank? name*) {:success? false :error :invalid-name}
      (not (teleportation/named-position-available?)) {:success? false :error :service-unavailable}
      (teleportation/delete-saved-location! owner name*) {:success? true :name name*}
      :else {:success? false :error :delete-failed})))

(defn- perform! [owner value]
  (let [name* (norm-name value)]
    (if (str/blank? name*)
      {:success? false :error :invalid-name}
      (let [result (combat-runtime/dispatch-intent!
                    (str owner)
                    {:ability-id skill-id
                     :action :start
                     :context {:location-name name*}})
            result (if (= :accepted (:status result))
                     (combat-runtime/finalize-result! (str owner) result)
                     result)]
        {:success? (= :accepted (:status result))
         :error (when-not (= :accepted (:status result)) (:reason result))
         :result result}))))

(defn- response [op owner action]
  {:action (assoc action :op op)
   :snapshot (query-location-teleport owner)})

(def ^:private handler-contract {:owner-spec :server :payload-routing :none})

(defn init! []
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-QUERY
    (fn [_ player]
      (let [owner (uuid/player-uuid player)
            snapshot (query-location-teleport owner)]
        (response :query owner {:success? (:success? snapshot)
                                :error (:error snapshot)})))
    handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-ADD
    (fn [{:keys [name]} player]
      (let [owner (uuid/player-uuid player)]
        (response :add owner (save-current-location! owner name))))
    handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-REMOVE
    (fn [{:keys [name]} player]
      (let [owner (uuid/player-uuid player)]
        (response :remove owner (delete-saved-location! owner name))))
    handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SAVED-POS-PERFORM
    (fn [{:keys [name]} player]
      (let [owner (uuid/player-uuid player)]
        (response :perform owner (perform! owner name))))
    handler-contract)
  nil)
