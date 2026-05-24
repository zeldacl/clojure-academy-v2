(ns cn.li.ac.block.developer.gui-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.developer.gui :as developer-gui]
            [cn.li.ac.block.developer.logic :as developer-logic]
            [cn.li.ac.block.developer.panel :as developer-panel]
            [cn.li.ac.ability.domain.developer :as developer-domain]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- widget-tree []
  (let [root (cgui-core/create-widget :name "main")
        parent-left (cgui-core/create-widget :name "parent_left")
        panel-ability (cgui-core/create-widget :name "panel_ability")
        btn-upgrade (cgui-core/create-widget :name "btn_upgrade")
        panel-machine (cgui-core/create-widget :name "panel_machine")
        wireless-btn (cgui-core/create-widget :name "button_wireless")]
    (cgui-core/add-widget! panel-ability btn-upgrade)
    (cgui-core/add-widget! panel-machine wireless-btn)
    (cgui-core/add-widget! parent-left panel-ability)
    (cgui-core/add-widget! parent-left panel-machine)
    (cgui-core/add-widget! root parent-left)
    root))

(deftest developer-open-gui-forwards-to-platform-gui-test
  (let [calls (atom [])]
    (with-redefs [requiring-resolve
                  (fn [sym]
                    (when (= sym 'cn.li.ac.gui.open/open-gui-by-type)
                      (fn [& args] (swap! calls conj args))))
                  bdsl/get-block (fn [_] nil)
                  world/world-is-client-side* (fn [_] true)]
      ((developer-logic/open-developer-gui-for "developer-normal")
       {:player :player-1
        :world :client-world
        :pos [10 20 30]
        :sneaking false})
      (is (= [[{:player :player-1 :world :client-world :pos [10 20 30] :sneaking false}
               :developer
               :client-world
               [10 20 30]]]
             @calls)))))

(deftest developer-panel-upgrade-button-opens-skill-tree-with-context-test
  (let [calls (atom [])
        root (widget-tree)]
    (with-redefs [events/on-left-click (fn [widget handler]
                                         (swap! calls conj {:kind :learn :handler handler})
                                         widget)
                  events/on-frame (fn [widget _handler]
                                    widget)
                  uuid/player-uuid (fn [_] "player-uuid")
                  entity/player-get-name (fn [_] "Player One")
                  platform-be/get-block-id (fn [_] :developer-advanced)
                  developer-domain/developer-type-for-block-id (fn [_] :advanced)
                  net-helpers/tile-pos-payload (fn [_] {:x 12 :y 34 :z 56})
                  client-bridge/open-skill-tree-screen!
                  (fn [player-uuid learn-context]
                    (swap! calls conj {:kind :open :player-uuid player-uuid :learn-context learn-context}))]
      (developer-panel/attach-classic-developer-bindings!
       root
       {:player :player-1
        :tile-entity :tile-1}
        {:switch-wireless-tab! nil})
      (is (= 1 (count (filter #(= :learn (:kind %)) @calls))))
      (let [{:keys [handler]} (first @calls)]
        (handler {:x 1 :y 2})
        (is (= [{:kind :learn :handler handler}
                {:kind :open
                 :player-uuid "player-uuid"
                 :learn-context {:x 12 :y 34 :z 56
                                 :developer-type :advanced}}]
               @calls))))))

(deftest developer-gui-init-registers-once-test
  (let [slot-calls (atom 0)
        gui-calls (atom 0)]
    (with-redefs [slot-schema/register-slot-schema! (fn [_] (swap! slot-calls inc))
                  gui-reg/register-block-gui! (fn [& _] (swap! gui-calls inc))]
      (developer-gui/init-developer-gui!)
      (developer-gui/init-developer-gui!)
      (is (= 1 @slot-calls))
      (is (= 1 @gui-calls)))))