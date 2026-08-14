(ns cn.li.ac.content.ability.teleporter.location-teleport-reactive
  "Location Teleport Presentation application. Server snapshots remain
   authoritative; client state is selection, input and transient status."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def screen-id :ac/location-teleport)
(def ^:private default-state
  {:locations [] :exp 0.0 :current-pos nil :limits {}
   :selected 0 :input "" :status "Loading saved locations..."})
(def ^:private refresh-fns (atom {}))

(defn- owner-key-from-player [player]
  (read-model/owner-key
    (read-model/canonical-client-owner
      {:client-session-id (runtime-hooks/require-player-state-session-id "teleporter.ui")
       :player-uuid (uuid/player-uuid player)}
      :location-teleport)
    :location-teleport))

(defn- screen-st [owner-key]
  (managed-screens/screen-state screen-id owner-key default-state))

(defn- update-screen! [owner-key f & args]
  (apply managed-screens/update-screen-state! screen-id owner-key default-state f args))

(defn- normalize-snapshot [snapshot]
  {:locations (vec (or (:locations snapshot) []))
   :exp (double (or (:exp snapshot) 0.0))
   :current-pos (:current-pos snapshot)
   :limits (or (:limits snapshot) {})})

(defn apply-server-payload! [owner payload]
  (let [owner-key (read-model/owner-key owner :location-teleport)]
    (update-screen! owner-key #(merge % (normalize-snapshot payload)
                                      {:status "Saved locations updated"}))
    (when-let [refresh! (get @refresh-fns owner-key)] (refresh!))
    nil))

(defn- net-owner [player-uuid]
  (assoc (runtime-hooks/default-client-owner) :player-uuid (str player-uuid)))

(defn- location-label [idx location selected?]
  (let [name (or (:name location) "?")
        world (or (:world-id location) "?")
        coords (format "%.0f, %.0f, %.0f"
                       (double (or (:x location) 0.0))
                       (double (or (:y location) 0.0))
                       (double (or (:z location) 0.0)))
        affordable? (if (contains? location :can-perform?)
                      (:can-perform? location) true)]
    {:label (str (if selected? "> " "  ") name " — " world " (" coords ")"
                  (when-not affordable? " [insufficient CP]"))
     :index idx :name name}))

(defn- view-state [state]
  (let [locations (:locations state)
        selected (max 0 (min (int (or (:selected state) 0))
                             (max 0 (dec (count locations)))))]
    (assoc state
           :selected selected
           :lines (mapv (fn [[idx location]]
                          (location-label idx location (= idx selected)))
                        (map-indexed vector locations))
           :status (or (:status state)
                       (str (count locations) " saved location(s), EXP "
                            (long (double (or (:exp state) 0.0)))))
           :button-left {:label "Previous" :visible? (pos? (count locations))}
           :button-right {:label "Next" :visible? (pos? (count locations))}
           :input (str (or (:input state) "")))))

(defn- refresh! [owner-key]
  (when-let [f (get @refresh-fns owner-key)] (f)))

(defn- query! [owner-key player-uuid]
  (net-client/send-to-server (net-owner player-uuid)
    catalog/MSG-REQ-SAVED-POS-QUERY {}
    (fn [response]
      (let [snapshot (or (:snapshot response) response)]
        (when (and (map? snapshot) (:success? snapshot))
          (update-screen! owner-key #(merge % (normalize-snapshot snapshot)
                                            {:status "Saved locations loaded"}))
          (refresh! owner-key))))))

(defn- send-action! [owner-key player-uuid message payload]
  (net-client/send-to-server (net-owner player-uuid) message payload
    (fn [_] (query! owner-key player-uuid))))

(defn- dispatch-action! [owner-key player-uuid action current]
  (let [state (screen-st owner-key)
        locations (:locations state)
        selected (max 0 (min (int (or (:selected current) 0))
                             (max 0 (dec (count locations)))))
        selected-location (nth locations selected nil)
        input (str/trim (str (or (:input current) "")))]
    (case action
      :application/left
      {:selected (if (seq locations) (mod (dec selected) (count locations)) 0)
       :status "Selected previous location"}
      :application/right
      {:selected (if (seq locations) (mod (inc selected) (count locations)) 0)
       :status "Selected next location"}
      :application/input
      {:input (str (:input current "")) :status "Enter a name to save the current position"}
      :application/delete
      (if (and selected-location (seq (:name selected-location)))
        (do (send-action! owner-key player-uuid catalog/MSG-REQ-SAVED-POS-REMOVE
                          {:name (:name selected-location)})
            {:status (str "Removing " (:name selected-location) "...")})
        {:status "No saved location selected"})
      :application/activate
      (cond
        (seq input)
        (let [max-length (int (or (:max-location-name-length (:limits state)) 16))]
          (if (<= (count input) max-length)
            (do (send-action! owner-key player-uuid catalog/MSG-REQ-SAVED-POS-ADD {:name input})
                {:input "" :status (str "Saving position as " input "...")})
            {:status (str "Name is limited to " max-length " characters")}))
        selected-location
        (do (send-action! owner-key player-uuid catalog/MSG-REQ-SAVED-POS-PERFORM
                          {:name (:name selected-location)})
            {:status (str "Teleporting to " (:name selected-location) "...")})
        :else {:status "No saved location selected"})
      {})))

(defn open-screen! [player payload]
  (let [player-uuid (uuid/player-uuid player)
        owner-key (owner-key-from-player player)]
    (when (map? payload)
      (update-screen! owner-key #(merge % (normalize-snapshot payload))))
    (let [refresh-fn* (atom nil)
          vm (application/mount!
               (str "application/location-teleport/" player-uuid)
               "Location Teleport"
               (view-state (screen-st owner-key))
               (fn [action current]
                 (let [result (dispatch-action! owner-key player-uuid action current)]
                   (update-screen! owner-key merge result)
                   (when-let [refresh! @refresh-fn*]
                     (refresh! (view-state (screen-st owner-key))))
                   result))
               #(do (swap! refresh-fns dissoc owner-key)
                    (managed-screens/clear-screen-state! screen-id owner-key)))]
      (swap! refresh-fns assoc owner-key
             (fn []
               (when-let [refresh! @refresh-fn*]
                 (refresh! (view-state (screen-st owner-key))))))
      (reset! refresh-fn* (:refresh! vm))
      (query! owner-key player-uuid)
      vm)))

(defn close-screen! [owner]
  (let [owner-key (read-model/owner-key owner :location-teleport)]
    (swap! refresh-fns dissoc owner-key)
    (managed-screens/clear-screen-state! screen-id owner-key)))

(defn open! [player] (open-screen! player nil))

(defn init!
  "Compatibility lifecycle hook; registration is no longer widget-based."
  [] nil)
