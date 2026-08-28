(ns cn.li.ac.content.ability.teleporter.location-teleport-presentation
  "Presentation Runtime controller for the location teleport ability.

   AC owns the ability semantics and RPC calls; the shared presentation
   runtime owns retained state, layout, painting, and input routing."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.owner :as owner]))

(def ^:private page-size 8)
(def ^:private view-id :academy.app/location-teleport)
(defonce ^:private active* (atom {}))

(defn- owner-for [player]
  (owner/require-client-owner
   (assoc (runtime-hooks/default-client-owner)
          :player-uuid (uuid/player-uuid player))))

(defn- current-label [position]
  (if (map? position)
    (format "Current: %s (#%s) (%.0f, %.0f, %.0f)"
            (or (:world-id position) "?")
            (or (:dim-id position) 0)
            (double (or (:x position) 0.0))
            (double (or (:y position) 0.0))
            (double (or (:z position) 0.0)))
    "Current position unavailable"))

(defn- decorate-location [location]
  (assoc location :teleport-label "Teleport" :delete-label "Delete"))

(defn- project [state]
  (let [locations (vec (or (:locations state) []))
        max-offset (max 0 (- (count locations) page-size))
        offset (max 0 (min max-offset (int (or (:offset state) 0))))]
    (assoc state
           :locations locations
           :visible-locations (subvec locations offset
                                       (min (count locations) (+ offset page-size)))
           :offset offset
           :max-offset max-offset)))

(defn- initial-state []
  {:title "Location Teleport"
   :status "Loading saved locations..."
   :exp 0.0
   :current-label "Current position unavailable"
   :locations []
   :visible-locations []
   :input ""
   :selected-label ""
   :add-label "Save current location"
   :offset 0
   :max-offset 0})

(defn- snapshot-state [state response]
  (let [snapshot (or (:snapshot response) response)
        locations (mapv decorate-location (or (:locations snapshot) []))]
    (project
     (assoc state
            :status (if (:success? snapshot)
                      (format "%d saved locations | EXP %.1f"
                              (count locations) (double (or (:exp snapshot) 0.0)))
                      (str "Unable to load locations: " (or (:error snapshot) "unknown")))
            :exp (double (or (:exp snapshot) 0.0))
            :current-label (current-label (:current-pos snapshot))
            :locations locations))))

(defn- active-vm [key]
  (get @active* key))

(defn- present-state! [key f]
  (when-let [vm (active-vm key)]
    (presentation/present! vm (f @(:state vm)))))

(defn- query! [key player]
  (when-let [vm (active-vm key)]
    (client-api/req-location-teleport-query!
     (owner-for player)
     (fn [response]
       (present-state! key #(snapshot-state % response))))))

(defn- action-effect [key player action payload]
  (case action
    :location/teleport
    (when-let [name (some-> (:item payload) :name str not-empty)]
      (present-state! key #(assoc % :status (str "Teleporting to " name "...")))
      (client-api/req-location-teleport-perform!
       (owner-for player) name
       (fn [response]
         (let [result (:action response)]
           (if (:success? result)
             (do (swap! active* dissoc key) (bridge/close-screen!))
             (present-state! key #(assoc (snapshot-state % response)
                                         :status (str "Teleport failed: "
                                                      (or (:error result) "unknown")))))))))

    :location/delete
    (when-let [name (some-> (:item payload) :name str not-empty)]
      (present-state! key #(assoc % :status (str "Deleting " name "...")))
      (client-api/req-location-teleport-remove!
       (owner-for player) name
       (fn [response]
         (present-state! key #(snapshot-state % response)))))

    :location/add
    (let [name (some-> (or (:value payload) (:input @(:state (active-vm key)))) str str/trim)]
      (when (seq name)
        (present-state! key #(assoc % :input "" :status (str "Saving " name "...")))
        (client-api/req-location-teleport-add!
         (owner-for player) name
         (fn [response]
           (present-state! key #(snapshot-state % response))))))

    nil))

(defn- reduce-state [state action payload]
  (case action
    :input/scroll
    (project (update state :offset + (if (neg? (double (or (:delta payload) 0.0))) 1 -1)))
    state))

(defn open! [player]
  (when player
    (let [key (str (uuid/player-uuid player))]
      (when-let [old (active-vm key)]
        (presentation/unmount! old)
        (swap! active* dissoc key))
      (let [vm* (atom nil)
            vm (presentation/mount-view!
                {:view-id view-id
                 :host-kind :screen
                 :state (initial-state)
                 :reduce (fn [state action payload]
                           (let [next-state (reduce-state state action payload)]
                             {:state next-state
                              :effects (when (#{:location/teleport :location/delete :location/add}
                                                action)
                                         [{:action action :payload payload}])
                              :event-result :consume}))
                 :run-effect! (fn [{:keys [action payload]}]
                                (action-effect key player action payload))
                 :on-close (fn [] (swap! active* dissoc key))})]
        (reset! vm* vm)
        (swap! active* assoc key vm)
        (bridge/call-adapter :presentation-open-screen!
                             (:mount vm) "Location Teleport"
                             #(swap! active* dissoc key))
        (query! key player)
        vm))))

(defn close! [player-or-uuid]
  (let [key (str (or (uuid/player-uuid player-or-uuid) player-or-uuid))]
    (when-let [vm (active-vm key)]
      (presentation/unmount! vm)
      (swap! active* dissoc key)))
  nil)

(defn close-all! []
  (doseq [[key vm] @active*]
    (presentation/unmount! vm)
    (swap! active* dissoc key))
  nil)