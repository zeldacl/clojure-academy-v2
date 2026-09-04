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

(defn- coord-label [position]
  (if (map? position)
    (format "Here: %.0f, %.0f, %.0f"
            (double (or (:x position) 0.0))
            (double (or (:y position) 0.0))
            (double (or (:z position) 0.0)))
    "Position unavailable"))

(defn- decorate-location [location]
  (assoc location :teleport-label "Teleport" :delete-label "Delete"))

(defn- find-location [locations name]
  (when (seq name)
    (some #(when (= (str (:name %)) (str name)) %) locations)))

(defn- selection-fields [state]
  (let [locations (vec (or (:locations state) []))
        selected-name (str (or (:selected-name state) ""))
        selected (find-location locations selected-name)
        current (:current-pos state)]
    (if selected
      (let [dx (- (double (or (:x selected) 0.0)) (double (or (:x current) 0.0)))
            dy (- (double (or (:y selected) 0.0)) (double (or (:y current) 0.0)))
            dz (- (double (or (:z selected) 0.0)) (double (or (:z current) 0.0)))
            dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
        {:selected-label (str "Selected: " (:name selected))
         :distance-label (format "Distance: %.1f" dist)
         :dim-label (str "Dim: " (or (:world-id selected) (:dim-id selected) "?"))
         :warn-label (if (> dist 2048.0) "Far teleport — high cost" "")
         :teleport-label "Teleport"})
      {:selected-label "No location selected"
       :distance-label ""
       :dim-label ""
       :warn-label "Select a saved location"
       :teleport-label "Teleport"})))

(defn- project [state]
  (let [locations (vec (or (:locations state) []))
        max-offset (max 0 (- (count locations) page-size))
        offset (max 0 (min max-offset (int (or (:offset state) 0))))
        selected-name (str (or (:selected-name state) ""))
        selected-name (if (find-location locations selected-name)
                        selected-name
                        "")]
    (merge
     (assoc state
            :locations locations
            :selected-name selected-name
            :visible-locations (subvec locations offset
                                        (min (count locations) (+ offset page-size)))
            :offset offset
            :max-offset max-offset
            :coord-label (coord-label (:current-pos state))
            :current-label (current-label (:current-pos state)))
     (selection-fields (assoc state :locations locations :selected-name selected-name)))))

(defn- initial-state []
  (project
   {:title "Location Teleport"
    :status "Loading saved locations..."
    :exp 0.0
    :current-pos nil
    :locations []
    :input ""
    :selected-name ""
    :add-label "Save"
    :offset 0}))

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
            :current-pos (:current-pos snapshot)
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

(defn- teleport-name [state payload]
  (or (some-> (:item payload) :name str not-empty)
      (some-> (:selected-name state) str not-empty)))

(defn- action-effect [key player action payload]
  (case action
    :location/teleport
    (when-let [vm (active-vm key)]
      (when-let [name (teleport-name @(:state vm) payload)]
        (present-state! key #(assoc % :status (str "Teleporting to " name "...")))
        (client-api/req-location-teleport-perform!
         (owner-for player) name
         (fn [response]
           (let [result (:action response)]
             (if (:success? result)
               (do (swap! active* dissoc key) (bridge/close-screen!))
               (present-state! key #(assoc (snapshot-state % response)
                                           :status (str "Teleport failed: "
                                                        (or (:error result) "unknown"))))))))))

    :location/delete
    (when-let [name (some-> (:item payload) :name str not-empty)]
      (present-state! key #(assoc % :status (str "Deleting " name "...")))
      (client-api/req-location-teleport-remove!
       (owner-for player) name
       (fn [response]
         (present-state! key #(snapshot-state (dissoc % :selected-name) response)))))

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

    :location/select
    (project (assoc state
                    :selected-name (str (or (:name (:item payload)) ""))
                    :status (str "Selected " (or (:name (:item payload)) ""))))

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
