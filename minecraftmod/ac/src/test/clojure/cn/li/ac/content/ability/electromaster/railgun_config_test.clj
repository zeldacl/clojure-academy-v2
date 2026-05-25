(ns cn.li.ac.content.ability.electromaster.railgun-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.player-state :as player-state]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.content.ability :as ability-content]
            [cn.li.ac.content.ability.electromaster.railgun :as railgun]
            [cn.li.mcmod.config.registry :as config-reg]))

(defn- with-test-state
  [f]
  (let [descriptors @config-reg/descriptor-registry
        values @config-reg/value-registry
        player-states @player-state/player-states]
    (try
      (reset! config-reg/descriptor-registry {})
      (reset! config-reg/value-registry {})
      (reset! player-state/player-states {})
      (f)
      (finally
        (reset! config-reg/descriptor-registry descriptors)
        (reset! config-reg/value-registry values)
        (reset! player-state/player-states player-states)))))

(defn- seed-electromaster-config!
  [values]
  (let [domain (skill-config/category-domain :electromaster)]
    (config-reg/register-config-descriptors!
      domain
      (get skill-config/descriptors-by-category :electromaster))
    (config-reg/ensure-default-values!
      domain
      (get skill-config/default-values-by-category :electromaster))
    (config-reg/set-config-values! domain values)))

(deftest railgun-qte-down-cost-uses-action-tunable-curve-test
  (testing "railgun cost functions exposed through the public skill spec read action tunables"
    (with-test-state
      (fn []
        (ability-content/init-ability-content!)
        (let [player-id "railgun-config-test-player"]
          (seed-electromaster-config!
            {(skill-config/config-key :railgun :cost.down.cp) [1000.0 2000.0]
             (skill-config/config-key :railgun :cost.down.overload) [300.0 100.0]})
          (player-state/set-player-state!
            player-id
            (-> (player-state/fresh-state)
                (assoc-in [:ability-data :skills :railgun :exp] 0.5)))
          (with-redefs [railgun/read-coin-qte-status (fn [_]
                                                        {:has-window? true
                                                         :active? true
                                                         :perform? true
                                                         :progress 0.75})]
            (let [spec (skill-registry/get-skill :railgun)
                  down-cp (get-in spec [:cost :down :cp])
                  down-overload (get-in spec [:cost :down :overload])]
              (is (fn? down-cp))
              (is (fn? down-overload))
              (is (= 1500.0 (down-cp {:player-id player-id})))
              (is (= 200.0 (down-overload {:player-id player-id}))))))))))
