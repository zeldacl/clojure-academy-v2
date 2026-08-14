(ns cn.li.ac.block.metal-former.gui-reactive
  "Metal Former container registration through Presentation Runtime."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.block.metal-former.recipes :as recipes]
            [cn.li.ac.block.metal-former.schema :as former-schema]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.energy.operations :as energy]))

(def ^:private former-slot-schema-id :metal-former)
(def ^:private former-gui-type :metal-former)
(def ^:private former-gui-schema
  (mapv (fn [field]
          (if (= (:key field) :mode)
            (assoc field :gui-init (fn [state] (recipes/normalize-mode (get state :mode (:default field))))
                         :gui-coerce recipes/normalize-mode)
            field)) former-schema/metal-former-schema))
(def ^:private former-sync (gui-sync/schema-sync-fns former-gui-schema))
(defn- msg [action] (msg-registry/msg former-gui-type action))
(defn- create-container [tile player]
  (assoc (gui-sync/create-schema-container former-gui-schema tile player former-gui-type
                                            {:gui-id (gui-manifest/gui-id :metal-former)})
         :presentation-close-fn (:on-close former-sync)))
(defn- get-slot-count [_] (slot-schema/tile-slot-count former-slot-schema-id))
(defn- get-slot-item [c i] (common/get-slot-item-be c i))
(defn- set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- slot-changed! [_ _] nil)
(defn- can-place-item? [_ i s]
  (case (int i) 0 (recipes/is-valid-input-item? s) 1 false 2 (energy/is-energy-item-supported? s) false))
(defn- still-valid? [_ _] true)
(def ^:private server-menu-sync! (:server-menu-sync! former-sync))
(def ^:private on-close (:on-close former-sync))

(defn- request-alternate! [container dir]
  (net-client/send-to-server (runtime-hooks/default-client-owner) (msg :alternate)
    (action-payload/action-payload container {:dir (int dir)})
    (fn [resp] (when-let [mode (:mode resp)] (reset! (:mode container) (recipes/normalize-mode mode))))))

(defn- handle-button-click! [container button-id _player]
  (when (#{-1 1} (int button-id)) (request-alternate! container button-id)))

(defn- quick-move-stack [c i s]
  (let [config (slot-schema/build-quick-move-config former-slot-schema-id
                 {:inventory-pred (fn [idx size] (>= idx size))
                  :rules [{:accept? energy/is-energy-item-supported? :slot-ids [:energy]}
                          {:accept? recipes/is-valid-input-item? :slot-ids [:input]}]})]
    (move-common/quick-move-with-rules c i s config)))

(defn create-screen [container menu player]
  (let [container* (assoc container
                          :presentation-buttons
                          [{:id :left :button-id -1 :x 12 :y 12 :width 24 :height 18 :label "<"}
                           {:id :right :button-id 1 :x 42 :y 12 :width 24 :height 18 :label ">"}]
                          :presentation-dispatch-action!
                          (fn [action payload]
                            (when (= action :container/button)
                              (handle-button-click! container (:button-id payload) player))))]
    (presentation-container/presentation-screen-data
      container* menu player former-slot-schema-id "academy:machine_container")))

(defn- former-container? [c]
  (and (map? c) (= (:container-type c) former-gui-type)
       (contains? c :tile-entity) (contains? c :mode) (contains? c :energy)))

(defn init-metal-former-reactive! []
  (install/framework-once! ::metal-former-reactive-installed?
    (fn []
      (slot-schema/register-slot-schema!
        {:schema-id former-slot-schema-id
         :slots [{:id :input :type :input :x 30 :y 20}
                 {:id :output :type :output :x 120 :y 52}
                 {:id :energy :type :energy :x 42 :y 81}]})
      (gui-reg/register-block-gui!
        (gui-manifest/gui-name :metal-former)
        (merge (gui-manifest/gui-registration :metal-former)
               {:container-predicate former-container? :container-fn create-container
                :screen-fn create-screen :server-menu-sync-fn server-menu-sync!
                :validate-fn still-valid? :close-fn on-close
                :button-click-fn handle-button-click! :slot-count-fn get-slot-count
                :slot-get-fn get-slot-item :slot-set-fn set-slot-item!
                :slot-can-place-fn can-place-item? :slot-changed-fn slot-changed!
                :quick-move-fn quick-move-stack}))
      (log/info "Metal Former GUI initialized (Presentation Runtime)"))))
