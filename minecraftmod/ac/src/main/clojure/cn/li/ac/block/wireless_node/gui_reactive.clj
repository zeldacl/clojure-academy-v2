(ns cn.li.ac.block.wireless-node.gui-reactive
  "Wireless Node container and Presentation Runtime bridge."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.block.wireless-node.node-info-reactive :as node-info]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def wireless-node-id :wireless-node)
(def ^:private gui-type :node)

(defn- ensure-slot-schema! [] (node-logic/ensure-node-slot-schema!))
(defn- resolve-state [tile]
  (if (map? tile)
    [nil tile]
    (try [tile (or (platform-be/get-custom-state tile) {})]
         (catch Exception e (log/warn "resolve-state:" (ex-message e)) [tile {}]))))

(defn create-container [tile player]
  (let [[be state] (resolve-state tile)
        base (gui-sync/create-schema-container node-schema/unified-node-schema
                (or be tile) player :node {:gui-id (gui-manifest/gui-id :wireless-node)})
        value-of (fn [key default]
                   (let [value (get base key default)]
                     (if (instance? clojure.lang.IDeref value) @value value)))
        ;; Bind atoms before assoc so text-change/submit closures do not capture
        ;; the pre-assoc container (where :presentation-form-state is nil).
        form-state (atom {:node-name (str (value-of :ssid ""))
                          :password (str (value-of :password ""))})]
    (assoc base
           ;; Main node GUI had no Save/Refresh chrome — name/password submit on text-input.
           :presentation-form-state form-state
           :presentation-text-fields [{:id :node-name :binding-key :node-name :x 12 :y 82 :width 120 :height 18
                                       :value-fn (fn [_ _] (value-of :ssid ""))}
                                      {:id :password :binding-key :network-password :x 12 :y 105 :width 120 :height 18
                                       :value-fn (fn [_ _] (value-of :password ""))}]
           :presentation-snapshot-fn
           (fn [_ _]
             (let [energy (double (or (value-of :energy 0.0) 0.0))
                   max-energy (max 1.0 (double (or (value-of :max-energy 1.0) 1.0)))
                   load (double (or (value-of :capacity 0.0) 0.0))
                   max-load (max 1.0 (double (or (value-of :max-capacity 1.0) 1.0)))
                   owner? (boolean (node-logic/owner-authorized? state player))
                   form @form-state]
               {:node-editable? owner?
                :node-readonly? (not owner?)
                ;; Form drafts always win while the atom holds the key — do not
                ;; fall back to tile ssid/password mid-edit (that reverts glyphs
                ;; when another field triggers a snapshot rebuild).
                :node-name (str (if (contains? form :node-name)
                                  (:node-name form)
                                  (value-of :ssid "")))
                :network-password (str (if (contains? form :password)
                                         (:password form)
                                         (value-of :password "")))
                :info-area (node-info/info-area-snapshot
                             {:initialized true
                              :energy energy
                              :max-energy max-energy
                              :capacity load
                              :owner (node-logic/owner-name state)
                              :range (or (value-of :range 0) 0)
                              :ssid (if (contains? form :node-name)
                                      (:node-name form)
                                      (value-of :ssid ""))
                              :password (if (contains? form :password)
                                          (:password form)
                                          (value-of :password ""))
                              :load load
                              :max-capacity max-load}
                             owner?)}))
           :presentation-text-change!
           (fn [field value]
             (swap! form-state assoc field value))
           :presentation-text-submit!
           (fn [field value]
             (when (node-logic/owner-authorized? state player)
               (case field
                 :node-name (node-info/send-change-name base value)
                 :password (node-info/send-change-password base value)
                 nil)))
           :presentation-dispatch-action!
           (fn [_action _payload] nil))))

(defn get-slot-count [_] (slot-schema/tile-slot-count wireless-node-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn can-place-item? [_ _ s] (energy-stub/is-energy-item-supported? s))
(defn still-valid? [_ _] true)
(defn- node-container? [c] (and (map? c) (= (:container-type c) gui-type)))
(def ^:private inventory-pred (fn [i s] (>= i s)))
(defn- quickly-move [c i stack]
  (move-common/quick-move-with-rules
    c i stack
    (slot-schema/build-quick-move-config wireless-node-id
      {:inventory-pred inventory-pred
       :rules [{:accept? energy-stub/is-energy-item-supported? :slot-ids [:input :output]}]})))

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player wireless-node-id "academy:wireless_node"))

(defn init-wireless-node-reactive! []
  (install/framework-once! ::node-reactive-installed?
    (fn []
      (ensure-slot-schema!)
      (gui-reg/register-block-gui!
        (gui-manifest/gui-name :wireless-node)
        (merge (gui-manifest/gui-registration :wireless-node)
               {:container-predicate node-container? :container-fn create-container
                :screen-fn create-screen :validate-fn still-valid?
                :close-fn (:on-close (gui-sync/schema-sync-fns node-schema/unified-node-schema))
                :slot-count-fn get-slot-count :slot-get-fn get-slot-item
                :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item?
                :slot-changed-fn (fn [_ _] nil) :quick-move-fn quickly-move}))
      (log/info "Wireless Node GUI initialized (Presentation Runtime)"))))
