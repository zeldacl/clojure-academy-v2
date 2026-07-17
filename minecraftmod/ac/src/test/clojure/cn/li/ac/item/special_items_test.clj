(ns cn.li.ac.item.special-items-test
  (:require
            [cn.li.ac.ability.service.runtime-store :as store]
            [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.platform.entity :as entity]))

(defn- with-event-runtime
  [f]
  (ps-fix/with-test-player-state-owner
   (fn []
     (evt/install-event-subscriber-runtime!
      (evt/create-event-subscriber-runtime))
     (evt/reset-ability-event-subscribers-for-test!)
     (try
       (f)
       (finally
         (evt/install-event-subscriber-runtime!
          (evt/create-event-subscriber-runtime))
         (evt/reset-ability-event-subscribers-for-test!))))))

(use-fixtures :each ps-fix/clean-player-states-fixture)
(use-fixtures :each with-event-runtime)

(defn- seed-player!
  [player-uuid ability-data]
  (store/set-player-state!
   ps-fix/test-session-id
   player-uuid
   (assoc (store/fresh-player-state) :ability-data ability-data)))

(deftest induction-factor-right-click-does-not-mutate-ability-state-test
  ;; Induction factors are consumed only via the developer timed session —
  ;; right-clicking one is a no-op that must not touch ability state or items.
  (let [consumed* (atom 0)
        player :stub-player
        player-uuid "p1"]
    (seed-player! player-uuid (adata/new-ability-data))
    (with-redefs [uuid/player-uuid (constantly player-uuid)
                  entity/player-consume-main-hand-item!
                  (fn [_ amount] (swap! consumed* + amount) true)]
      (is (= {:consume? false}
             (#'special-items/apply-induction-factor!
              {:player player
               :item-id "my_mod:induction_factor_electromaster"
               :side :server}))))
    (is (nil? (get-in (store/get-player-state ps-fix/test-session-id player-uuid)
                      [:ability-data :category-id])))
    (is (zero? @consumed*))))

(deftest induction-factor-catalog-lists-all-factors-test
  (is (= 4 (count (special-items/induction-factor-catalog))))
  (is (some #(= "my_mod:induction_factor_electromaster" (first %))
            (special-items/induction-factor-catalog))))

(deftest find-induction-factor-scans-inventory-test
  (let [inventory {"my_mod:induction_factor_teleporter" 1}]
    (with-redefs [entity/player-count-item-by-id
                  (fn [_ item-id] (get inventory item-id 0))]
      (is (= {:item-id "my_mod:induction_factor_teleporter" :category :teleporter}
             (special-items/find-induction-factor :stub-player)))))
  (with-redefs [entity/player-count-item-by-id (constantly 0)]
    (is (nil? (special-items/find-induction-factor :stub-player)))))
