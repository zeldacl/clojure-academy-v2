(ns cn.li.ac.item.special-items-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.model.ability :as adata]            [cn.li.ac.ability.util.uuid :as uuid]
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

(deftype StubPlayer [state*]
  entity/IEntityOps
  (entity-distance-to-sqr [_ _ _ _] 0.0)
  (player-get-level [_] nil)
  (player-creative? [_] false)
  (player-spectator? [_] false)
  (player-get-name [_] "stub-player")
  (player-get-uuid [_] "stub-player")
  (player-get-main-hand-item-id [_] nil)
  (player-get-main-hand-item-count [_] 1)
  (player-main-hand-placeable-block? [_] false)
  (player-place-main-hand-block-at-hit! [_ _ _ _ _ _]
    {:placed? false :fallback-drop? false :pos nil :face nil})
  (player-consume-main-hand-item! [_ amount]
    (swap! state* update :main-hand-consumed (fnil + 0) amount)
    true)
  (player-drop-main-hand-item-at! [_ _ _ _ _] false)
  (player-count-item-by-id [_ item-id]
    (get-in @state* [:inventory item-id] 0))
  (player-consume-item-by-id! [_ item-id amount]
    (let [count (get-in @state* [:inventory item-id] 0)]
      (when (>= count amount)
        (swap! state* assoc-in [:inventory item-id] (- count amount))
        true)))
  (player-give-item-stack! [_ _] false)
  (player-spawn-entity-by-id! [_ _ _] false)
  (player-raytrace-block [_ _ _] nil)
  (player-get-container-menu [_] nil)
  (inventory-get-player [_] nil)
  (menu-get-container-id [_] 0))

(defn- seed-player!
  [player-uuid ability-data]
  (store/set-player-state!*
   ps-fix/test-session-id
   player-uuid
   (assoc (store/fresh-player-state) :ability-data ability-data)))

(deftest induction-factor-right-click-does-not-mutate-ability-state-test
  (let [player-state* (atom {:inventory {}})
        player (StubPlayer. player-state*)
        player-uuid "p1"]
    (seed-player! player-uuid (adata/new-ability-data))
    (with-redefs [uuid/player-uuid (constantly player-uuid)]
      (is (= {:consume? false}
             (#'special-items/apply-induction-factor!
              {:player player
               :item-id "my_mod:induction_factor_electromaster"
               :side :server}))))
    (is (nil? (get-in (store/get-player-state* ps-fix/test-session-id player-uuid)
                      [:ability-data :category-id])))
    (is (nil? (:main-hand-consumed @player-state*)))))

(deftest induction-factor-catalog-lists-all-factors-test
  (is (= 4 (count (special-items/induction-factor-catalog))))
  (is (some #(= "my_mod:induction_factor_electromaster" (first %))
            (special-items/induction-factor-catalog))))

