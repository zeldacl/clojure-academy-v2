(ns cn.li.combat.lib-test
  "End-to-end proof (compile AND dispatch -- see cn.li.combat.run-test's own
   docstring for why compile-only is not enough, and node-core's ops_test/
   set_bang_test for the each-loop dispatch bug that motivated the rule)
   that every function in cn.li.combat.lib actually runs against combat's
   real vocabulary, one deftest per ported composite. Each test wraps the
   library function in a tiny throwaway ability, dispatches it against a
   fake host, and asserts the host calls/argument VALUES the function
   should have produced -- not just that compilation didn't throw."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.run :as run]
            [cn.li.combat.lib :as lib]))

(defn- compile-and-dispatch! [text host input]
  (let [ir (run/compile-doc! text lib/fns)
        program (run/compile-program ir host)]
    (run/dispatch! program :default input)))

;; --- target/directional-destination -----------------------------------------

(deftest directional-destination-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [cap args]) {:position {:x 9.0 :y 0.0 :z 0.0}})
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-directional :activation :instant
               :tunables {:eye-y {:type :double} :distance {:type :double}}
               :do [(target/directional-destination ?caster/eye ?caster/aim $eye-y :forward $distance {})
                    (finish {:outcome :performed})]}"
        input {:tunables {:eye-y 1.5 :distance 20.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}]
    (compile-and-dispatch! text host input)
    (is (= [[:raycast {:origin {:x 0.0 :y 1.0 :z 0.0} :look {:x 0.0 :y 0.0 :z 1.0} :eye-y 1.5
                       :direction :forward :distance 20.0 :policy {}}]]
           @calls))))

;; --- target/raycast-destination ----------------------------------------------

(deftest raycast-destination-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [cap args])
                       (if (contains? args :hit)
                         {:type :landing :position {:x 5.0 :y 6.0 :z 7.0}}
                         {:entity-id nil :position {:x 5.0 :y 6.0 :z 7.0}}))
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-raycast-dest :activation :instant
               :tunables {:distance {:type :double}}
               :do [(target/raycast-destination ?caster/eye ?caster/aim $distance {} true true)
                    (finish {:outcome :performed})]}"
        input {:tunables {:distance 30.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (testing "raycast ran first, without a :hit arg"
      (is (= :raycast (first (first @calls))))
      (is (not (contains? (second (first @calls)) :hit))))
    (testing "resolve-destination ran second, fed the FIRST call's own return value"
      (is (= :raycast (first (second @calls))))
      (is (= {:entity-id nil :position {:x 5.0 :y 6.0 :z 7.0}} (:hit (second (second @calls))))))))

;; --- target/hold-destination --------------------------------------------------

(deftest hold-destination-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [cap args])
                       (if (contains? args :hit) {:type :landing} {:entity-id nil}))
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-hold :activation :instant
               :tunables {:hold-ticks {:type :double} :range-per-hold-tick {:type :double}
                          :maximum-range {:type :double} :available-resource {:type :double}
                          :resource-per-distance {:type :double}}
               :do [(target/hold-destination ?caster/eye ?caster/aim $hold-ticks $range-per-hold-tick
                      $maximum-range $available-resource $resource-per-distance {} true true)
                    (finish {:outcome :performed})]}"
        input {:tunables {:hold-ticks 2.0 :range-per-hold-tick 3.0 :maximum-range 100.0
                          :available-resource 5.0 :resource-per-distance 1.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}]
    (compile-and-dispatch! text host input)
    (testing "growth 3*(2+1)=9, capped by max-range 100 -> 9, then capped by resource 5/1=5 -> 5"
      (is (= 5.0 (:distance (second (first @calls))))))))

;; --- target/penetration-destination -------------------------------------------

(deftest penetration-destination-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [cap args]) {:entity-id nil})
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-pen :activation :instant
               :tunables {:distance {:type :double} :scan-step {:type :double}
                          :clearance-steps {:type :long} :available-resource {:type :double}
                          :resource-per-distance {:type :double} :marker-offset-y {:type :double}}
               :do [(target/penetration-destination ?caster/eye ?caster/aim $distance $scan-step
                      $clearance-steps $available-resource $resource-per-distance $marker-offset-y)
                    (finish {:outcome :performed})]}"
        input {:tunables {:distance 50.0 :scan-step 0.5 :clearance-steps 3 :available-resource 10.0
                          :resource-per-distance 2.0 :marker-offset-y 0.25}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (testing "resource-limited 10/2=5 beats the raw 50 distance cap"
      (let [[cap args] (first @calls)]
        (is (= :raycast cap))
        (is (= 5.0 (:distance args)))
        (is (false? (:include-entities? args)))
        (is (true? (:include-blocks? args)))
        (is (= {:type :penetration :scan-step 0.5 :clearance-steps 3 :marker-offset-y 0.25}
               (:policy args)))))))

;; --- terrain/apply-break-budget -----------------------------------------------

