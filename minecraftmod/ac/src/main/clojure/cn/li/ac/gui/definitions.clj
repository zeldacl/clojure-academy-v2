(ns cn.li.ac.gui.definitions
  "Central GUI declarations.

  This namespace is intended to be required during core initialization so that
  GUI metadata/hook registries are populated before platform registration."
  (:require [cn.li.mcmod.gui.dsl :as gui]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.slot-schema :as slots]))

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
  :container-fn (fn [tile player]
                  (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/create-container)]
                    (f tile player)))
  :screen-fn (fn [container minecraft-container player]
               (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/create-screen)]
                 (f container minecraft-container player)))
  :tick-fn (fn [container]
             (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/tick!)]
               (f container)))
  :sync-get (fn [container]
              (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/get-sync-data)]
                (f container)))
  :sync-apply (fn [container data]
                (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/apply-sync-data!)]
                  (f container data)))
  :payload-sync-apply-fn (fn [payload]
                           (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/apply-node-sync-payload!)]
                             (f payload))))

(gui/defgui wireless-matrix
  :gui-id 1
  :display-name "Wireless Matrix"
  :gui-type :matrix
  :registry-name "wireless_matrix_gui"
  :screen-factory-fn-kw :create-matrix-screen
  :slot-layout matrix-slot-layout
  :container-predicate matrix-container?
  :container-fn (fn [tile player]
                  (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/create-container)]
                    (f tile player)))
  :screen-fn (fn [container minecraft-container player]
               (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/create-screen)]
                 (f container minecraft-container player)))
  :tick-fn (fn [container]
             (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/tick!)]
               (f container)))
  :sync-get (fn [container]
              (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/get-sync-data)]
                (f container)))
  :sync-apply (fn [container data]
                (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/apply-sync-data!)]
                  (f container data)))
  :payload-sync-apply-fn (fn [payload]
                           (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/apply-matrix-sync-payload!)]
                             (f payload))))

(gui/defgui solar-gen
  :gui-id 2
  :display-name "Solar Generator"
  :gui-type :solar
  :registry-name "solar_gen_gui"
  :screen-factory-fn-kw :create-solar-screen
  :slot-layout solar-slot-layout
  :container-predicate solar-container?
  :container-fn (fn [tile player]
                  (when-let [f (requiring-resolve 'cn.li.ac.block.solar-gen.gui/create-container)]
                    (f tile player)))
  :screen-fn (fn [container minecraft-container player]
               (when-let [f (requiring-resolve 'cn.li.ac.block.solar-gen.gui/create-screen)]
                 (f container minecraft-container player)))
  :tick-fn (fn [container]
             (when-let [f (requiring-resolve 'cn.li.ac.block.solar-gen.gui/tick!)]
               (f container)))
  :sync-get (fn [container]
              (when-let [f (requiring-resolve 'cn.li.ac.block.solar-gen.gui/get-sync-data)]
                (f container)))
  :sync-apply (fn [container data]
                (when-let [f (requiring-resolve 'cn.li.ac.block.solar-gen.gui/apply-sync-data!)]
                  (f container data))))

