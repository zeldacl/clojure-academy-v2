(ns cn.li.ac.content.ability.electromaster.mine-detect-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.content.ability :as ability-content]
            [cn.li.ac.content.ability.electromaster.mine-detect :as mine-detect]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- with-config-registry [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (let [descriptors (config-reg/get-descriptor-registry)
            values (config-reg/get-value-registry)]
        (try
          (config-reg/set-descriptor-registry! {})
          (config-reg/set-value-registry! {})
          (store/reset-store!)
          (f)
          (finally
            (config-reg/set-descriptor-registry! descriptors)
            (config-reg/set-value-registry! values)
            (store/reset-store!)))))))

(defn- seed-electromaster-config! [values]
  (let [domain (skill-config/category-domain :electromaster)]
    (config-reg/register-config-descriptors!
      domain
      (get skill-config/descriptors-by-category :electromaster))
    (config-reg/ensure-default-values!
      domain
      (get skill-config/default-values-by-category :electromaster))
    (config-reg/set-config-values! domain values)))

(deftest mine-detect-public-spec-uses-upstream-level-and-tunables-test
  (testing "MineDetect exposes upstream level and tunable cost/cooldown through the public skill spec"
    (with-config-registry
      (fn []
        (ability-content/init-ability-content!)
        (seed-electromaster-config!
          {(skill-config/config-key :mine-detect :cost.down.cp) [1500.0 1000.0]
           (skill-config/config-key :mine-detect :cost.down.overload) [200.0 180.0]
           (skill-config/config-key :mine-detect :cooldown.ticks) [900.0 400.0]
           (skill-config/config-key :mine-detect :progression.exp-cast) 0.008})
        (let [spec (skill-registry/get-skill :mine-detect)
              player-id "mine-detect-config-player"]
          (is (= 3 (:level spec)))
          (is (false? (:controllable? spec)))
          (is (= 1500.0 ((get-in spec [:cost :down :cp]) {:player-id player-id})))
          (is (= 200.0 ((get-in spec [:cost :down :overload]) {:player-id player-id})))
          (is (= 900 (skill-config/lerp-int :mine-detect :cooldown.ticks 0.0)))
          (is (= 0.008 (skill-config/tunable-double :mine-detect :progression.exp-cast))))))))

(deftest perform-sends-minimal-activation-payload-and-awards-exp-test
  (testing "MineDetect now sends a compact activation payload instead of a scanned ore snapshot"
    (let [fx* (atom [])
          potion* (atom [])]
      (with-redefs [skill-effects/skill-exp (fn [_ _] 0.6)
                    skill-config/lerp-double (fn [_ field-id exp]
                                               (case field-id
                                                 :targeting.range 24.0
                                                 :cooldown.ticks 600.0
                                                 0.0))
                    skill-config/tunable-double (fn [_ field-id]
                                                  (case field-id
                                                    :progression.exp-cast 0.008
                                                    0.0))
                    skill-config/tunable-int (fn [_ field-id]
                                               (case field-id
                                                 :effect.blindness-duration-ticks 100
                                                 :effect.blindness-amplifier 0
                                                 0))
                    skill-effects/player-path (fn [player-id path & [default]]
                                                (if (= path [:ability-data :level])
                                                  4
                                                  default))
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! potion* conj [:exp player-id skill-id amount])
                                                   nil)
                    skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                       (swap! potion* conj [:cooldown player-id skill-id ticks])
                                                       nil)
                    ctx/ctx-send-to-client! (fn [ctx-id ch payload]
                                              (swap! fx* conj [ctx-id ch payload])
                                              nil)
                    potion-effects/*potion-effects* :mock]
        (with-redefs [potion-effects/apply-potion-effect! (fn [& args]
                                                             (swap! potion* conj args)
                                                             nil)]
          (mine-detect/mine-detect-perform! {:player-id "mine-detect-player"
                                            :ctx-id "ctx-1"})))
      (is (= ["ctx-1" :mine-detect/fx-perform
              {:mode :perform
               :life-ticks 100
               :rescan-interval 5
               :range 24.0
               :advanced? true}]
             (first @fx*)))
      (is (= [[:mock "mine-detect-player" :blindness 100 0]
              [:exp "mine-detect-player" :mine-detect 0.008]
              [:cooldown "mine-detect-player" :mine-detect 600]]
             @potion*)))))