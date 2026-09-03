(ns cn.li.ac.skills.skills-test
  "S6 proof: every ac/skills/*.edn new-DSL ability conversion actually
   compiles (cn.li.node.surface/parse -> cn.li.node.compile via
   cn.li.combat.run/compile-doc!, against combat-core's REAL vocabulary and
   :defn library) and, where it has any real :do/:phases logic, actually
   dispatches against a fake host -- not just compiles. One deftest per
   converted ability, growing as S6 proceeds; see each ac/skills/*.edn
   file's own docstring for what changed and what stayed on the old
   :mark-policies/:damage-policies path."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cn.li.combat.run :as run]
            [cn.li.combat.lib :as lib]))

(defn- read-skill [filename]
  (let [resource (io/resource (str "ac/skills/" filename))]
    (when-not resource
      (throw (ex-info "missing ac/skills resource" {:filename filename})))
    (read-string (slurp resource))))

(defn- compile-and-dispatch! [doc entry host input]
  (let [ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)]
    (run/dispatch! program entry input)))

(defn- assert-trivial-passive-phases!
  "Shared shape for the 'passive, no real :do logic of its own' abilities:
   all four phases are a bare (finish {:outcome :passive}), dispatched
   against a no-op host, with `tunables` supplying whatever the doc's
   :program declares (empty map for abilities with none)."
  [doc tunables]
  (let [host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables tunables :capabilities {}}]
    (doseq [phase [:start :pulse :release :abort]]
      (let [frame (compile-and-dispatch! doc phase host input)]
        (is (= {:outcome :passive :next-phase nil :end-ability? false} (.-result frame))
            (str "phase " phase))))))

(deftest rad-intensify-test
  (let [doc (read-skill "rad_intensify.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:damage-rate 1.0 :mark-duration-ticks 100 :mastery-denominator 1.0}
              :capabilities {}}]
    (testing "a bare EDN read gets a real self-contained DSL doc, not a broken/empty string"
      (is (= :rad-intensify (:id (run/compile-doc! (:program doc) lib/fns)))))
    (testing "all four phases are wired entry points, each dispatching to a trivial passive finish"
      (doseq [phase [:start :pulse :release :abort]]
        (let [frame (compile-and-dispatch! doc phase host input)]
          (is (= {:outcome :passive :next-phase nil :end-ability? false} (.-result frame))
              (str "phase " phase)))))
    (testing "the legacy policy/metadata fields are untouched, still consumed by the old path"
      (is (= :radiation (:mark-type (first (:mark-policies doc)))))
      (is (= :radiation (:mark-type (first (:damage-policies doc))))))))

(deftest dim-folding-theorem-test
  (let [doc (read-skill "dim_folding_theorem.edn")]
    (assert-trivial-passive-phases!
     doc {:damage-multipliers [1.5 2.0] :level0-probability 0.2 :exp-per-crit-level 5.0})
    (testing "damage-policies (chance-based critical multiplier) is untouched"
      (is (= :damage/critical (:component (:program (first (:damage-policies doc)))))))))

(deftest brain-course-test
  (let [doc (read-skill "brain_course.edn")]
    (assert-trivial-passive-phases! doc {})
    (testing "passive-effects (flat +1000 max-cp) is untouched"
      (is (= [{:target :max-cp :operation :add :value 1000.0}] (:passive-effects doc))))))

(deftest mind-course-test
  (let [doc (read-skill "mind_course.edn")]
    (assert-trivial-passive-phases! doc {})
    (testing "passive-effects (x1.2 cp-recovery-speed) is untouched"
      (is (= [{:target :cp-recovery-speed :operation :multiply :value 1.2}] (:passive-effects doc))))))

(deftest space-fluct-test
  (let [doc (read-skill "space_fluct.edn")]
    (assert-trivial-passive-phases!
     doc {:damage-multipliers [1.5 2.0 3.0] :level0-probability 0.2 :level1-probability 0.1
         :level2-probability 0.05 :exp-critical 5.0})
    (testing "damage-policies (3-level chance-based critical multiplier) is untouched"
      (is (= 3 (count (:levels (:program (first (:damage-policies doc))))))))))

(deftest electron-bomb-test
  (let [doc (read-skill "electron_bomb.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :entity/spawn {:entity-id "ball-uuid"}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:damage 12.0 :cooldown-ticks 40.0 :exp-hit 1.0
                          :settle-ticks 20 :settle-ticks-improved 40 :improved-exp-threshold 0.5}
               :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"
                              :progression/mastery 0.8 :rng/seed 42 :progression/cast 3.0
                              :cooldown/main 40}}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        frame (run/dispatch! program :default input)]
    (testing "mastery 0.8 > threshold 0.5 -> improved settle-ticks (40) used as life-ticks"
      (is (= 40 (:life-ticks (nth (first (filter #(= :entity/spawn (second %)) @calls)) 2)))))
    (testing "entity/spawn got the fixed entity-type string, tags vector, spawn position"
      (let [[_ _ args] (first (filter #(= :entity/spawn (second %)) @calls))]
        (is (= "academy:entity_md_ball" (:entity-type args)))
        (is (= ["ac_electron_bomb"] (:add-tags args)))
        (is (= {:x 0.0 :y 1.0 :z 0.0} (:position args)))))
    (testing "projectile/schedule-beam got the spawned ball's id and delay = life-ticks - 2"
      (let [[_ _ args] (first (filter #(= :projectile/schedule-beam (second %)) @calls))]
        (is (= "ball-uuid" (:entity-id (:origin-selector args))))
        (is (= 38 (:delay-ticks args)))
        (is (= 12.0 (:damage args)))))
    (testing "cooldown/start committed the resolved ?cooldown/main value"
      (is (some #(= [:command :cooldown/start {:name :main :ticks 40}] %) @calls)))
    (testing "score/mark landed on the frame's own event outbox, not a host call"
      (is (= [{:type :score/mark :owner "player-1" :progression 3.0}]
             (mapv #(select-keys % [:type :owner :progression]) (.-events frame)))))))

(deftest mine-detect-test
  (let [doc (read-skill "mine_detect.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:blindness-duration-ticks 60 :blindness-amplifier 1 :targeting-range 32.0
                          :cooldown-endpoints [100.0 20.0] :exp-cast 0.1}
               :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :world/id "overworld" :progression/mastery 0.6 :progression/level 3
                              :rng/seed 7 :progression/cast 2.0
                              :budget/activate {:cp 12.0 :overload 3.0}}}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        frame (run/dispatch! program :default input)]
    (testing "cost/spend queried with the resolved ?budget/activate descriptor, not a bare name"
      (is (= [:query :cost/spend {:budget {:cp 12.0 :overload 3.0}}] (first @calls))))
    (testing "sufficient? true -> combat/status landed on the caster (self-target), not aborted"
      (is (some #(= [:command :entity/status
                    {:target "player-1" :status-id :blindness :duration-ticks 60.0 :amplifier 1}] %)
                @calls)))
    (testing "cooldown-ticks-next = floor(lerp(100.0, 20.0, mastery+exp-cast)) = floor(lerp(100,20,0.7)) = 44"
      (is (some #(= [:command :cooldown/start {:name :main :ticks 44}] %) @calls)))
    (testing "the vfx signal carries the computed :range/:advanced? values, mastery 0.6 > 0.5 but level 3 < 4"
      (let [signal (first (.-vfx frame))]
        (is (= :block-scan-transient (:effect-id signal)))
        (is (= 32.0 (:range (:payload signal))))
        (is (false? (:advanced? (:payload signal))))))
    (testing "the outcome is :performed, not :insufficient-resource"
      (is (= :performed (:outcome (.-result frame)))))))

(deftest location-teleport-test
  (let [doc (read-skill "location_teleport.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :owner/snapshot {:position {:x 0.0 :y 0.0 :z 0.0 :world-id "overworld"}}
                         :saved-location {:x 3.0 :y 4.0 :z 0.0 :world-id "overworld"}
                         :entity/select ["e1"]
                         :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:cross-dimension-exp-threshold 0.5 :teleport-radius 20.0
                          :cp-base [10.0 5.0] :overload 2.0 :cross-dimension-multiplier 1.5
                          :min-distance-multiplier 1.0 :distance-cap 4.0
                          :cooldown-ticks [100.0 20.0] :int-distance-threshold 10.0
                          :exp-short 1.0 :exp-long 5.0}
               :capabilities {:progression/mastery 0.6 :context/location-name :home}}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        frame (run/dispatch! program :default input)]
    (testing "same world-id -> not cross-dimension, so the exp-threshold gate never blocks"
      (is (= :teleported (:outcome (.-result frame)))))
    (testing "cp-cost = lerp(10,5,0.6){=7.0} * 1.0(no cross-dim multiplier) * max(1.0,sqrt(min(4.0,5.0))){=2.0} = 14.0"
      (is (= {:cp 14.0 :overload 2.0} (:budget (nth (first (filter #(= :cost/spend (second %)) @calls)) 2)))))
    (testing "combat/teleport-group (cn.li.combat.lib's composite) actually ran: entities queried around the destination, then teleported"
      (is (= [:query :entity/select {:shape {:type :sphere :center {:x 3.0 :y 4.0 :z 0.0 :world-id "overworld"}
                                             :radius 20.0}
                                    :limit 128}]
             (first (filter #(= :entity/select (second %)) @calls))))
      (is (some #(= [:command :entity/teleport {:target "e1" :position {:x 3.0 :y 4.0 :z 0.0 :world-id "overworld"}}] %)
                @calls)))
    (testing "distance 5.0 < int-distance-threshold 10.0 -> exp-short (1.0), not exp-long"
      (is (some #(= [:command :cooldown/start {:name :main :ticks 52}] %) @calls)))
    (testing "not cross-dimension -> no achievement/trigger event"
      (is (empty? (filter #(= :achievement/trigger (:type %)) (.-events frame)))))
    (testing "the audio vfx signal landed with the resolved destination"
      (is (= :audio-one-shot (:effect-id (first (.-vfx frame)))))
      (is (= {:x 3.0 :y 4.0 :z 0.0 :world-id "overworld"} (:position (:payload (first (.-vfx frame)))))))))

(deftest brain-course-advanced-test
  (let [doc (read-skill "brain_course_advanced.edn")]
    (assert-trivial-passive-phases! doc {})
    (testing "passive-effects (+1500 max-cp, +100 max-overload) is untouched"
      (is (= [{:target :max-cp :operation :add :value 1500.0}
             {:target :max-overload :operation :add :value 100.0}]
             (:passive-effects doc))))))
