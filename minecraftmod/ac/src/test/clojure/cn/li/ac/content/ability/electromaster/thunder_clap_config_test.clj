(ns cn.li.ac.content.ability.electromaster.thunder-clap-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.content.ability.electromaster.thunder-clap :as thunder-clap]))

(deftest thunder-clap-public-spec-uses-action-tunables-test
  (testing "ThunderClap cost and targeting values exposed through the public skill spec read action tunables"
    (let [spec thunder-clap/thunder-clap
          down-overload (get-in spec [:cost :down :overload])
          tick-cp (get-in spec [:cost :tick :cp])
          range-fn (get-in spec [:on-down 0 1 :range])]
      (with-redefs [thunder-clap/cfg-lerp (fn [field exp]
                                            (case field
                                              :cost.down.overload (+ 100.0 (* 200.0 exp))
                                              :cost.tick.cp (+ 10.0 (* 20.0 exp))
                                              0.0))
                    thunder-clap/min-ticks (fn [] 50)
                    thunder-clap/targeting-range (fn [] 77.0)]
        (is (fn? down-overload))
        (is (fn? tick-cp))
        (is (fn? range-fn))
        (is (= 200.0 (down-overload {:exp 0.5})))
        (is (= 20.0 (tick-cp {:hold-ticks 50 :exp 0.5})))
        (is (= 0.0 (tick-cp {:hold-ticks 51 :exp 0.5})))
        (is (= 77.0 (range-fn {})))))))
