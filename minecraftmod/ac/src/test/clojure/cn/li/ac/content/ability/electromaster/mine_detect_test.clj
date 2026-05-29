(ns cn.li.ac.content.ability.electromaster.mine-detect-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.content.ability.electromaster.mine-detect :as mine-detect]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

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