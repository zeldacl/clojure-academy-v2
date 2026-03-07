(ns my-mod.gui.definitions
  "Central GUI declarations.

  This namespace is intended to be required during core initialization so that
  GUI metadata/hook registries are populated before platform registration."
  (:require [my-mod.gui.dsl :as gui]
            [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
            [my-mod.wireless.gui.solar-container :as solar-container]
            [my-mod.wireless.gui.node-gui :as node-gui]
            [my-mod.wireless.gui.matrix-gui :as matrix-gui]
            [my-mod.wireless.gui.solar-gui-xml :as solar-gui]
            [my-mod.wireless.gui.node-sync :as node-sync]
            [my-mod.wireless.gui.matrix-sync :as matrix-sync]))

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

;; Slot layout definitions (used by quick-move strategies and slot manager)
(def ^:private node-slot-layout
  {:slots [{:type :energy :index 0 :x 0 :y 0}
           {:type :output :index 1 :x 26 :y 0}]
   :ranges {:tile [0 1]
            :player-main [2 28]
            :player-hotbar [29 37]}})

(def ^:private matrix-slot-layout
  {:slots [{:type :plate :index 0 :x 0 :y 0}
           {:type :plate :index 1 :x 34 :y 0}
           {:type :plate :index 2 :x 68 :y 0}
           {:type :core :index 3 :x 47 :y 24}]
   :ranges {:tile [0 3]
            :player-main [4 30]
            :player-hotbar [31 39]}})

(def ^:private solar-slot-layout
  {:slots [{:type :energy :index 0 :x 42 :y 81}]
   :ranges {:tile [0 0]
            :player-main [1 27]
            :player-hotbar [28 36]}})

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

