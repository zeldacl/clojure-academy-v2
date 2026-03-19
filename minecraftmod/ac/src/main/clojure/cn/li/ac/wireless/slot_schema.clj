(ns cn.li.ac.wireless.slot-schema
  "Authoritative slot schemas for wireless GUIs.

  GUI definitions, containers, and block logic should all query these schemas
  instead of using hardcoded slot indexes.

  Also declares the quick-move routing configs so containers only need to
  reference a pre-built config rather than repeat the acceptance predicates."
  (:require [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.energy.operations :as energy-ops]))

(def wireless-node-id :wireless-node)
(def wireless-matrix-id :wireless-matrix)
(def solar-gen-id :solar-gen)

(def wireless-node
  (slot-schema/register-slot-schema!
    {:schema-id wireless-node-id
     :slots [{:id :input :type :energy :x 42 :y 10}
             {:id :output :type :output :x 42 :y 80}]}))

(def wireless-matrix
  (slot-schema/register-slot-schema!
    {:schema-id wireless-matrix-id
     :slots [{:id :plate-a :type :plate :x 78 :y 11}
             {:id :plate-b :type :plate :x 53 :y 60}
             {:id :plate-c :type :plate :x 104 :y 60}
             {:id :core :type :core :x 78 :y 36}]}))

(def solar-gen
  (slot-schema/register-slot-schema!
    {:schema-id solar-gen-id
     :slots [{:id :energy :type :energy :x 42 :y 81}]}))

;; ============================================================================
;; Quick-Move Routing Configs
;;
;; Compiled once at load time from the slot schemas above.  Containers
;; reference these vars instead of repeating acceptance predicates.
;; ============================================================================

(def ^:private inventory-pred
  (fn [slot-index player-inventory-start]
    (>= slot-index player-inventory-start)))

(def wireless-node-quick-move-config
  (slot-schema/build-quick-move-config
    wireless-node-id
    {:inventory-pred inventory-pred
     :rules [{:accept? energy-ops/is-energy-item-supported?
              :slot-ids [:input]}]}))

(def wireless-matrix-quick-move-config
  (slot-schema/build-quick-move-config
    wireless-matrix-id
    {:inventory-pred inventory-pred
     :rules [{:accept? core/is-mat-core?
              :slot-ids [:core]}
             {:accept? plate/is-constraint-plate?
              :slot-type :plate}]}))
