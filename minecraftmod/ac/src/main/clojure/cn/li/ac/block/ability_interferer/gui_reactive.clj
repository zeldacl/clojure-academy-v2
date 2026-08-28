(ns cn.li.ac.block.ability-interferer.gui-reactive
  "Reactive GUI registration for the Ability Interferer through Presentation Runtime."
  (:refer-clojure :exclude [sync])
  (:require [clojure.string :as str]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.item.item-energy-base :as energy-base]
            [cn.li.ac.block.ability-interferer.config :as interferer-config]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]))

(def ^:private slot-schema-id :ability-interferer)
(def ^:private gui-type :ability-interferer)
(def ^:private sync (gui-sync/schema-sync-fns interferer-schema/ability-interferer-schema))
(defn- msg [action] (msg-registry/msg gui-type action))
(defn- value-of [value] (if (instance? clojure.lang.IDeref value) @value value))
(defn- normalize-whitelist [names]
  (->> (or names []) (map #(str/trim (str %))) (remove str/blank?) distinct sort vec))
(defn- current-whitelist [container] (normalize-whitelist (value-of (:whitelist container))))
(defn- send-interferer! [container action payload]
  (net-client/send-to-server (or (:owner container) (runtime-hooks/default-client-owner))
    (msg action) (action-payload/action-payload container payload) nil))

(defn- snapshot-interferer [container _player]
  (let [range (double (or (value-of (:range container)) (interferer-config/default-range)))
        enabled? (boolean (value-of (:enabled container)))
        state (or (:presentation-interferer-state container) (atom {:input "" :selected nil}))
        selected (:selected @state)]
    {:machine-state "Ability Interferer"
     :interferer-range (format "Range: %.0f" range)
     :interferer-enabled (if enabled? "Enabled" "Disabled")
     :interferer-range-down {:label "-10"}
     :interferer-range-up {:label "+10"}
     :interferer-toggle {:label (if enabled? "Disable" "Enable")}
     :interferer-whitelist (mapv (fn [name]
                                   {:label name :player-name name
                                    :select-label (if (= selected name) "Selected" "Select")})
                                 (current-whitelist container))
     :interferer-input (str (or (:input @state) ""))
     :interferer-selected (if (str/blank? (str selected)) "No player selected" (str "Selected: " selected))
     :interferer-remove {:label "Remove"}}))

(defn- dispatch-interferer! [container action payload]
  (let [state (or (:presentation-interferer-state container) (atom {:input "" :selected nil}))]
    (case action
      :interferer/input-change
      (swap! state assoc :input (str (or (:value payload) "")))

      :interferer/select-whitelist
      (swap! state assoc :selected (or (:player-name (:item payload)) (:item payload)))

      :interferer/range-down
      (let [target (interferer-config/clamp-range
                     (- (double (or (value-of (:range container)) (interferer-config/default-range))) 10.0))]
        (send-interferer! container :change-range {:range target})
        (reset! (:range container) target))

      :interferer/range-up
      (let [target (interferer-config/clamp-range
                     (+ (double (or (value-of (:range container)) (interferer-config/default-range))) 10.0))]
        (send-interferer! container :change-range {:range target})
        (reset! (:range container) target))

      :interferer/toggle
      (let [target (not (boolean (value-of (:enabled container))))]
        (send-interferer! container :toggle-enabled {:enabled target})
        (reset! (:enabled container) target))

      :interferer/add-whitelist
      (let [name (str/trim (str (or (:value payload) (:input @state) "")))]
        (when-not (str/blank? name)
          (send-interferer! container :add-to-whitelist {:player-name name})
          (reset! (:whitelist container) (normalize-whitelist (conj (current-whitelist container) name)))
          (swap! state assoc :input "")))

      :interferer/remove-whitelist
      (when-let [name (:selected @state)]
        (send-interferer! container :remove-from-whitelist {:player-name name})
        (reset! (:whitelist container) (vec (remove #(= % name) (current-whitelist container))))
        (swap! state assoc :selected nil))
      nil)))

(defn create-container [tile player]
  (assoc (gui-sync/create-schema-container interferer-schema/ability-interferer-schema tile player gui-type
                                           {:gui-id (gui-manifest/gui-id :ability-interferer)})
         :presentation-close-fn (:on-close sync)
         :presentation-wireless {:domain :ability-interferer :role :ability-interferer}
         :presentation-wireless-state (atom {:linked nil :avail [] :password ""})
         :presentation-interferer-state (atom {:input "" :selected nil})
         :presentation-snapshot-fn snapshot-interferer
         :presentation-dispatch-action! dispatch-interferer!))

(defn get-slot-count [_] (slot-schema/tile-slot-count slot-schema-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- energy-unit-stack? [s]
  (and s (= :energy-unit (energy-base/get-energy-item-type s))))
(defn can-place-item? [_ _ s] (energy-unit-stack? s))
(defn still-valid? [_ _] true)
(def server-menu-sync! (:server-menu-sync! sync))
(def on-close (:on-close sync))
(defn handle-button-click! [_ _ _] nil)
(def ^:private quick-move-config
  (delay (slot-schema/build-quick-move-config slot-schema-id
           {:inventory-pred (fn [i s] (>= i s))
            :rules [{:accept? energy-unit-stack? :slot-ids [:energy]}]})))
(defn- quick-move-stack [c i s]
  (move-common/quick-move-with-rules c i s @quick-move-config))

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player slot-schema-id "academy:ability_interferer"))

(defn- container? [c]
  (and (map? c) (= (:container-type c) gui-type)
       (contains? c :tile-entity) (contains? c :energy)))

(defn init-ability-interferer-reactive! []
  (install/framework-once! ::interferer-reactive-installed?
    (fn []
      (slot-schema/register-slot-schema!
        {:schema-id slot-schema-id
         :slots [{:id :energy :type :energy :x 80 :y 35}]})
      (gui-reg/register-block-gui!
        (gui-manifest/gui-name :ability-interferer)
        (merge (gui-manifest/gui-registration :ability-interferer)
               {:container-predicate container? :container-fn create-container
                :screen-fn create-screen :server-menu-sync-fn server-menu-sync!
                :validate-fn still-valid? :close-fn on-close
                :button-click-fn handle-button-click! :slot-count-fn get-slot-count
                :slot-get-fn get-slot-item :slot-set-fn set-slot-item!
                :slot-can-place-fn can-place-item? :slot-changed-fn (fn [_ _] nil)
                :quick-move-fn quick-move-stack}))
      (log/info "Ability Interferer GUI initialized (Presentation Runtime)"))))