(deftest apply-break-budget-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr]
                       (case cap
                         :block/select [{:hardness 2.0 :position {:x 0.0 :y 0.0 :z 0.0}}
                                       {:hardness 2.0 :position {:x 1.0 :y 0.0 :z 0.0}}
                                       {:hardness 2.0 :position {:x 2.0 :y 0.0 :z 0.0}}]
                         :random/chance (do (swap! calls conj [:chance args]) true)
                         :block/break (do (swap! calls conj [:break args]) nil)))
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-budget :activation :instant
               :tunables {:energy {:type :double} :drop-chance {:type :double}}
               :do [(let blocks (target/blocks {:shape {} :limit 128}))
                    (terrain/apply-break-budget blocks $energy $drop-chance)
                    (finish {:outcome :performed})]}"
        input {:tunables {:energy 5.0 :drop-chance 0.4} :capabilities {}}]
    (compile-and-dispatch! text host input)
    (let [breaks (filter #(= :break (first %)) @calls)
          chances (filter #(= :chance (first %)) @calls)]
      (testing "exactly the first two candidates fit the 5.0 budget (2.0+2.0<=5.0, +2.0 more would not)"
        (is (= 2 (count breaks)))
        (is (= [{:x 0.0 :y 0.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0}]
               (mapv #(:position (second %)) breaks)))
        (is (every? #(true? (:drop? (second %))) breaks)))
      (testing "the chance roll only happens for candidates that actually break"
        (is (= 2 (count chances)))
        (is (every? #(= 0.4 (:probability (second %))) chances))))))

;; --- combat/area-damage --------------------------------------------------------

(deftest area-damage-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) ["e1" "e2"])
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        text "{:ability :t-area :activation :instant
               :tunables {:radius {:type :double} :amount {:type :double}}
               :do [(combat/area-damage ?caster/eye $radius $amount :fire 10)
                    (finish {:outcome :performed})]}"
        input {:tunables {:radius 4.0 :amount 8.0} :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (let [damages (filter #(= :command (first %)) @calls)]
      (is (= [[:command :entity/damage {:target "e1" :amount 8.0 :damage-type :fire}]
             [:command :entity/damage {:target "e2" :amount 8.0 :damage-type :fire}]]
             damages)))))

;; --- combat/beam-strike ---------------------------------------------------------

(deftest beam-strike-test
  (let [calls (atom [])
        host {:query! (fn [_cap _args _fr]
                       {:entities [{:reflection-accepted? true :reflection-target "r1"
                                   :reflection-damage 9.0 :damage-type :electric}
                                  {:reflection-accepted? false :id "d1" :damage 3.0 :damage-type :fire}]})
              :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
        text "{:ability :t-beam :activation :instant
               :tunables {:length {:type :double} :radius {:type :double} :damage {:type :double}}
               :do [(combat/beam-strike ?caster/eye ?caster/eye ?caster/aim $length $length $radius
                      $radius 256 $damage :fire 4096 nil 1.0)
                    (finish {:outcome :performed})]}"
        input {:tunables {:length 32.0 :radius 1.0 :damage 6.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}]
    (compile-and-dispatch! text host input)
    (is (= [[:entity/damage {:target "r1" :amount 9.0 :damage-type :electric}]
           [:entity/damage {:target "d1" :amount 3.0 :damage-type :fire}]]
           @calls))))

(deftest beam-strike-rejects-nil-required-argument-at-compile-time-test
  (let [text "{:ability :t-beam-nil :activation :instant
               :tunables {:length {:type :double} :radius {:type :double} :damage {:type :double}}
               :do [(combat/beam-strike ?caster/eye ?caster/eye ?caster/aim $length nil $radius
                      $radius 256 $damage :fire 4096 nil 1.0)
                    (finish {:outcome :performed})]}" ]
    (try
      (run/compile-doc! text lib/fns)
      (is false "expected a nil beam-strike argument to fail during compilation")
      (catch clojure.lang.ExceptionInfo e
        (is (= :nil-typed-param (:code (ex-data e))))))))

;; --- terrain/break-area ----------------------------------------------------------

(deftest break-area-test
  (let [calls (atom [])
        rolls (atom [true true false])
        host {:query! (fn [cap args _fr]
                       (case cap
                         :block/select [{:position {:x 0.0 :y 0.0 :z 0.0}} {:position {:x 1.0 :y 0.0 :z 0.0}}]
                         :random/chance (let [r (first @rolls)]
                                         (swap! calls conj [:chance (:probability args)])
                                         (swap! rolls rest)
                                         r)
                         :block/break (do (swap! calls conj [:break args]) nil)))
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-break-area :activation :instant
               :tunables {:radius {:type :double} :hardness-max {:type :double}
                          :break-chance {:type :double} :drop-chance {:type :double}}
               :do [(terrain/break-area ?caster/eye $radius $hardness-max $break-chance $drop-chance 64)
                    (finish {:outcome :performed})]}"
        input {:tunables {:radius 3.0 :hardness-max 1.0 :break-chance 0.9 :drop-chance 0.3}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (testing "block0: break-roll true, drop-roll true, breaks. block1: break-roll false, skipped entirely"
      (is (= [[:chance 0.9] [:chance 0.3] [:break {:position {:x 0.0 :y 0.0 :z 0.0} :drop? true}]
             [:chance 0.9]]
             @calls))
      (is (= 4 (count @calls))))))

;; --- terrain/random-break ---------------------------------------------------------

(deftest random-break-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :block/select [{:position {:x 0.0 :y 0.0 :z 0.0}} {:position {:x 1.0 :y 0.0 :z 0.0}}
                                       {:position {:x 2.0 :y 0.0 :z 0.0}}]
                         :block/break nil))
              :command! (fn [_cap _args _fr])}
        text "{:ability :t-random-break :activation :instant
               :tunables {:radius {:type :double} :hardness-max {:type :double}}
               :do [(terrain/random-break ?caster/eye $radius 3 $hardness-max)
                    (finish {:outcome :performed})]}"
        input {:tunables {:radius 5.0 :hardness-max 2.0} :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (testing "attempts (3) flows straight into the query's own :limit"
      (is (= 3 (:limit (nth (first (filter #(= :block/select (second %)) @calls)) 2)))))
    (testing "all 3 fetched candidates break, none held back by a second limit"
      (is (= 3 (count (filter #(= :block/break (second %)) @calls)))))))

;; --- combat/teleport-group ----------------------------------------------------------

(deftest teleport-group-test
  (let [calls (atom [])
        host {:query! (fn [_cap _args _fr] ["e1" "e2"])
              :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
        text "{:ability :t-tp :activation :instant
               :tunables {:radius {:type :double}}
               :do [(combat/teleport-group ?caster/eye $radius 20)
                    (finish {:outcome :performed})]}"
        input {:tunables {:radius 6.0} :capabilities {:caster/eye {:x 2.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (is (= [[:entity/teleport {:target "e1" :position {:x 2.0 :y 0.0 :z 0.0}}]
           [:entity/teleport {:target "e2" :position {:x 2.0 :y 0.0 :z 0.0}}]]
           @calls))))

;; --- motion/radial-impulse ---------------------------------------------------------

(deftest radial-impulse-test
  (let [calls (atom [])
        host {:query! (fn [cap _args _fr]
                       (case cap :entity/select ["e1"] :entity/snapshot {:position {:x 3.0 :y 0.0 :z 0.0}}))
              :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
        text "{:ability :t-radial :activation :instant
               :tunables {:radius {:type :double} :speed {:type :double}}
               :do [(motion/radial-impulse ?caster/eye $radius $speed 10)
                    (finish {:outcome :performed})]}"
        input {:tunables {:radius 8.0 :speed 5.0} :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (testing "direction = normalize((3,0,0)-(0,0,0)) = (1,0,0), push = direction*speed"
      (is (= [[:entity/impulse {:target "e1" :vector {:vec3 [5.0 0.0 0.0]}}]] @calls)))))

;; --- terrain/wave-plan -----------------------------------------------------------

(deftest wave-plan-test
  (let [calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       {:affected-blocks [{:position {:x 1.0 :y 0.0 :z 0.0} :block-id :air}]})
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        text "{:ability :t-wave :activation :instant
               :tunables {:initial-energy {:type :double} :max-iterations {:type :long}
                          :seed {:type :long} :mastery {:type :double} :mastery-threshold {:type :double}
                          :mastery-radius {:type :long} :mastery-hardness-cap {:type :double}
                          :ground-break-probability {:type :double} :drop-probability {:type :double}
                          :launch-base {:type :double} :launch-span {:type :double}
                          :entity-search-radius {:type :double}}
               :do [(let plan (terrain/wave-plan ?caster/eye ?caster/aim $initial-energy $max-iterations
                                $seed {} {} {} $mastery $mastery-threshold $mastery-radius
                                $mastery-hardness-cap $ground-break-probability $drop-probability
                                $launch-base $launch-span $entity-search-radius))
                    (each blk (:affected-blocks plan)
                      (block/set {:position (:position blk) :block-id (:block-id blk)}))
                    (finish {:outcome :performed})]}"
        input {:tunables {:initial-energy 12.0 :max-iterations 40 :seed 7 :mastery 0.5
                          :mastery-threshold 0.2 :mastery-radius 3 :mastery-hardness-cap 2.0
                          :ground-break-probability 0.6 :drop-probability 0.1 :launch-base 0.4
                          :launch-span 0.3 :entity-search-radius 5.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 1.0 :z 0.0}}}]
    (compile-and-dispatch! text host input)
    (testing "the kernel call got the full 17-field passthrough, not scrambled"
      (let [[_ _ args] (first @calls)]
        (is (= 7 (:seed args)))
        (is (= 0.5 (:mastery args)))
        (is (= 3 (:mastery-radius args)))))
    (testing "the returned plan's :affected-blocks list drove the each loop into a real action"
      (is (= [[:command :block/set {:position {:x 1.0 :y 0.0 :z 0.0} :block-id :air}]]
             (filter #(= :command (first %)) @calls))))))
