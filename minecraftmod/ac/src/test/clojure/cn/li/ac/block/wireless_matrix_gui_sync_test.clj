(ns cn.li.ac.block.wireless-matrix-gui-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-matrix.gui :as gui]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]))

(defn- container-fixture []
  {:plate-count (atom 0)
   :core-level (atom 0)
   :is-working (atom false)
   :bandwidth (atom -1.0)
   :range (atom -1.0)
   :capacity (atom -1)
   :max-capacity (atom -1)
   :sync-ticker (atom 0)
   :tile-entity :tile
   :inventory {0 :plate-a 1 :plate-b 2 :plate-c 3 :core}})

(defn- with-tile-state-stub [f]
  (with-redefs [common/get-tile-state (fn [_]
                                        {:plate-count 0
                                         :core-level 0
                                         :is-working false
                                         :bandwidth -1.0
                                         :range -1.0
                                         :capacity -1
                                         :max-capacity -1})]
    (f)))

(deftest sync-to-client-working-state-follows-business-rule-test
  (with-tile-state-stub
    (fn []
      (testing "core exists but plate incomplete => not working"
        (let [container (assoc (container-fixture) :inventory {0 :plate-a 1 :plate-b 2 nil 3 :core})]
          (with-redefs [slot-schema/slot-indexes-by-type (fn [_ _] [0 1 2])
                        slot-schema/slot-index (fn [_ _] 3)
                        gui/get-slot-item (fn [c i] (get (:inventory c) i))
                        pitem/item-is-empty? nil?
                        core/is-mat-core? (fn [item] (= item :core))
                        core/get-core-level (fn [_] 1)
                        sync-helpers/with-throttled-sync! (fn [_ _ f] (f))
                        matrix-logic/matrix-stats-for-counts (fn [_ _] {:capacity 0 :bandwidth 0.0 :range 0.0})
                        sync-helpers/query-matrix-network-capacity! (fn [_ _] nil)]
            (gui/tick! container)
            (is (false? @(:is-working container))))))

      (testing "core exists and plate complete => working"
        (let [required (matrix-logic/required-plate-count)
              container (assoc (container-fixture)
                               :inventory (assoc (zipmap (range required) (repeat :plate)) required :core))]
          (with-redefs [slot-schema/slot-indexes-by-type (fn [_ _] (range required))
                        slot-schema/slot-index (fn [_ _] required)
                        gui/get-slot-item (fn [c i] (get (:inventory c) i))
                        pitem/item-is-empty? nil?
                        core/is-mat-core? (fn [item] (= item :core))
                        core/get-core-level (fn [_] 1)
                        sync-helpers/with-throttled-sync! (fn [_ _ f] (f))
                        matrix-logic/matrix-stats-for-counts (fn [_ _] {:capacity 8 :bandwidth 60.0 :range 24.0})
                        sync-helpers/query-matrix-network-capacity! (fn [_ _] nil)]
            (gui/tick! container)
            (is (true? @(:is-working container)))))))))

(deftest sync-to-client-updates-derived-stats-when-core-or-plate-change-test
  (with-tile-state-stub
    (fn []
      (let [container (container-fixture)
            queried (atom nil)]
        (with-redefs [slot-schema/slot-indexes-by-type (fn [_ _] [0 1 2])
                      slot-schema/slot-index (fn [_ _] 3)
                      gui/get-slot-item (fn [c i] (get (:inventory c) i))
                      pitem/item-is-empty? nil?
                      core/is-mat-core? (fn [item] (= item :core))
                      core/get-core-level (fn [_] 2)
                      sync-helpers/with-throttled-sync! (fn [_ _ f] (f))
                      matrix-logic/matrix-stats-for-counts (fn [core plate]
                                                             {:capacity (* 10 core)
                                                              :bandwidth (* 20.0 core)
                                                              :range (+ 30.0 plate)})
                      sync-helpers/query-matrix-network-capacity! (fn [_ stats] (reset! queried stats))]
          (gui/tick! container)
          (is (= 40.0 @(:bandwidth container)))
          (is (= 33.0 @(:range container)))
          (is (= {:capacity 20 :bandwidth 40.0 :range 33.0}
                 @queried)))))))
