(ns cn.li.ac.gui.definitions
  "Central GUI declarations.

  This namespace is intended to be required during core initialization so that
  GUI metadata/hook registries are populated before platform registration."
  (:require [cn.li.mcmod.gui.dsl :as gui]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.slot-schema :as slots]
            [cn.li.ac.wireless.gui.node-container :as node-container]
            [cn.li.ac.wireless.gui.matrix-container :as matrix-container]
            [cn.li.ac.wireless.gui.solar-container :as solar-container]
            [cn.li.ac.wireless.gui.node-gui :as node-gui]
            [cn.li.ac.wireless.gui.matrix-gui :as matrix-gui]
            [cn.li.ac.wireless.gui.solar-gui-xml :as solar-gui]
            [cn.li.ac.wireless.gui.node-sync :as node-sync]
            [cn.li.ac.wireless.gui.matrix-sync :as matrix-sync]))

(defn- node-container?
  [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :ssid)
       (contains? container :password)))

(defn- matrix-container?
  [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :plate-count)
       (contains? container :core-level)))

(defn- solar-container?
  [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :status)))

;; Slot layout is derived from the centralized slot schemas.
(def ^:private node-slot-layout
  (slot-schema/get-slot-layout slots/wireless-node-id))

(def ^:private matrix-slot-layout
  (slot-schema/get-slot-layout slots/wireless-matrix-id))

(def ^:private solar-slot-layout
  (slot-schema/get-slot-layout slots/solar-gen-id))

(gui/defgui wireless-node
  :gui-id 0
  :display-name "Wireless Node"
  :gui-type :node
  :registry-name "wireless_node_gui"
  :screen-factory-fn-kw :create-node-screen
  :slot-layout node-slot-layout
  :container-predicate node-container?
  :container-fn node-container/create-container
  :screen-fn (fn [container minecraft-container player]
               (node-gui/create-screen container minecraft-container player))
  :tick-fn node-container/tick!
  :sync-get node-container/get-sync-data
  :sync-apply node-container/apply-sync-data!
  :payload-sync-apply-fn node-sync/apply-node-sync-payload!)

(gui/defgui wireless-matrix
  :gui-id 1
  :display-name "Wireless Matrix"
  :gui-type :matrix
  :registry-name "wireless_matrix_gui"
  :screen-factory-fn-kw :create-matrix-screen
  :slot-layout matrix-slot-layout
  :container-predicate matrix-container?
  :container-fn matrix-container/create-container
  :screen-fn (fn [container minecraft-container player]
               (matrix-gui/create-screen container minecraft-container player))
  :tick-fn matrix-container/tick!
  :sync-get matrix-container/get-sync-data
  :sync-apply matrix-container/apply-sync-data!
  :payload-sync-apply-fn matrix-sync/apply-matrix-sync-payload!)

(gui/defgui solar-gen
  :gui-id 2
  :display-name "Solar Generator"
  :gui-type :solar
  :registry-name "solar_gen_gui"
  :screen-factory-fn-kw :create-solar-screen
  :slot-layout solar-slot-layout
  :container-predicate solar-container?
  :container-fn solar-container/create-container
  :screen-fn (fn [container minecraft-container player]
               (solar-gui/create-screen container minecraft-container player))
  :tick-fn solar-container/tick!
  :sync-get solar-container/get-sync-data
  :sync-apply solar-container/apply-sync-data!)

