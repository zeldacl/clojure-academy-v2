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
  (player-main-hand-placeable-block? [_] false)
  (player-place-main-hand-block-at-hit! [_ _world-id _x _y _z _face]
    {:placed? false :fallback-drop? false})
  (player-consume-main-hand-item! [_ _amount] false)
  (player-drop-main-hand-item-at! [_ _amount _x _y _z] false)
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
    (state/call-with-container-state-runtime
      (state/create-container-state-runtime)
      (fn []
        (try
          (f)
          (finally
            (state/clear-all!)))))))

(deftest active-player-menu-and-id-lifecycle-test
  (testing "runtime state registers and unregisters the full menu lifecycle"
    (let [owner {:session-id :session-a :player-uuid "player-1"}
          container {:gui-type :node :id 1}
          menu (FakePlatformObject. nil 17)]
  (state/register-active-container! owner container)
  (state/register-player-container! owner container)
      (state/register-menu-container! menu container)
      (state/register-container-by-id! owner 17 container)

  (is (= #{container} (set (state/list-active-containers))))
  (is (= [container] (vec (state/list-active-containers owner))))
  (is (identical? container (state/get-player-container owner)))
      (is (identical? container (state/get-container-for-menu menu)))
      (is (identical? container (state/get-container-by-id owner 17)))
      (is (= 17 (state/get-menu-container-id menu)))
      (is (identical? container (state/resolve-container-for-menu menu)))

      (state/unregister-menu-container! menu)
      (state/unregister-container-by-id! owner 17)
  (state/unregister-player-container! owner)
  (state/unregister-active-container! owner container)

      (is (empty? (state/list-active-containers)))
  (is (nil? (state/get-player-container owner)))
      (is (nil? (state/get-container-for-menu menu)))
      (is (nil? (state/get-container-by-id owner 17))))))

    (deftest ownerless-container-id-lookup-fails-fast-test
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
            #"GUI container id lookup requires explicit owner"
            (state/register-container-by-id! 42 {:gui-type :matrix})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
            #"GUI container id lookup requires explicit owner"
            (state/get-container-by-id 42))))

(deftest same-container-id-is-isolated-by-player-owner-test
  (testing "two players may have the same Minecraft window id without overwriting each other"
    (let [p1 (FakePlatformObject. "player-1" nil)
          p2 (FakePlatformObject. "player-2" nil)
        owner-1 {:session-id :session-a :player p1}
        owner-2 {:session-id :session-a :player p2}
          c1 {:gui-type :node :id :p1}
          c2 {:gui-type :node :id :p2}]
      (state/register-container-by-id! owner-1 7 c1)
      (state/register-container-by-id! owner-2 7 c2)

      (is (identical? c1 (state/get-container-by-id owner-1 7)))
      (is (identical? c2 (state/get-container-by-id owner-2 7)))

      (state/unregister-container-by-id! owner-1 7)
      (is (nil? (state/get-container-by-id owner-1 7)))
      (is (identical? c2 (state/get-container-by-id owner-2 7))))))

(deftest player-container-stack-removes-only-closed-container-test
  (testing "closing one container does not wipe another active container for the same player"
    (let [owner {:session-id :session-stack :player-uuid "player-stack"}
          c1 {:gui-type :first}
          c2 {:gui-type :second}]
      (state/register-player-container! owner c1)
      (state/register-player-container! owner c2)

      (is (identical? c2 (state/get-player-container owner)))
      (is (= [c1 c2] (state/get-player-containers owner)))

      (state/unregister-player-container! owner c2)
      (is (identical? c1 (state/get-player-container owner)))
      (is (= [c1] (state/get-player-containers owner))))))

(deftest clear-session-containers-removes-only-matching-session-test
  (let [owner-a {:server-session-id :server-a :player-uuid "player-1"}
        owner-b {:server-session-id :server-b :player-uuid "player-1"}
        c1 {:gui-type :node :id :a}
        c2 {:gui-type :node :id :b}]
    (state/register-active-container! owner-a c1)
    (state/register-active-container! owner-b c2)
    (state/register-player-container! owner-a c1)
    (state/register-player-container! owner-b c2)
    (state/register-container-by-id! owner-a 7 c1)
    (state/register-container-by-id! owner-b 7 c2)
    (state/set-tab-index-by-container-id! owner-a 7 1)
    (state/set-tab-index-by-container-id! owner-b 7 0)

    (state/clear-session-containers! :server-a)

    (is (nil? (state/get-player-container owner-a)))
    (is (identical? c2 (state/get-player-container owner-b)))
    (is (nil? (state/get-container-by-id owner-a 7)))
    (is (identical? c2 (state/get-container-by-id owner-b 7)))
    (is (nil? (state/get-tab-index-by-container-id owner-a 7)))
    (is (= 0 (state/get-tab-index-by-container-id owner-b 7)))
    (is (= [c2] (vec (state/list-active-containers owner-b))))))

(deftest client-container-side-channel-stays-absent-test
  (testing "the legacy global client-container API does not return"
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'client-container)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'set-client-container!)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'get-client-container)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'clear-client-container!)))))
