(ns cn.li.ac.content.ability.server-runtime-lifecycle-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as damage-helper]
            [cn.li.ac.content.ability.server-runtime-lifecycle :as lifecycle]
            [cn.li.ac.content.ability.vecmanip.arbitration :as arbitration]
            [cn.li.ac.content.ability.vecmanip.vec-reflection :as vec-reflection]))

(defn- reset-server-runtime-lifecycle-fixture [f]
  (lifecycle/reset-server-runtime-lifecycle-for-test!)
  (try
    (f)
    (finally
      (lifecycle/reset-server-runtime-lifecycle-for-test!))))

(use-fixtures :each reset-server-runtime-lifecycle-fixture)

(deftest install-server-runtime-lifecycle-installs-runtimes-once-test
  (lifecycle/install-server-runtime-lifecycle!)
  (let [arbitration-runtime-a (var-get #'arbitration/*projectile-arbitration-runtime*)
        vec-reflection-runtime-a (var-get #'vec-reflection/*vec-reflection-runtime*)
        damage-helper-runtime-a (var-get #'damage-helper/*damage-helper-runtime*)]
    (is (some? arbitration-runtime-a))
    (is (some? vec-reflection-runtime-a))
    (is (some? damage-helper-runtime-a))
    (lifecycle/install-server-runtime-lifecycle!)
    (is (identical? arbitration-runtime-a (var-get #'arbitration/*projectile-arbitration-runtime*)))
    (is (identical? vec-reflection-runtime-a (var-get #'vec-reflection/*vec-reflection-runtime*)))
    (is (identical? damage-helper-runtime-a (var-get #'damage-helper/*damage-helper-runtime*)))))

(deftest reset-ability-server-runtimes-replaces-runtime-roots-and-clears-state-test
  (lifecycle/install-server-runtime-lifecycle!)
  (arbitration/reset-projectile-locks-for-test! {:tick 9 :owners {["p1" "arrow"] :vec-reflection}})
  (vec-reflection/reset-reflection-runtime-for-test!)
  (vec-reflection/mark-reflecting-for-test! "p" "a" "ctx" "chain")
  (damage-helper/reset-marks-for-test!
    {[
      "source" "target"] {:source-player-id "source"
                            :target-id "target"
                            :expire-at Long/MAX_VALUE
                            :rate 2.0}})
  (let [arbitration-runtime-a (var-get #'arbitration/*projectile-arbitration-runtime*)
        vec-reflection-runtime-a (var-get #'vec-reflection/*vec-reflection-runtime*)
        damage-helper-runtime-a (var-get #'damage-helper/*damage-helper-runtime*)]
    (lifecycle/reset-ability-server-runtimes!)
    (is (not (identical? arbitration-runtime-a (var-get #'arbitration/*projectile-arbitration-runtime*))))
    (is (not (identical? vec-reflection-runtime-a (var-get #'vec-reflection/*vec-reflection-runtime*))))
    (is (not (identical? damage-helper-runtime-a (var-get #'damage-helper/*damage-helper-runtime*))))
    (is (= {:tick -1 :owners {}}
           (arbitration/projectile-locks-snapshot)))
    (is (= {:reflecting-pairs #{}
            :reflection-depths {}}
           (vec-reflection/reflection-runtime-snapshot)))
    (is (= {}
           (damage-helper/marks-snapshot)))))

(deftest reset-server-runtime-lifecycle-clears-runtime-roots-test
  (lifecycle/install-server-runtime-lifecycle!)
  (lifecycle/reset-server-runtime-lifecycle-for-test!)
  (is (nil? (var-get #'arbitration/*projectile-arbitration-runtime*)))
  (is (nil? (var-get #'vec-reflection/*vec-reflection-runtime*)))
  (is (nil? (var-get #'damage-helper/*damage-helper-runtime*))))