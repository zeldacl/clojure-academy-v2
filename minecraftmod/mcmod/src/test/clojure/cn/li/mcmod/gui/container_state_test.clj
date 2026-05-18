(ns cn.li.mcmod.gui.container-state-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.gui.container-state :as state]
            [cn.li.mcmod.platform.entity :as entity]))

(deftype FakePlatformObject [uuid container-id]
  entity/IEntityOps
  (entity-distance-to-sqr [_ _x _y _z] 0.0)
  (player-get-level [_] nil)
  (player-creative? [_] false)
  (player-spectator? [_] false)
  (player-get-name [_] (str uuid))
  (player-get-uuid [_] uuid)
  (player-get-main-hand-item-id [_] nil)
  (player-get-main-hand-item-count [_] 0)
  (player-consume-main-hand-item! [_ _amount] false)
  (player-count-item-by-id [_ _item-id] 0)
  (player-consume-item-by-id! [_ _item-id _amount] false)
  (player-give-item-stack! [_ _item-stack] false)
  (player-spawn-entity-by-id! [_ _entity-id _speed] false)
  (player-raytrace-block [_ _reach _fluid-source-only?] nil)
  (player-get-container-menu [_] nil)
  (inventory-get-player [_] nil)
  (menu-get-container-id [_] container-id))

(use-fixtures
  :each
  (fn [f]
    (state/clear-all!)
    (try
      (f)
      (finally
        (state/clear-all!)))))

(deftest active-player-menu-and-id-lifecycle-test
  (testing "runtime state registers and unregisters the full menu lifecycle"
    (let [container {:gui-type :node :id 1}
          player (FakePlatformObject. "player-1" nil)
          menu (FakePlatformObject. nil 17)]
      (state/register-active-container! container)
      (state/register-player-container! player container)
      (state/register-menu-container! menu container)
      (state/register-container-by-id! 17 container)

      (is (= #{container} (state/list-active-containers)))
      (is (identical? container (state/get-player-container player)))
      (is (identical? container (state/get-container-for-menu menu)))
      (is (identical? container (state/get-container-by-id 17)))
      (is (= 17 (state/get-menu-container-id menu)))
      (is (identical? container (state/resolve-container-for-menu menu)))

      (state/unregister-menu-container! menu)
      (state/unregister-container-by-id! 17)
      (state/unregister-player-container! player)
      (state/unregister-active-container! container)

      (is (empty? (state/list-active-containers)))
      (is (nil? (state/get-player-container player)))
      (is (nil? (state/get-container-for-menu menu)))
      (is (nil? (state/get-container-by-id 17))))))

(deftest resolve-container-for-menu-falls-back-to-container-id-test
  (testing "screen creation can recover the Clojure container from a menu id"
    (let [container {:gui-type :matrix :id 2}
          menu (FakePlatformObject. nil 42)]
      (state/register-container-by-id! 42 container)
      (is (nil? (state/get-container-for-menu menu)))
      (is (identical? container (state/resolve-container-for-menu menu))))))

(deftest client-container-side-channel-stays-absent-test
  (testing "the legacy global client-container API does not return"
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'client-container)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'set-client-container!)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'get-client-container)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'clear-client-container!)))))
