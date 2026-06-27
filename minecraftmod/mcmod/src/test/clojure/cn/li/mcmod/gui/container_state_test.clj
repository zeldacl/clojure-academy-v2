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

(deftest non-cmenu-bridge-returns-nil-test
  (testing "get-container-for-menu returns nil for objects without cljContainer field"
    (let [plain-obj (Object.)
          container {:gui-type :node :id 1}]
      ;; register/unregister silently succeed (field absent → catch + no-op)
      (is (nil? (state/register-menu-container! plain-obj container)))
      (is (nil? (state/get-container-for-menu plain-obj)))
      (is (nil? (state/resolve-container-for-menu plain-obj)))
      (is (nil? (state/unregister-menu-container! plain-obj))))))

(deftest get-menu-container-id-uses-platform-protocol-test
  (testing "get-menu-container-id reads via platform protocol, not from atom"
    (let [menu (FakePlatformObject. nil 42)]
      (is (= 42 (state/get-menu-container-id menu)))
      (is (nil? (state/get-menu-container-id nil))))))

(deftest no-op-stubs-are-callable-and-return-nil-test
  (testing "legacy register/unregister/query stubs work without atom"
    (let [owner {:client-session-id :s :player-uuid "p"}
          container {:id 1}]
      (is (nil? (state/register-active-container! owner container)))
      (is (nil? (state/unregister-active-container! owner container)))
      (is (nil? (state/register-player-container! owner container)))
      (is (nil? (state/unregister-player-container! owner)))
      (is (nil? (state/register-container-by-id! owner 7 container)))
      (is (nil? (state/unregister-container-by-id! owner 7)))
      (is (= [] (state/list-active-containers)))
      (is (= [] (state/list-active-containers owner)))
      (is (nil? (state/get-player-container owner)))
      (is (= [] (state/get-player-containers owner)))
      (is (nil? (state/get-player-container-from-active owner)))
      (is (nil? (state/get-container-by-id owner 7)))
      (is (nil? (state/clear-all!)))
      (is (nil? (state/clear-owner-containers! owner)))
      (is (nil? (state/clear-session-containers! :session-a)))
      (let [snap (state/container-state-snapshot)]
        (is (= {} (:active-containers snap)))
        (is (= {} (:player-containers snap)))
        (is (= {} (:menu-containers snap)))
        (is (= {} (:containers-by-id snap)))))))

(deftest client-container-side-channel-stays-absent-test
  (testing "the legacy global client-container API does not return"
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'client-container)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'set-client-container!)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'get-client-container)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'clear-client-container!)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.container-state 'set-tab-index-by-container-id!)))
    (is (nil? (ns-resolve 'cn.li.mcmod.gui.tabbed-gui 'set-tab-index-by-container-id!)))))
