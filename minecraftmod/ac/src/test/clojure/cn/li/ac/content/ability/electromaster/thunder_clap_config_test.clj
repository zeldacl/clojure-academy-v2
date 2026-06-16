(ns cn.li.ac.content.ability.electromaster.thunder-clap-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.test.support.skill-context :as skill-ctx]
            [cn.li.ac.content.ability.electromaster.thunder-clap :as thunder-clap]))

(deftest thunder-clap-public-spec-uses-action-tunables-test
  (testing "ThunderClap cost and targeting values exposed through the public skill spec read action tunables"
    (let [spec thunder-clap/thunder-clap
          down-overload (get-in spec [:cost :down :overload])
          tick-cp (get-in spec [:cost :tick :cp])
          down-action (get-in spec [:actions :down!])
          tick-action (get-in spec [:actions :tick!])
          mocks (skill-ctx/content-ctx-mocks {:skill-state {}})
          {:keys [get-context update-skill-state-root! assoc-skill-state! clear-skill-state!]}
          mocks]
      (skill-ctx/with-server-skill-context
        #(with-redefs [ctx/get-context get-context
                      ctx-skill/update-skill-state-root! update-skill-state-root!
                      ctx-skill/assoc-skill-state! assoc-skill-state!
                      ctx-skill/clear-skill-state! clear-skill-state!
                      thunder-clap/cfg-lerp (fn [field exp]
                                              (case field
                                                :cost.down.overload (+ 100.0 (* 200.0 exp))
                                                :cost.tick.cp (+ 10.0 (* 20.0 exp))
                                                0.0))
                      thunder-clap/min-ticks (fn [] 50)
                      thunder-clap/targeting-range (fn [] 77.0)
                      thunder-clap/resolve-raycast-target (fn [_] {:x 1.0 :y 2.0 :z 3.0})]
           (is (fn? down-overload))
           (is (fn? tick-cp))
           (is (fn? down-action))
           (is (fn? tick-action))
           (is (= 200.0 (down-overload {:exp 0.5})))
           (is (= 20.0 (tick-cp {:hold-ticks 50 :exp 0.5})))
           (is (= 0.0 (tick-cp {:hold-ticks 51 :exp 0.5})))
           (is (nil? (down-action {:ctx-id "ctx-1" :player-id "p1"})))
           (is (nil? (tick-action {:ctx-id "ctx-1" :player-id "p1"}))))))))
