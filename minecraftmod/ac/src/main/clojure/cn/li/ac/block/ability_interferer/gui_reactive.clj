(ns cn.li.ac.block.ability-interferer.gui-reactive
  "Complete reactive replacement for ability_interferer/gui.clj."
  (:refer-clojure :exclude [sync])
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client] [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest] [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt] [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.block.gui.sync :as gui-sync] [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]))

(def ^:private slot-schema-id :ability-interferer) (def ^:private gui-type :ability-interferer)
(def ^:private sync (gui-sync/schema-sync-fns interferer-schema/ability-interferer-schema))
(defn- msg [action] (msg-registry/msg gui-type action))
(defn create-container [tile player] (gui-sync/create-schema-container interferer-schema/ability-interferer-schema tile player gui-type {:gui-id (gui-manifest/gui-id :ability-interferer)}))
(defn get-slot-count [_] (slot-schema/tile-slot-count slot-schema-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn can-place-item? [_ _ s] (energy/is-energy-item-supported? s))
(defn still-valid? [_ _] true) (def server-menu-sync! (:server-menu-sync! sync))
(def on-close (:on-close sync)) (defn handle-button-click! [_ _ _] nil)

(defn- attach-binds! [r container _menu _player _signals]
  (rt/put-user-signal! r :range-display
    (sig/computed-o [(rt/clock-ms-sig r)] (fn [_] (str (or @(:range container) "...")))))
  (rt/put-user-signal! r :active-display
    (sig/computed-o [(rt/clock-ms-sig r)] (fn [_] (if @(:active? container) "ON" "OFF")))))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)]
    (bgui/create-screen
      {:page-xml "guis/rework/new/page_interfere.xml" :texture-name "interferer"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double @(:energy container))) (fn [] (max 1.0 (double @(:max-energy container)))))]
       :properties {:range (fn [] (str (or (safe-val (:range container)) "...")))
                    :active (fn [] (if (safe-val (:active? container)) "ON" "OFF"))}
       :wireless? false :custom-bind! attach-binds!})))

(def update! bgui/update-signals!) (def open! bgui/open!)
(defn- container? [c] (and (map? c) (= (:container-type c) gui-type) (contains? c :tile-entity) (contains? c :energy)))
(defonce-guard interferer-reactive-installed?)
(defn init-ability-interferer-reactive! []
  (with-init-guard interferer-reactive-installed?
    (slot-schema/register-slot-schema! {:schema-id slot-schema-id :slots [{:id :energy :type :energy :x 80 :y 35}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :ability-interferer) (merge (gui-manifest/gui-registration :ability-interferer) {:container-predicate container? :container-fn create-container :screen-fn create-screen :server-menu-sync-fn server-menu-sync! :validate-fn still-valid? :close-fn on-close :button-click-fn handle-button-click! :slot-count-fn get-slot-count :slot-get-fn get-slot-item :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item? :slot-changed-fn (fn [_ _] nil)}))
    (log/info "Ability Interferer GUI initialized (reactive)")))
