(ns cn.li.ac.ability.server.service.skill-effects-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.player-state :as ps]))

(deftest scale-damage-default-and-custom-test
  (binding [cn.li.ac.ability.config/*damage-scale* 2.0]
    (is (= 20.0 (skill-effects/scale-damage {} 10.0)))
    (is (= 30.0 (skill-effects/scale-damage {:damage-scale 1.5} 10.0)))))

(deftest apply-cost-runtime-speed-and-empty-cost-test
  (testing "missing cost branch is true"
    (is (true? (skill-effects/apply-cost! {} :down {:player-id "p"}))))
  (testing "runtime cost delegates to perform-resource!"
    (let [seen (atom nil)]
      (with-redefs [skill-effects/perform-resource! (fn [pid ol cp creative?]
                                                      (reset! seen {:pid pid :ol ol :cp cp :creative? creative?})
                                                      {:success? true})]
        (is (true? (skill-effects/apply-cost!
                    {:cp-consume-speed 1.0
                     :overload-consume-speed 1.0
                     :cost {:tick {:mode :runtime-speed :cp-speed 0.5 :overload-speed 1.0}}}
                    :tick
                    {:player-id "p"})))
        (is (= "p" (:pid @seen)))
        (is (pos? (double (:ol @seen))))
        (is (pos? (double (:cp @seen))))
        (is (false? (:creative? @seen)))))))

(deftest perform-resource-state-missing-test
  (reset! ps/player-states {})
  (is (= {:success? false :events [] :data nil}
         (skill-effects/perform-resource! "missing" 1.0 1.0 false))))
