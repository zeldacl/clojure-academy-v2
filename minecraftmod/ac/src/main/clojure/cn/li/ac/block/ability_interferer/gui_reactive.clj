(ns cn.li.ac.block.ability-interferer.gui-reactive
  "Reactive GUI registration for the Ability Interferer."
  (:refer-clojure :exclude [sync])
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client] [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.block.gui.sync :as gui-sync] [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]))

(def ^:private slot-schema-id :ability-interferer) (def ^:private gui-type :ability-interferer)
(def ^:private sync (gui-sync/schema-sync-fns interferer-schema/ability-interferer-schema))
(defn- msg [action] (msg-registry/msg gui-type action))
(defn create-container [tile player] (assoc (gui-sync/create-schema-container interferer-schema/ability-interferer-schema tile player gui-type {:gui-id (gui-manifest/gui-id :ability-interferer)}) :presentation-close-fn (:on-close sync)))
(defn get-slot-count [_] (slot-schema/tile-slot-count slot-schema-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn can-place-item? [_ _ s] (energy/is-energy-item-supported? s))
(defn still-valid? [_ _] true) (def server-menu-sync! (:server-menu-sync! sync))
(def on-close (:on-close sync)) (defn handle-button-click! [_ _ _] nil)

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player slot-schema-id "academy:machine_container"))

(defn- container? [c] (and (map? c) (= (:container-type c) gui-type) (contains? c :tile-entity) (contains? c :energy)))
(defn init-ability-interferer-reactive! []
  (install/framework-once! ::interferer-reactive-installed?
  (fn []
    (slot-schema/register-slot-schema! {:schema-id slot-schema-id :slots [{:id :energy :type :energy :x 80 :y 35}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :ability-interferer) (merge (gui-manifest/gui-registration :ability-interferer) {:container-predicate container? :container-fn create-container :screen-fn create-screen :server-menu-sync-fn server-menu-sync! :validate-fn still-valid? :close-fn on-close :button-click-fn handle-button-click! :slot-count-fn get-slot-count :slot-get-fn get-slot-item :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item? :slot-changed-fn (fn [_ _] nil)}))
    (log/info "Ability Interferer GUI initialized (reactive)"))))
