(ns cn.li.ac.block.developer.gui-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is]]
            [cn.li.ac.block.developer.gui :as developer-gui]
            [cn.li.ac.block.developer.logic :as developer-logic]
            [cn.li.ac.block.developer.panel :as developer-panel]
            [cn.li.ac.ability.domain.developer :as developer-domain]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
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
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- with-player-state-owner
  [player-uuid f]
  (binding [runtime-hooks/*player-state-owner* {:server-session-id :test-session
                                                :player-uuid player-uuid}]
    (f)))

(defn- widget-tree []
  (let [root (cgui-core/create-widget :name "main")
        parent-left (cgui-core/create-widget :name "parent_left")
        panel-ability (cgui-core/create-widget :name "panel_ability")
    text-ability (cgui-core/create-widget :name "text_abilityname")
    logo-ability (cgui-core/create-widget :name "logo_ability")
    text-exp (cgui-core/create-widget :name "text_exp")
    text-level (cgui-core/create-widget :name "text_level")
    logo-progress (cgui-core/create-widget :name "logo_progress")
        btn-upgrade (cgui-core/create-widget :name "btn_upgrade")
        panel-machine (cgui-core/create-widget :name "panel_machine")
    wireless-btn (cgui-core/create-widget :name "button_wireless")
    text-node (cgui-core/create-widget :name "text_nodename")
    progress-power (cgui-core/create-widget :name "progress_power")
    progress-sync (cgui-core/create-widget :name "progress_syncrate")]
  (cgui-core/add-widget! panel-ability text-ability)
  (cgui-core/add-widget! panel-ability logo-ability)
  (cgui-core/add-widget! panel-ability text-exp)
  (cgui-core/add-widget! panel-ability text-level)
  (cgui-core/add-widget! panel-ability logo-progress)
    (cgui-core/add-widget! panel-ability btn-upgrade)
  (cgui-core/add-widget! wireless-btn text-node)
  (cgui-core/add-widget! panel-machine progress-power)
  (cgui-core/add-widget! panel-machine progress-sync)
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
                  bdsl/get-block-spec (fn [_] nil)
                  world/world-is-client-side* (fn [_] true)]
      ((developer-logic/open-developer-gui-for "developer-normal")
       {:player :player-1
        :world :client-world
        :pos [10 20 30]
        :sneaking false})
      (is (= [[:player-1
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
                  store/get-player-state* (fn [_ _]
                                                  {:ability-data {:category-id :electromaster
                                                                  :level 2
                                                                  :level-progress 1000.0}})
                  category/get-category (fn [_] {:name-key "ac.category.electromaster"})
                  category/get-prog-incr-rate (fn [_] 1.0)
                  uuid/player-uuid (fn [_] "player-uuid")
                  entity/player-get-name (fn [_] "Player One")
                  platform-be/get-block-id (fn [_] :developer-advanced)
                  developer-domain/developer-type-for-block-id (fn [_] :advanced)
                  developer-domain/min-for-level (fn [_] :normal)
                  developer-domain/gte? (fn [_ _] true)
                  skill-query/get-controllable-skills-at-level (fn [& _] [])
                  net-helpers/tile-pos-payload (fn [_] {:x 12 :y 34 :z 56})
                  client-bridge/open-screen!
                  (fn [screen-id payload]
                    (swap! calls conj {:kind :open :screen-id screen-id :payload payload}))]
      (developer-panel/attach-classic-developer-bindings!
       root
       {:player :player-1
        :tile-entity :tile-1
        :energy (atom 0.0)
        :max-energy (atom 50000.0)
        :is-developing (atom false)
        :wireless-bandwidth (atom 1000.0)
        :wireless-inject-last-tick (atom 0.0)}
        {:switch-wireless-tab! nil})
      (with-player-state-owner
        "player-uuid"
        (fn []
          (is (= 1 (count (filter #(= :learn (:kind %)) @calls))))
          (let [{:keys [handler]} (first @calls)]
            (handler {:x 1 :y 2})
            (is (= [{:kind :learn :handler handler}
                    {:kind :open
                     :screen-id :ac/skill-tree
                     :payload {:player-uuid "player-uuid"
                               :learn-context {:x 12 :y 34 :z 56
                                               :developer-type :advanced}}}]
                   @calls))))))))

(deftest developer-panel-upgrade-button-respects-category-state-test
  (let [root (widget-tree)
        frame-handler (atom nil)]
    (with-redefs [events/on-left-click (fn [widget _handler] widget)
                  events/on-frame (fn [_widget handler]
                                    (reset! frame-handler handler)
                                    nil)
                  uuid/player-uuid (fn [_] "player-uuid")
                  platform-be/get-block-id (fn [_] :developer-advanced)
                  developer-domain/developer-type-for-block-id (fn [_] :advanced)
                  developer-domain/min-for-level (fn [_] :normal)
                  developer-domain/gte? (fn [_ _] true)
                  skill-query/get-controllable-skills-at-level (fn [& _] [])
                  msg-registry/msg (fn [domain action] [domain action])
                  net-client/send-to-server (fn [& _] nil)
                  store/get-player-state* (fn [_ _]
                                                  {:ability-data {:category-id :electromaster
                                                                  :level 2
                                                                  :level-progress 0.0}})
                  category/get-category (fn [_] {:name-key "ac.category.electromaster"})
                  category/get-prog-incr-rate (fn [_] 1.0)
                  net-helpers/tile-pos-payload (fn [_] {:pos-x 1 :pos-y 2 :pos-z 3})]
      (developer-panel/attach-classic-developer-bindings!
       root
       {:player :player-1
        :tile-entity :tile-1
        :energy (atom 0.0)
        :max-energy (atom 50000.0)
        :is-developing (atom false)
        :wireless-bandwidth (atom 1000.0)
        :wireless-inject-last-tick (atom 0.0)}
       {:switch-wireless-tab! nil})
      (with-player-state-owner
        "player-uuid"
        (fn []
          (is (fn? @frame-handler))
          (@frame-handler nil)
          (is (true? (cgui-core/visible? (cgui-core/find-widget root "parent_left/panel_ability/btn_upgrade")))))))))

(deftest developer-panel-upgrade-button-hidden-without-category-test
  (let [root (widget-tree)
        frame-handler (atom nil)
        open-calls (atom [])]
    (with-redefs [events/on-left-click (fn [widget handler]
                                         (when (= "btn_upgrade" (cgui-core/get-name widget))
                                           (swap! open-calls conj {:handler handler}))
                                         widget)
                  events/on-frame (fn [_widget handler]
                                    (reset! frame-handler handler)
                                    nil)
                  uuid/player-uuid (fn [_] "player-uuid")
                  platform-be/get-block-id (fn [_] :developer-advanced)
                  developer-domain/developer-type-for-block-id (fn [_] :advanced)
                  skill-query/get-controllable-skills-at-level (fn [& _] [])
                  store/get-player-state* (fn [_ _]
                                                  {:ability-data {:category-id nil
                                                                  :level 1
                                                                  :level-progress 0.0}})
                  category/get-category (fn [_] nil)
                  net-helpers/tile-pos-payload (fn [_] {:pos-x 1 :pos-y 2 :pos-z 3})
                  msg-registry/msg (fn [domain action] [domain action])
                  net-client/send-to-server (fn [& _] nil)
                  client-bridge/open-screen!
                  (fn [& args]
                    (swap! open-calls conj {:open args}))]
      (developer-panel/attach-classic-developer-bindings!
       root
       {:player :player-1
        :tile-entity :tile-1
        :energy (atom 0.0)
        :max-energy (atom 50000.0)
        :is-developing (atom false)
        :wireless-bandwidth (atom 1000.0)
        :wireless-inject-last-tick (atom 0.0)}
       {:switch-wireless-tab! nil})
      (with-player-state-owner
        "player-uuid"
        (fn []
          (@frame-handler nil)
          (is (false? (cgui-core/visible? (cgui-core/find-widget root "parent_left/panel_ability/btn_upgrade"))))
          (let [handler (some :handler @open-calls)]
            (is (fn? handler))
            (handler nil)
            (is (empty? (filter :open @open-calls)))))))))

(deftest developer-gui-init-registers-once-test
  (let [slot-calls (atom 0)
        gui-calls (atom 0)]
    (with-redefs [slot-schema/register-slot-schema! (fn [_] (swap! slot-calls inc))
                  gui-reg/register-block-gui! (fn [& _] (swap! gui-calls inc))]
      (developer-gui/init-developer-gui!)
      (developer-gui/init-developer-gui!)
      (is (= 1 @slot-calls))
  (is (= 1 @gui-calls)))))

(deftest developer-on-close-clears-user-session-state-test
  (let [saved (atom nil)
    changed (atom 0)]
    (with-redefs [world/world-is-client-side* (fn [_] false)
      entity/player-get-level (fn [_] :server-level)
      platform-be/get-custom-state (fn [_] {:user-uuid "u1"
                    :user-name "Player"
                    :is-developing true})
      platform-be/set-custom-state! (fn [_ st] (reset! saved st))
      platform-be/set-changed! (fn [_] (swap! changed inc))]
  (developer-gui/on-close {:tile-entity :tile-1
           :player :player-1})
  (is (= "" (:user-uuid @saved)))
  (is (= "" (:user-name @saved)))
  (is (false? (:is-developing @saved)))
  (is (= 1 @changed)))))
