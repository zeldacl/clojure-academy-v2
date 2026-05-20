(ns cn.li.ac.content.ability.electromaster.thunder-clap-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.registry :as skill-registry]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.content.ability.electromaster.thunder-clap]
            [cn.li.mcmod.config.registry :as config-reg]))

(defn- with-test-state
  [f]
  (let [descriptors @config-reg/descriptor-registry
        values @config-reg/value-registry]
    (try
      (reset! config-reg/descriptor-registry {})
      (reset! config-reg/value-registry {})
      (f)
      (finally
        (reset! config-reg/descriptor-registry descriptors)
        (reset! config-reg/value-registry values)))))

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

(deftest thunder-clap-public-spec-uses-action-tunables-test
  (testing "ThunderClap cost and targeting values exposed through the public skill spec read action tunables"
    (with-test-state
      (fn []
        (seed-electromaster-config!
          {(skill-config/config-key :thunder-clap :targeting.range) 77.0
           (skill-config/config-key :thunder-clap :charge.min-ticks) 50
           (skill-config/config-key :thunder-clap :cost.down.overload) [100.0 300.0]
           (skill-config/config-key :thunder-clap :cost.tick.cp) [10.0 30.0]})
        (let [spec (skill-registry/get-skill :thunder-clap)
              down-overload (get-in spec [:cost :down :overload])
              tick-cp (get-in spec [:cost :tick :cp])
              range-fn (get-in spec [:on-down 0 1 :range])]
          (is (fn? down-overload))
          (is (fn? tick-cp))
          (is (fn? range-fn))
          (is (= 200.0 (down-overload {:exp 0.5})))
          (is (= 20.0 (tick-cp {:hold-ticks 50 :exp 0.5})))
          (is (= 0.0 (tick-cp {:hold-ticks 51 :exp 0.5})))
          (is (= 77.0 (range-fn {}))))))))
