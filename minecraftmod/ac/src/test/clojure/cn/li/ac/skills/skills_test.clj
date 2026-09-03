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

(deftest vec-reflection-start-test
  (let [doc (read-skill "vec_reflection.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) (case cap :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {} :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                                           :budget/activate {:overload 5.0}}}
        frame (run/dispatch! program :start input)]
    (is (= [:query :cost/spend {:budget {:overload 5.0}}] (first @calls)))
    (is (= :ring-particle-field (:effect-id (first (.-vfx frame)))))
    (is (= :spawn (:operation (first (.-vfx frame)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest vec-reflection-start-insufficient-resource-test
  (let [doc (read-skill "vec_reflection.edn")
        host {:query! (fn [_cap _args _fr] false) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {} :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                                           :budget/activate {:overload 5.0}}}
        frame (run/dispatch! program :start input)]
    (is (empty? (.-vfx frame)))
    (is (= {:outcome :insufficient-resource :next-phase nil :end-ability? true} (.-result frame)))))

(deftest vec-reflection-pulse-reflects-only-the-positive-difficulty-projectile-test
  (let [doc (read-skill "vec_reflection.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:position {:x 5.0 :y 1.0 :z 0.0}}
                         :entity/select [{:id "p1" :difficulty 2.0 :position {:x 1.0 :y 1.0 :z 0.0}
                                         :velocity {:x 0.0 :y 0.0 :z 1.0}}
                                        {:id "p2" :difficulty 0.0 :position {:x 2.0 :y 1.0 :z 0.0}
                                         :velocity {:x 0.0 :y 0.0 :z 1.0}}]))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:target-radius 8.0 :affected-entity-difficulty [] :excluded-entity-ids []
                          :large-fireball-ids []}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :budget/per-tick {:cp 2.0} :budget/per-reflect {:cp 1.0}
                              :invariant/overload-floor 10.0 :progression/reflect-entity 0.5}
               :state {}}
        frame (run/dispatch! program :pulse input)]
    (testing "resource/enforce-floor ran with the resolved invariant, before any scan"
      (is (some #(= [:command :resource/enforce-floor {:resource :overload :minimum 10.0}] %) @calls)))
    (testing "only the positive-difficulty projectile (p1) triggered a per-reflect spend and redirect
              (2 total cost/spend calls: the per-tick guard at the top of pulse, plus exactly one
              per-reflect spend for p1 -- p2's difficulty 0.0 skips its own reflect-budget spend
              entirely)"
      (is (= 2 (count (filter #(= :cost/spend (second %)) @calls))))
      (is (= 1 (count (filter #(and (= :cost/spend (second %)) (= {:cp 1.0} (:budget (nth % 2)))) @calls))))
      (is (some #(= [:command :projectile/redirect
                    {:entity {:id "p1" :difficulty 2.0 :position {:x 1.0 :y 1.0 :z 0.0}
                             :velocity {:x 0.0 :y 0.0 :z 1.0}}
                     :target-position {:x 5.0 :y 1.0 :z 0.0} :velocity {:x 0.0 :y 0.0 :z 1.0}
                     :difficulty 2.0 :replacement-types []}]
                    %)
                @calls)))
    (testing "p1's id was recorded as a state write (the once-per-projectile dedup)"
      (is (some #(and (= :visited-projectiles (:key %)) (= "p1" (:value %))) (.-stateWrites frame))))
    (testing "the reflect vfx signal only fired once, for p1"
      (is (= 1 (count (.-vfx frame)))))
    (testing "the pulse continues (not an end-ability outcome)"
      (is (= :continue (:outcome (.-result frame)))))))

(deftest vec-reflection-pulse-insufficient-tick-budget-destroys-the-ring-test
  (let [doc (read-skill "vec_reflection.edn")
        calls (atom [])
        host {:query! (fn [_cap _args _fr] false)
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:target-radius 8.0 :affected-entity-difficulty [] :excluded-entity-ids []
                          :large-fireball-ids []}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :budget/per-tick {:cp 2.0}}
               :state {}}
        frame (run/dispatch! program :pulse input)]
    (is (= :ring-particle-field (:effect-id (first (.-vfx frame)))))
    (is (= :destroy (:operation (first (.-vfx frame)))))
    (is (empty? @calls) "no resource/enforce-floor or projectile scan ran -- the tick was aborted first")
    (is (= {:outcome :insufficient-resource :next-phase nil :end-ability? true} (.-result frame)))))

(deftest vec-reflection-release-and-abort-both-destroy-the-ring-test
  (let [doc (read-skill "vec_reflection.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {} :capabilities {}}]
    (let [frame (run/dispatch! program :release input)]
      (is (= :destroy (:operation (first (.-vfx frame)))))
      (is (= :released (:outcome (.-result frame)))))
    (let [frame (run/dispatch! program :abort input)]
      (is (= :destroy (:operation (first (.-vfx frame)))))
      (is (= :aborted (:outcome (.-result frame)))))))

(deftest arc-gen-entity-hit-creeper-charged-test
  (let [doc (read-skill "arc_gen.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id "e1" :entity-type "minecraft:creeper"
                                  :position {:x 1.0 :y 0.0 :z 0.0}}
                         :random/chance true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:damage 10.0 :max-distance 20.0 :ignite-probability 0.1
                          :fishing-probability 0.1 :fishing-exp-threshold 0.5
                          :creeper-charge-chance 0.5 :cooldown-endpoints [100.0 20.0]
                          :exp-entity 0.2 :exp-block 0.1}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :world/id "overworld" :progression/mastery 0.5 :rng/seed 3
                              :budget/activate {:cp 5.0} :progression/hit-entity 1.0
                              :progression/hit-block 1.0}}
        frame (run/dispatch! program :default input)]
    (testing "entity hit -> combat/damage, not the block-impact path"
      (is (some #(= [:command :entity/damage {:target "e1" :amount 10.0 :damage-type :skill}] %) @calls)))
    (testing "creeper + charged roll -> powered-creeper status applied"
      (is (some #(= [:command :entity/status
                    {:target "e1" :status-id :powered-creeper :duration-ticks 1.0 :amplifier 0}] %)
                @calls)))
    (testing "cooldown-ticks-next = floor(lerp(100,20, mastery+exp-entity)) = floor(lerp(100,20,0.7)) = 44"
      (is (some #(= [:command :cooldown/start {:name :main :ticks 44}] %) @calls)))
    (testing "the electromaster.arc_gen achievement event fired"
      (is (some #(and (= :achievement/trigger (:type %)) (= "electromaster.arc_gen" (:id (:payload %))))
                (.-events frame))))
    (is (= :performed (:outcome (.-result frame))))))

(deftest arc-gen-block-hit-test
  (let [doc (read-skill "arc_gen.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id nil :block-position {:x 2.0 :y 0.0 :z 0.0}
                                  :position {:x 2.0 :y 0.0 :z 0.0} :water? true}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:damage 10.0 :max-distance 20.0 :ignite-probability 0.1
                          :fishing-probability 0.1 :fishing-exp-threshold 0.5
                          :creeper-charge-chance 0.5 :cooldown-endpoints [100.0 20.0]
                          :exp-entity 0.2 :exp-block 0.1}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :world/id "overworld" :progression/mastery 0.5 :rng/seed 3
                              :budget/activate {:cp 5.0} :progression/hit-entity 1.0
                              :progression/hit-block 1.0}}
        frame (run/dispatch! program :default input)]
    (testing "no entity -> no combat/damage or creeper-charge roll, no random/chance query at all"
      (is (not (some #(= :entity/damage (second %)) @calls)))
      (is (not (some #(= :random/chance (second %)) @calls))))
    (testing "the block-impact event carries the resolved fields, not raw refs"
      (is (some #(and (= :world/block-impact (:type %)) (true? (:water? (:payload %)))
                      (= "overworld" (:world-id (:payload %))))
                (.-events frame))))
    (testing "cooldown-ticks-next = floor(lerp(100,20, mastery+exp-block)) = floor(lerp(100,20,0.6)) = 52"
      (is (some #(= [:command :cooldown/start {:name :main :ticks 52}] %) @calls)))
    (is (= :performed (:outcome (.-result frame))))))

(deftest vec-deviation-start-writes-overload-floor-test
  (let [doc (read-skill "vec_deviation.edn")
        host {:query! (fn [_cap _args _fr] true) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:activation-overload 5.0}
               :capabilities {:budget/activate {:overload 5.0} :context/resources {:overload 20.0}}}
        frame (run/dispatch! program :start input)]
    (is (= [{:key :overload-floor :value 15.0}] (vec (.-stateWrites frame))))
    (is (= :started (:outcome (.-result frame))))))

(deftest vec-deviation-pulse-routes-each-projectile-type-test
  (let [doc (read-skill "vec_deviation.edn")
        calls (atom [])
        p1 {:id "large1" :type "large_fireball" :item? false :living? false :mob? false
           :multipart? false :difficulty 1.0 :explosion-power 0.0 :position {:x 1.0 :y 0.0 :z 0.0}}
        p2 {:id "small1" :type "small_fireball" :item? false :living? false :mob? false
           :multipart? false :difficulty 1.0 :position {:x 2.0 :y 0.0 :z 0.0}}
        p3 {:id "normal1" :type "arrow" :item? false :living? false :mob? false :multipart? false
           :difficulty 1.0 :position {:x 3.0 :y 0.0 :z 0.0} :velocity {:x 0.0 :y 0.0 :z 1.0}}
        p4 {:id "living1" :type "zombie" :item? false :living? true :mob? true :multipart? false
           :difficulty 1.0}
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend true :entity/select [p1 p2 p3 p4]))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:target-radius 10.0 :affected-entity-difficulty [] :excluded-entity-ids []
                          :excluded-tags [] :large-fireball-ids ["large_fireball"]
                          :small-fireball-ids ["small_fireball"] :fireball-explosion-radius 3.0}
               :capabilities {:caster/body {:x 0.0 :y 0.0 :z 0.0} :world/id "overworld"
                              :invariant/overload-floor 2.0 :budget/scan {:cp 1.0}
                              :budget/deflect {:cp 0.5} :budget/normal-tick {:cp 1.0 :overload 1.0}
                              :progression/deflect 1.0}
               :state {}}
        frame (run/dispatch! program :pulse input)]
    (testing "the living/mob projectile (p4) never got deflect-checked at all"
      (is (= 5 (count (filter #(= :cost/spend (second %)) @calls)))
          "scan + 3 deflects + normal-tick, not 6"))
    (testing "large fireball: discarded and exploded with its own explosion-power ignored (0.0), falling back to the tunable radius"
      (is (some #(= [:command :entity/discard {:entity p1 :world-id "overworld"}] %) @calls))
      (is (some #(= [:command :world/explosion
                    {:world-id "overworld" :position {:x 1.0 :y 0.0 :z 0.0} :radius 3.0 :fire? true}]
                    %)
                @calls)))
    (testing "small fireball: discarded, no explosion"
      (is (some #(= [:command :entity/discard {:entity p2 :world-id "overworld"}] %) @calls))
      (is (not (some #(and (= :world/explosion (second %))
                          (= {:x 2.0 :y 0.0 :z 0.0} (:position (nth % 2))))
                     @calls))))
    (testing "normal projectile: velocity zeroed and tagged, not discarded"
      (is (some #(= [:command :entity/configure
                    {:world-id "overworld" :entity p3 :velocity {:vec3 [0.0 0.0 0.0]}
                     :projectile-damage 0.0 :add-tags ["ac_vm_deviated"]}]
                    %)
                @calls))
      (is (not (some #(= [:command :entity/discard {:entity p3 :world-id "overworld"}] %) @calls)))
      (is (some #(= :ring-fade-audio (:effect-id %)) (.-vfx frame))))
    (testing "living/mob projectile: no discard, no configure, no explosion at all"
      (is (not (some #(= p4 (:entity (nth % 2))) (filter #(= :command (first %)) @calls)))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest ray-barrage-scatters-when-an-unhit-silbarn-is-nearby-test
  (let [doc (read-skill "ray_barrage.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id "silbarn-e1" :position {:x 5.0 :y 1.0 :z 0.0}}
                         :entity/select (if (contains? (:filter args) :entity-types)
                                         [{:id "silbarn-e1" :type "academy:entity_silbarn"
                                          :position {:x 5.0 :y 1.0 :z 0.0} :behavior-hit? false}]
                                         [{:id "t1"} {:id "t2"}])
                         :random/int 2))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:targeting-range 30.0 :scatter-cone-angle 15.0 :plain-damage 8.0
                          :scattered-damage 3.0}
               :capabilities {:caster/id "player-1" :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :world/id "overworld" :rng/seed 9 :budget/fire {:cp 2.0}
                              :progression/hit 1.0 :cooldown/main 60}}
        frame (run/dispatch! program :start input)]
    (testing "the silbarn's dormant behavior was triggered, not damaged directly"
      (is (some #(= [:command :entity/trigger-behavior
                    {:entity {:id "silbarn-e1" :type "academy:entity_silbarn"
                             :position {:x 5.0 :y 1.0 :z 0.0} :behavior-hit? false}}] %)
                @calls)))
    (testing "both scatter targets took scattered-damage and a radiation mark, the primary target did not"
      (is (= 2 (count (filter #(= :entity/damage (second %)) @calls))))
      (is (every? #(= {:amount 3.0 :damage-type :magic} (select-keys (nth % 2) [:amount :damage-type]))
                  (filter #(= :entity/damage (second %)) @calls)))
      (is (= 2 (count (filter #(= :entity/mark (second %)) @calls)))))
    (testing "the fan vfx count is 25 + the rolled random/int (2) = 27"
      (is (= 27 (:count (:payload (some #(when (= :ray-fan-transient (:effect-id %)) %) (.-vfx frame)))))))
    (testing "cooldown committed the resolved ?cooldown/main value, and the outcome is :performed"
      (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
      (is (= :performed (:outcome (.-result frame)))))))

(deftest ray-barrage-plain-hit-when-no-silbarn-nearby-test
  (let [doc (read-skill "ray_barrage.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id "e2" :position {:x 5.0 :y 1.0 :z 0.0}}
                         :entity/select []))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:targeting-range 30.0 :scatter-cone-angle 15.0 :plain-damage 8.0
                          :scattered-damage 3.0}
               :capabilities {:caster/id "player-1" :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :world/id "overworld" :rng/seed 9 :budget/fire {:cp 2.0}
                              :progression/hit 1.0 :cooldown/main 60}}
        frame (run/dispatch! program :start input)]
    (testing "no silbarn nearby -> plain damage on the aim target only, no scatter query or random roll"
      (is (some #(= [:command :entity/damage {:target "e2" :amount 8.0 :damage-type :magic}] %) @calls))
      (is (not (some #(= :entity/trigger-behavior (second %)) @calls)))
      (is (not (some #(= :random/int (second %)) @calls)))
      (is (not (some #(and (= :entity/select (second %)) (contains? (:filter (nth % 2)) :excluded-entity-ids))
                     @calls))))
    (is (= :performed (:outcome (.-result frame))))))

(deftest thunder-bolt-direct-and-aoe-targets-test
  (let [doc (read-skill "thunder_bolt.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id "d1" :entity-type "minecraft:zombie"
                                  :position {:x 5.0 :y 1.0 :z 0.0}}
                         :entity/select (if (contains? (:filter args) :entity-ids)
                                         [{:id "d1" :type "minecraft:zombie" :living? true}]
                                         [{:id "a1" :type "minecraft:creeper" :living? true}])
                         :random/chance true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:targeting-range 20.0 :direct-damage 12.0 :aoe-radius 6.0 :aoe-damage 4.0
                          :slowness-chance 0.3 :slowness-exp-threshold 0.5
                          :slowness-duration-ticks 40 :slowness-aoe-retry-duration-ticks 20
                          :slowness-amplifier 1 :creeper-charge-chance 0.5}
               :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 1.0 :y 0.0 :z 0.0} :world/id "overworld"
                              :progression/mastery 0.9 :budget/fire {:cp 2.0}
                              :progression/effective 1.0 :progression/ineffective 0.5
                              :cooldown/main 80}}
        frame (run/dispatch! program :default input)]
    (testing "both the direct target and the aoe target took damage"
      (is (some #(= [:command :entity/damage {:target "d1" :amount 12.0 :damage-type :skill
                                              :damage-pipeline :skill}] %)
                @calls))
      (is (some #(= [:command :entity/damage {:target "a1" :amount 4.0 :damage-type :skill
                                              :damage-pipeline :skill}] %)
                @calls)))
    (testing "the direct target (not a creeper) got slowed once from the direct-hit check
              (duration-ticks arrives as 40.0, not 40: combat/status's :duration-ticks is
              :double, so the :long tunable literal gets widened at compile time)"
      (is (some #(= [:command :entity/status {:target "d1" :status-id :slowness
                                              :duration-ticks 40.0 :amplifier 1}] %)
                @calls)))
    (testing "the aoe target (a creeper) got powered, and the direct target got slowed AGAIN
              with the aoe-retry duration -- the old content's own odd but faithfully-ported
              behavior: the aoe loop's slowness re-check always targets the direct target, not
              the aoe-loop target itself"
      (is (some #(= [:command :entity/status {:target "a1" :status-id :powered-creeper
                                              :duration-ticks 1.0 :amplifier 0}] %)
                @calls))
      (is (some #(= [:command :entity/status {:target "d1" :status-id :slowness
                                              :duration-ticks 20.0 :amplifier 1}] %)
                @calls)))
    (testing "lightning struck, the arc vfx carries the resolved aoe-targets list, cooldown committed"
      (is (some #(= :world/lightning (second %)) @calls))
      (is (= [{:id "a1" :type "minecraft:creeper" :living? true}]
             (:aoe-points (:payload (first (.-vfx frame))))))
      (is (some #(= [:command :cooldown/start {:name :main :ticks 80}] %) @calls)))
    (testing "at least one real target -> :effective progression, not :ineffective"
      (is (some #(and (= :score/mark (:type %)) (= :effective (:tag %))) (.-events frame))))
    (is (= {:outcome :performed :next-phase nil :end-ability? true} (.-result frame)))))

(deftest vec-accel-start-test
  (let [doc (read-skill "vec_accel.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {} :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                                           :caster/aim {:x 1.0 :y 0.0 :z 0.0}}}
        frame (run/dispatch! program :start input)]
    (is (= #{[:charge-ticks 0] [:can-perform? true] [:look-dir {:x 1.0 :y 0.0 :z 0.0}]
             [:init-vel {:vec3 [0.0 0.0 0.0]}]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :spawn (:operation (first (.-vfx frame)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest vec-accel-pulse-charges-and-computes-a-launch-velocity-test
  (let [doc (read-skill "vec_accel.edn")
        host {:query! (fn [_cap _args _fr] {:block-position {:x 0.0 :y -1.0 :z 0.0}})
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:ground-check-distance 2.0 :groundless-exp-threshold 0.9
                          :speed-progress [0.0 1.0] :max-charge-ticks 10 :max-velocity 2.0
                          :pitch-offset-radians 0.0}
               :capabilities {:caster/body {:x 0.0 :y 0.0 :z 0.0} :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 1.0 :y 0.0 :z 0.0} :progression/mastery 0.1}
               :state {:charge-ticks 5}}
        frame (run/dispatch! program :pulse input)
        writes (into {} (map (juxt :key :value)) (.-stateWrites frame))
        expected-speed (* (Math/sin 0.6) 2.0)]
    (testing "the ground hit alone makes can-perform? true even though mastery is below the groundless threshold"
      (is (true? (:can-perform? writes))))
    (testing "charge-ticks incremented from the carried-forward state (5 -> 6)"
      (is (= 6 (:charge-ticks writes))))
    (testing "init-vel = vec3/launch(aim, sin(lerp(0,1,6/10))*2.0, 0.0), level aim -> all speed on x"
      (let [[x y z] (:vec3 (:init-vel writes))]
        (is (< (Math/abs (- x expected-speed)) 1.0e-9))
        (is (< (Math/abs y) 1.0e-9))
        (is (< (Math/abs z) 1.0e-9))))
    (is (= :charging (:outcome (.-result frame))))))

(deftest vec-accel-release-launches-when-performable-and-affordable-test
  (let [doc (read-skill "vec_accel.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) (case cap :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:release-cp 3.0 :release-overload 1.0 :cooldown-ticks 60}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :progression/launch 1.0}
               :state {:can-perform? true :init-vel {:vec3 [1.0 0.0 0.0]}}}
        frame (run/dispatch! program :release input)]
    (is (= [:query :cost/spend {:budget {:cp 3.0 :overload 1.0}}] (first @calls)))
    (is (some #(= [:command :motion/velocity {:velocity {:vec3 [1.0 0.0 0.0]} :dismount? true
                                              :reset-fall-damage? true}] %)
              @calls))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
    (is (some #(and (= :trajectory-ribbon-session (:effect-id %)) (= :destroy (:operation %)))
              (.-vfx frame)))
    (is (= :launched (:outcome (.-result frame))))))

(deftest vec-accel-release-not-performable-skips-spend-entirely-test
  (let [doc (read-skill "vec_accel.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) true)
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:release-cp 3.0 :release-overload 1.0 :cooldown-ticks 60}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :progression/launch 1.0}
               :state {:can-perform? false :init-vel {:vec3 [1.0 0.0 0.0]}}}
        frame (run/dispatch! program :release input)]
    (is (empty? @calls))
    (is (= :destroy (:operation (first (.-vfx frame)))))
    (is (= :not-performable (:outcome (.-result frame))))))

(defn- mark-teleport-host [{:keys [dest]}]
  {:query! (fn [cap args _fr]
            (case cap
              :raycast (if (contains? args :hit) dest {:entity-id nil})
              :cost/spend true))
   :command! (fn [_cap _args _fr])})

(def ^:private mark-teleport-input
  {:tunables {:minimum-distance 2.0 :maximum-range 50.0 :range-per-hold-tick 2.0
             :cp-per-block 1.0 :release-overload 1.0 :entity-eye-height 1.6}
   :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                  :caster/aim {:x 1.0 :y 0.0 :z 0.0} :world/id "overworld" :charge/ticks 5
                  :context/resources {:cp 20.0} :progression/teleport 1.0 :cooldown/main 60}
   :state {}})

(deftest mark-teleport-start-spawns-a-marker-at-a-valid-destination-test
  (let [doc (read-skill "mark_teleport.edn")
        dest {:valid? true :position {:x 12.0 :y 1.0 :z 0.0} :distance 12.0 :world-id "overworld"}
        host (mark-teleport-host {:dest dest})
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        frame (run/dispatch! program :start mark-teleport-input)]
    (is (= [{:key :destination :value dest}] (vec (.-stateWrites frame))))
    (is (= :teleport-marker (:effect-id (first (.-vfx frame)))))
    (is (= "player-1" (:owner (:payload (first (.-vfx frame))))))
    (is (= {:x 12.0 :y 1.0 :z 0.0} (:position (:payload (first (.-vfx frame))))))
    (is (= :started (:outcome (.-result frame))))))

(deftest mark-teleport-release-teleports-when-valid-and-affordable-test
  (let [doc (read-skill "mark_teleport.edn")
        dest {:valid? true :position {:x 12.0 :y 1.0 :z 0.0} :distance 12.0 :world-id "overworld"}
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc mark-teleport-input :state {:destination dest})
        frame (run/dispatch! program :release input)]
    (testing "cp-cost = distance(12.0) * cp-per-block(1.0) = 12.0"
      (is (= [:query :cost/spend {:budget {:cp 12.0 :overload 1.0}}] (first @calls))))
    (is (some #(= [:command :entity/teleport {:target "player-1" :position {:x 12.0 :y 1.0 :z 0.0}
                                              :dismount? true :reset-fall-damage? true}] %)
              @calls))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
    (is (some #(and (= :teleport-marker (:effect-id %)) (= :destroy (:operation %))) (.-vfx frame)))
    (is (= :teleported (:outcome (.-result frame))))))

(deftest mark-teleport-release-too-close-skips-spend-entirely-test
  (let [doc (read-skill "mark_teleport.edn")
        dest {:valid? false}
        calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) true)
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc mark-teleport-input :state {:destination dest})
        frame (run/dispatch! program :release input)]
    (is (empty? @calls))
    (is (some #(and (= :teleport-marker (:effect-id %)) (= :destroy (:operation %))) (.-vfx frame)))
    (is (= :too-close (:outcome (.-result frame))))))

(def ^:private flesh-ripping-attacked-trace
  {:position {:x 5.0 :y 1.0 :z 0.0} :attacked? true :target-width 0.8 :target-height 1.8
   :target-id "e1"})

(deftest flesh-ripping-start-spawns-a-target-box-test
  (let [doc (read-skill "flesh_ripping.edn")
        host {:query! (fn [_cap _args _fr] flesh-ripping-attacked-trace)
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:targeting-range 20.0} :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                                                                 :caster/aim {:x 1.0 :y 0.0 :z 0.0}}}
        frame (run/dispatch! program :start input)]
    (is (= [{:key :trace :value flesh-ripping-attacked-trace}] (vec (.-stateWrites frame))))
    (let [signal (first (.-vfx frame))]
      (is (= :target-box-session (:effect-id signal)))
      (is (= 0.8 (:width (:payload signal))))
      (is (= 1.8 (:height (:payload signal))))
      (is (= [185 25 25 180] (:color (:payload signal)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest flesh-ripping-release-hits-when-attacked-and-affordable-test
  (let [doc (read-skill "flesh_ripping.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend true :random/chance true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:damage 15.0 :nausea-chance 0.5 :nausea-duration-ticks 40
                          :nausea-amplifier 1}
               :capabilities {:caster/id "player-1" :caster/creative? false
                              :budget/release {:cp 5.0} :progression/hit 1.0 :cooldown/main 80}
               :state {:trace flesh-ripping-attacked-trace}}
        frame (run/dispatch! program :release input)]
    (testing "not creative -> spend-scale 1.0"
      (is (= [:query :cost/spend {:budget {:cp 5.0} :scale 1.0}] (first @calls))))
    (is (some #(= [:command :entity/damage {:target "e1" :amount 15.0 :damage-type :magic}] %) @calls))
    (is (some #(= [:command :entity/status {:target "player-1" :status-id :nausea
                                            :duration-ticks 40.0 :amplifier 1}] %)
              @calls))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 80}] %) @calls))
    (is (= #{:particle-burst :audio-one-shot :target-box-session} (set (map :effect-id (.-vfx frame)))))
    (is (= :performed (:outcome (.-result frame))))))

(deftest flesh-ripping-release-misses-when-not-attacked-test
  (let [doc (read-skill "flesh_ripping.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) true)
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:damage 15.0 :nausea-chance 0.5 :nausea-duration-ticks 40
                          :nausea-amplifier 1}
               :capabilities {:caster/id "player-1" :caster/creative? false}
               :state {:trace (assoc flesh-ripping-attacked-trace :attacked? false)}}
        frame (run/dispatch! program :release input)]
    (is (empty? @calls) "no cost/spend, no damage -- attacked? false short-circuits everything")
    (is (= #{:audio-one-shot :target-box-session} (set (map :effect-id (.-vfx frame)))))
    (is (= :miss (:outcome (.-result frame))))))

(def ^:private thunder-clap-input
  {:tunables {:targeting-range 20.0 :charge-min 2 :charge-max 10 :damage 10.0
             :overcharge-multiplier [1.0 3.0] :aoe-radius 6.0}
   :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                  :caster/aim {:x 1.0 :y 0.0 :z 0.0} :world/id "overworld"
                  :progression/cast 1.0 :cooldown/main 100}})

(deftest thunder-clap-pulse-discharges-at-full-charge-test
  (let [doc (read-skill "thunder_clap.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :raycast {:position {:x 5.0 :y 1.0 :z 0.0}}
                             :entity/select [{:id "e1"} {:id "e2"}]))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc-in thunder-clap-input [:capabilities :charge/ticks] 10)
        frame (run/dispatch! program :pulse input)]
    (testing "overcharge-ratio = clamp((10-2)/(10-2))=1.0 -> damage = 10.0 * lerp(1.0,3.0,1.0) = 30.0"
      (is (= 2 (count (filter #(= :entity/damage (second %)) @calls))))
      (is (every? #(= {:amount 30.0 :damage-type :skill} (select-keys (nth % 2) [:amount :damage-type]))
                  (filter #(= :entity/damage (second %)) @calls))))
    (is (some #(= :world/lightning (second %)) @calls))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 100}] %) @calls))
    (is (some #(and (= :achievement/trigger (:type %))
                    (= "electromaster.thunder_clap" (:id (:payload %))))
              (.-events frame)))
    (is (= :performed (:outcome (.-result frame))))))

(deftest thunder-clap-pulse-cost-failed-exactly-at-min-jumps-to-release-test
  (let [doc (read-skill "thunder_clap.edn")
        host {:query! (fn [_cap _args _fr]
                       (case _cap :raycast {:position {:x 5.0 :y 1.0 :z 0.0}} :cost/spend false))
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc-in thunder-clap-input [:capabilities :charge/ticks] 2)
        frame (run/dispatch! program :pulse input)]
    (is (= {:outcome :cost-failed-at-min :next-phase :release :end-ability? false} (.-result frame)))))

(deftest thunder-clap-pulse-cost-failed-below-min-aborts-test
  (let [doc (read-skill "thunder_clap.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap :raycast {:position {:x 5.0 :y 1.0 :z 0.0}} :cost/spend false))
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc-in thunder-clap-input [:capabilities :charge/ticks] 1)
        frame (run/dispatch! program :pulse input)]
    (testing "the unconditional ring :update fires first, then the abort-only :destroy"
      (is (= [:update :destroy] (mapv :operation (.-vfx frame)))))
    (is (= {:outcome :insufficient-resource :next-phase nil :end-ability? true} (.-result frame)))))

(deftest thunder-clap-pulse-still-charging-continues-when-affordable-test
  (let [doc (read-skill "thunder_clap.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap :raycast {:position {:x 5.0 :y 1.0 :z 0.0}} :cost/spend true))
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc-in thunder-clap-input [:capabilities :charge/ticks] 1)
        frame (run/dispatch! program :pulse input)]
    (is (= {:outcome :continue :next-phase nil :end-ability? false} (.-result frame)))))

(deftest thunder-clap-release-undercharged-does-nothing-test
  (let [doc (read-skill "thunder_clap.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args]) (case cap :raycast {:position {:x 5.0 :y 1.0 :z 0.0}}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input (assoc-in thunder-clap-input [:capabilities :charge/ticks] 1)
        frame (run/dispatch! program :release input)]
    (is (not (some #(= :entity/damage (second %)) @calls)))
    (is (= :destroy (:operation (first (.-vfx frame)))))
    (is (= :undercharged (:outcome (.-result frame))))))

(def ^:private threatening-teleport-hand-item {:present? true :item-id "academy:needle"})
(def ^:private threatening-teleport-hit-trace
  {:position {:x 5.0 :y 1.0 :z 0.0} :attacked? true :target-width 0.6 :target-height 1.8
   :target-id "e1" :drop-position {:x 5.0 :y 0.0 :z 0.0}})

(deftest threatening-teleport-start-with-item-spawns-marker-test
  (let [doc (read-skill "threatening_teleport.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap :item/held threatening-teleport-hand-item
                             :raycast threatening-teleport-hit-trace))
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:maximum-range 20.0} :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                                                               :caster/aim {:x 1.0 :y 0.0 :z 0.0}}}
        frame (run/dispatch! program :start input)]
    (is (= #{[:hand-item threatening-teleport-hand-item] [:trace threatening-teleport-hit-trace]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (let [signal (first (.-vfx frame))]
      (is (= 0.6 (:width (:payload signal))))
      (is (= 1.8 (:height (:payload signal))))
      (is (= [186 178 35 42] (:color (:payload signal)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest threatening-teleport-release-hits-with-a-needle-and-drops-the-item-test
  (let [doc (read-skill "threatening_teleport.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :item/held threatening-teleport-hand-item
                         :cost/spend true
                         :random/chance true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:damage 10.0 :needle-damage-multiplier 2.0 :drop-prob-hit 0.2
                          :drop-prob-miss 0.05}
               :capabilities {:caster/body {:x 0.0 :y 1.0 :z 0.0} :caster/creative? false
                              :budget/release {:cp 5.0} :progression/hit 1.0
                              :progression/miss 0.5 :cooldown/main 60}
               :state {:trace threatening-teleport-hit-trace}}
        frame (run/dispatch! program :release input)]
    (testing "needle multiplier applied: 10.0 * 2.0 = 20.0"
      (is (some #(= [:command :entity/damage {:target "e1" :amount 20.0 :damage-type :magic}] %)
                @calls)))
    (testing "drop-prob-hit (0.2) used since the trace was a hit, and the item settles at the drop position"
      (is (some #(= [:query :random/chance {:probability 0.2}] %) @calls))
      (is (some #(= [:command :inventory/settle {:source :main-hand :count 1
                                                 :position {:x 5.0 :y 0.0 :z 0.0} :drop? true
                                                 :creative? false}] %)
                @calls)))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
    (is (some #(and (= :achievement/trigger (:type %))
                    (= "teleporter.threatening_teleport" (:id (:payload %))))
              (.-events frame)))
    (is (= #{:target-box-session :teleport-trail-transient} (set (map :effect-id (.-vfx frame)))))
    (is (= :performed (:outcome (.-result frame))))))

(deftest threatening-teleport-release-no-item-skips-everything-test
  (let [doc (read-skill "threatening_teleport.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :item/held {:present? false} :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:damage 10.0 :needle-damage-multiplier 2.0 :drop-prob-hit 0.2
                          :drop-prob-miss 0.05}
               :capabilities {:caster/body {:x 0.0 :y 1.0 :z 0.0} :caster/creative? false}
               :state {:trace threatening-teleport-hit-trace}}
        frame (run/dispatch! program :release input)]
    (is (not (some #(= :cost/spend (second %)) @calls)))
    (is (= :destroy (:operation (first (.-vfx frame)))))
    (is (= :no-item (:outcome (.-result frame))))))

(deftest groundshock-start-and-pulse-track-charge-ticks-test
  (let [doc (read-skill "groundshock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        start-frame (run/dispatch! program :start {:tunables {} :capabilities {}})
        pulse-frame (run/dispatch! program :pulse
                                   {:tunables {:charge-max-tolerant-ticks 40}
                                    :capabilities {} :state {:charge-ticks 5}})]
    (is (= [{:key :charge-ticks :value 0}] (vec (.-stateWrites start-frame))))
    (is (= :started (:outcome (.-result start-frame))))
    (is (= [{:key :charge-ticks :value 6}] (vec (.-stateWrites pulse-frame))))
    (is (= 6 (:phase-ticks (:payload (first (.-vfx pulse-frame))))))
    (is (= :charging (:outcome (.-result pulse-frame))))))

(deftest groundshock-pulse-times-out-when-held-too-long-test
  (let [doc (read-skill "groundshock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        frame (run/dispatch! program :pulse {:tunables {:charge-max-tolerant-ticks 10}
                                             :capabilities {} :state {:charge-ticks 9}})]
    (testing "the unconditional phase-ticks :update fires first, then the timeout :destroy"
      (is (= [:update :destroy] (mapv :operation (.-vfx frame)))))
    (is (= {:outcome :aborted :next-phase nil :end-ability? true} (.-result frame)))))

(deftest groundshock-release-executes-the-wave-plan-test
  (let [doc (read-skill "groundshock.edn")
        calls (atom [])
        plan {:affected-blocks [{:position {:x 0.0 :y 0.0 :z 0.0}}]
             :transforms [{:position {:x 1.0 :y 0.0 :z 0.0} :block-id "minecraft:cobblestone"
                          :expected-block-ids ["minecraft:stone"]}]
             :broken-blocks [{:position {:x 2.0 :y 0.0 :z 0.0} :drop? true}]
             :mastery-breaks [{:position {:x 3.0 :y 0.0 :z 0.0}}]
             :entities [{:id "e1" :velocity {:x 0.1 :y 0.2 :z 0.3} :launch-y 0.9}]}
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :owner/snapshot {:on-ground? true}
                         :cost/spend true
                         :kernel/terrain-wave-plan plan
                         :block/break nil))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:charge-min-ticks 10 :initial-energy 100.0 :max-iterations 200
                          :entity-search-radius 6.0 :damage 12.0 :launch-base 0.4
                          :launch-span 0.3 :ground-break-probability 0.5 :drop-probability 0.2
                          :energy-cost-stone 1.0 :energy-cost-grass 0.5 :energy-cost-farmland 0.5
                          :energy-cost-default 2.0 :mastery-exp-threshold 0.5 :mastery-radius 3
                          :mastery-hardness-cap 2.0}
               :capabilities {:caster/body {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :world/id "overworld" :rng/seed 5 :progression/mastery 0.5
                              :budget/release {:cp 5.0 :overload 2.0} :progression/hit 1.0
                              :progression/use 1.0 :cooldown/main 80}
               :state {:charge-ticks 20}}
        frame (run/dispatch! program :release input)]
    (testing "the plan's transforms/broken/mastery blocks each drove their own host action"
      (is (some #(= [:command :block/set {:position {:x 1.0 :y 0.0 :z 0.0}
                                          :block-id "minecraft:cobblestone"
                                          :expected-block-ids ["minecraft:stone"]}] %)
                @calls))
      (is (some #(= [:query :block/break {:position {:x 2.0 :y 0.0 :z 0.0} :drop? true}] %) @calls))
      (is (some #(= [:query :block/break {:position {:x 3.0 :y 0.0 :z 0.0} :drop? true}] %) @calls)))
    (testing "the launched entity took damage and its velocity was rebuilt from :launch-y"
      (is (some #(= [:command :entity/damage {:target "e1" :amount 12.0 :damage-type :skill
                                              :damage-pipeline :skill}] %)
                @calls))
      (is (some #(= [:command :motion/entity-velocity {:target "e1" :velocity [0.1 0.9 0.3]}] %)
                @calls)))
    (testing "exactly one release spend -- the old content's double-spend bug is not reproduced"
      (is (= 1 (count (filter #(= :cost/spend (second %)) @calls)))))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 80}] %) @calls))
    (is (= #{:first-person-motion-session :terrain-shockwave-transient}
           (set (map :effect-id (.-vfx frame)))))
    (is (= :performed (:outcome (.-result frame))))))

(def ^:private penetrate-teleport-available-dest
  {:available? true :distance 10.0 :marker-position {:x 1.0 :y 1.0 :z 1.0}
   :position {:x 1.0 :y 1.0 :z 1.0}})

(deftest penetrate-teleport-start-test
  (let [doc (read-skill "penetrate_teleport.edn")
        host {:query! (fn [_cap _args _fr] penetrate-teleport-available-dest)
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:max-distance 15.0 :scan-step 0.5 :cp-per-block 1.0}
               :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 1.0 :y 0.0 :z 0.0} :context/resources {:cp 20.0}}}
        frame (run/dispatch! program :start input)]
    (is (= #{[:desired-distance 15.0] [:destination penetrate-teleport-available-dest]
             [:can-teleport? true]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (let [signal (first (.-vfx frame))]
      (is (= [255 255 255 255] (:color (:payload signal))))
      (is (= 0.4 (:particle-chance (:payload signal)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest penetrate-teleport-release-teleports-when-available-and-affordable-test
  (let [doc (read-skill "penetrate_teleport.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :raycast penetrate-teleport-available-dest :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:scan-step 0.5 :cp-per-block 1.0 :release-overload 1.0}
               :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 1.0 :y 0.0 :z 0.0} :context/resources {:cp 20.0}
                              :progression/teleport 1.0 :cooldown/main 60}
               :state {:desired-distance 10.0}}
        frame (run/dispatch! program :release input)]
    (testing "cp-cost = distance(10.0) * cp-per-block(1.0) = 10.0"
      (is (some #(= [:query :cost/spend {:budget {:cp 10.0 :overload 1.0}}] %) @calls)))
    (is (some #(= [:command :entity/teleport {:target "player-1" :position {:x 1.0 :y 1.0 :z 1.0}
                                              :dismount? true :reset-fall-damage? true}] %)
              @calls))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
    (is (= :teleported (:outcome (.-result frame))))))

(deftest penetrate-teleport-release-unavailable-skips-spend-test
  (let [doc (read-skill "penetrate_teleport.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :raycast (assoc penetrate-teleport-available-dest :available? false)
                             :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:scan-step 0.5 :cp-per-block 1.0 :release-overload 1.0}
               :capabilities {:caster/id "player-1" :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 1.0 :y 0.0 :z 0.0} :context/resources {:cp 20.0}}
               :state {:desired-distance 10.0}}
        frame (run/dispatch! program :release input)]
    (is (not (some #(= :cost/spend (second %)) @calls)))
    (is (= :unavailable (:outcome (.-result frame))))))

(deftest penetrate-teleport-slot-wheel-adjusts-and-clamps-distance-test
  (let [doc (read-skill "penetrate_teleport.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:max-distance 10.0} :capabilities {:context/delta 2.0}
               :state {:desired-distance 5.0}}
        frame (run/dispatch! program :slot-wheel input)]
    (is (= [{:key :desired-distance :value 7.0}] (vec (.-stateWrites frame))))
    (is (= :distance-updated (:outcome (.-result frame))))))

(deftest directed-blastwave-pulse-charges-and-detects-punch-completion-test
  (let [doc (read-skill "directed_blastwave.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        charging-frame (run/dispatch! program :pulse
                                      {:tunables {:charge-max-accepted-ticks 20
                                                  :charge-max-tolerant-ticks 40
                                                  :punch-animation-ticks 10}
                                       :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}}
                                       :state {:charge-ticks 5 :punched? false :punch-ticks 0}})
        punch-done-frame (run/dispatch! program :pulse
                                        {:tunables {:charge-max-accepted-ticks 20
                                                    :charge-max-tolerant-ticks 40
                                                    :punch-animation-ticks 10}
                                         :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}}
                                         :state {:charge-ticks 5 :punched? true :punch-ticks 10}})]
    (is (= :charging (:outcome (.-result charging-frame))))
    (testing "punched? true -> punch-ticks increments too (10 -> 11, past the 10-tick animation)"
      (is (= #{[:charge-ticks 6] [:punch-ticks 11]}
             (set (map (juxt :key :value) (.-stateWrites punch-done-frame))))))
    (is (= :performed (:outcome (.-result punch-done-frame))))))

(deftest directed-blastwave-release-punches-when-charged-and-affordable-test
  (let [doc (read-skill "directed_blastwave.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:position {:x 5.0 :y 0.0 :z 0.0}}
                         :entity/select [{:id "e1" :position {:x 2.0 :y -0.1 :z 0.0}}]
                         :block/select []))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:charge-min-ticks 0 :charge-max-accepted-ticks 20
                          :targeting-distance 20.0 :aoe-radius 6.0 :damage 10.0
                          :knockback-scale 1.0 :hardness-low-threshold 0.3
                          :hardness-mid-threshold 0.7 :hardness-caps [1.0 2.0 3.0]
                          :break-probability [0.2 0.6] :drop-probability [0.1 0.3]}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0} :caster/id "player-1"
                              :world/id "overworld" :rng/seed 3 :progression/mastery 0.5
                              :budget/release {:cp 5.0} :progression/hit 1.0
                              :progression/miss 0.5 :cooldown/main 60}
               :state {:charge-ticks 5}}
        frame (run/dispatch! program :release input)]
    (is (some #(= [:command :entity/damage {:target "e1" :amount 10.0 :damage-type :skill
                                            :damage-pipeline :skill}] %)
              @calls))
    (testing "knockback: direction (1,0,0) normalized * (0.2 * |1.0|) = (0.2, 0.0, 0.0)"
      (is (some #(= :entity/impulse (second %)) @calls))
      (let [[_ _ impulse-args] (first (filter #(= :entity/impulse (second %)) @calls))
            [ix iy iz] (:vec3 (:vector impulse-args))]
        (is (< (Math/abs (- ix 0.2)) 1.0e-9))
        (is (< (Math/abs iy) 1.0e-9))
        (is (< (Math/abs iz) 1.0e-9))))
    (testing "block/select ran with the mid-tier hardness cap (mastery 0.5 is between the two thresholds)"
      (is (some #(and (= :block/select (second %)) (= 2.0 (:max-hardness (:projection (nth % 2)))))
                @calls)))
    (is (some #(and (= :score/mark (:type %)) (= :hit (:tag %))) (.-events frame)))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
    (is (= #{:directed-blastwave-charge :directed-blastwave-wave :audio-one-shot}
           (set (map :effect-id (.-vfx frame)))))
    (is (= #{[:punched? true] [:punch-ticks 0]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (testing "no :end-ability? -- a successful punch keeps the session alive for another charge"
      (is (= {:outcome :punched :next-phase nil :end-ability? false} (.-result frame))))))

(deftest blood-retrograde-pulse-discharges-on-full-charge-with-a-target-test
  (let [doc (read-skill "blood_retrograde.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :raycast {:entity-id "e1" :position {:x 5.0 :y 0.0 :z 0.0}}
                         :cost/spend true
                         :random/int 2))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:max-charge-ticks 10 :targeting-distance 20.0 :release-cp 3.0
                          :release-overload 1.0 :damage 25.0 :fx-ratio-ticks 8.0
                          :fallback-width 0.6 :fallback-height 1.8 :spray-angles [-10.0 10.0]}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :caster/body {:x 0.0 :y 1.0 :z 0.0} :rng/seed 4
                              :progression/hit 1.0 :cooldown/main 40}
               :state {:charge-ticks 9}}
        frame (run/dispatch! program :pulse input)]
    (is (some #(= [:command :entity/damage {:target "e1" :amount 25.0 :damage-type :skill
                                            :damage-pipeline :skill}] %)
              @calls))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 40}] %) @calls))
    (testing "splash-count = 6 + the rolled random/int (2) = 8"
      (is (= 8 (:splash-count (:payload (some #(when (= :blood-retrograde-impact (:effect-id %)) %)
                                              (.-vfx frame)))))))
    (is (= {:outcome :performed :next-phase nil :end-ability? true} (.-result frame)))))

(deftest blood-retrograde-release-no-target-ends-immediately-test
  (let [doc (read-skill "blood_retrograde.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :raycast {:entity-id nil}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:targeting-distance 20.0} :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                                                                    :caster/aim {:x 1.0 :y 0.0 :z 0.0}}
               :state {}}
        frame (run/dispatch! program :release input)]
    (is (not (some #(= :cost/spend (second %)) @calls)))
    (is (= :destroy (:operation (first (.-vfx frame)))))
    (is (= {:outcome :no-target :next-phase nil :end-ability? true} (.-result frame)))))

(deftest scatter-bomb-start-test
  (let [doc (read-skill "scatter_bomb.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true))
              :command! (fn [_cap _args _fr])}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:cost-down-overload 2.0 :spawn-start-tick 5}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :budget/activate {:overload 5.0}
                              :context/resources {:overload 20.0}}}
        frame (run/dispatch! program :start input)]
    (is (= #{[:balls 0] [:ball-ids []] [:overload-floor 18.0] [:next-spawn-tick 5]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest scatter-bomb-pulse-spawns-a-ball-when-due-test
  (let [doc (read-skill "scatter_bomb.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend true :entity/spawn {:entity-id "ball-1"}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:anti-afk-tick 999 :max-balls 5 :max-hold-ticks 100
                          :spawn-interval-ticks 4}
               :capabilities {:charge/ticks 10 :caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/id "player-1" :world/id "overworld"
                              :budget/charging {:cp 0.5}}
               :state {:overload-floor 5.0 :next-spawn-tick 10 :balls 0 :ball-ids []}}
        frame (run/dispatch! program :pulse input)]
    (is (some #(= [:command :resource/enforce-floor {:resource :overload :minimum 5.0}] %) @calls))
    (is (some #(= [:query :entity/spawn {:world-id "overworld" :entity-type "academy:entity_md_ball"
                                         :add-tags ["ac_scatter_bomb"] :owner "player-1"
                                         :position {:x 0.0 :y 1.0 :z 0.0}
                                         :velocity {:vec3 [0.0 0.0 0.0]} :life-ticks 2333333
                                         :barrier? true}] %)
              @calls))
    (is (= #{[:ball-ids ["ball-1"]] [:balls 1] [:next-spawn-tick 14]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (testing "the display balls count is the correct just-updated value (1), not the old +2 double-add bug"
      (is (= 1 (:balls (:payload (first (.-vfx frame)))))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest scatter-bomb-release-routes-auto-aim-and-scatter-balls-test
  (let [doc (read-skill "scatter_bomb.edn")
        calls (atom [])
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :entity/select (if (contains? (:filter args) :entity-types)
                                         [{:id "ball-1" :position {:x 1.0 :y 1.0 :z 0.0}}
                                          {:id "ball-2" :position {:x 2.0 :y 1.0 :z 0.0}}]
                                         [{:id "target-1" :position {:x 5.0 :y 0.0 :z 0.0}
                                          :eye-height 1.6}])
                         :data/random-item {:id "target-1" :position {:x 5.0 :y 0.0 :z 0.0}
                                            :eye-height 1.6}
                         :kernel/scatter-end {:vec3 [9.0 1.0 0.0]}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        ir (run/compile-doc! (:program doc) lib/fns)
        program (run/compile-program ir host)
        input {:tunables {:auto-aim-radius 30.0 :auto-aim-exp-threshold 0.3 :damage 8.0
                          :scatter-range 10.0 :scatter-angle-degrees 15.0 :max-balls 5}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 1.0 :y 0.0 :z 0.0}
                              :caster/id "player-1" :world/id "overworld"
                              :context/skill-exp 0.5 :progression/ball-fired 1.0}
               :state {:balls 2 :ball-ids ["ball-1" "ball-2"]}}
        frame (run/dispatch! program :release input)]
    (testing "auto-aim-limit = floor(balls(2) * skill-exp(0.5)) = 1 -> only ball-index 0 (ball-1) auto-aims"
      (is (some #(= [:command :entity/discard {:world-id "overworld"
                                               :entity {:id "ball-1" :position {:x 1.0 :y 1.0 :z 0.0}}}] %)
                @calls))
      (is (some #(= [:command :entity/discard {:world-id "overworld"
                                               :entity {:id "ball-2" :position {:x 2.0 :y 1.0 :z 0.0}}}] %)
                @calls)))
    (testing "ball-1 (index 0, within the auto-aim limit) targets the eye-adjusted target position"
      (is (some #(= {:vec3 [5.0 1.6 0.0]}
                    (:destination (nth % 2)))
                (filter #(and (= :command (first %)) (= :projectile/schedule-beam (second %))
                             (= {:x 1.0 :y 1.0 :z 0.0} (:origin (nth % 2))))
                       @calls))))
    (testing "ball-2 (index 1, past the auto-aim limit) uses the scattered endpoint from the host"
      (is (some #(= {:vec3 [9.0 1.0 0.0]} (:destination (nth % 2)))
                (filter #(and (= :command (first %)) (= :projectile/schedule-beam (second %))
                             (= {:x 2.0 :y 1.0 :z 0.0} (:origin (nth % 2))))
                       @calls))))
    (is (= 2 (count (filter #(= :beam-fade-audio (:effect-id %)) (.-vfx frame)))))
    (is (= :released (:outcome (.-result frame))))))

(deftest brain-course-advanced-test
  (let [doc (read-skill "brain_course_advanced.edn")]
    (assert-trivial-passive-phases! doc {})
    (testing "passive-effects (+1500 max-cp, +100 max-overload) is untouched"
      (is (= [{:target :max-cp :operation :add :value 1500.0}
             {:target :max-overload :operation :add :value 100.0}]
             (:passive-effects doc))))))

(deftest railgun-start-with-no-coin-and-iron-in-hand-arms-item-charge-test
  (let [doc (read-skill "railgun.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap :entity/select [] :item/held {:item-id "minecraft:iron_ingot"}))
              :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0}
                                           :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/id "player-1"}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= #{[:coin-id nil] [:mode :armed] [:hold-ticks 0] [:mode :item-charge]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :started (:outcome (.-result frame))))))

(deftest railgun-start-with-a-coin-candidate-stays-armed-test
  (let [doc (read-skill "railgun.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap
                         :entity/select [{:id "coin-1" :motion-progress 0.5 :owner-id "player-1"}]
                         :item/held {:item-id "minecraft:iron_ingot"}))
              :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0}
                                           :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/id "player-1"}}
        frame (compile-and-dispatch! doc :start host input)]
    (testing "a coin candidate present suppresses item-charge even though iron is held"
      (is (= #{[:coin-id "coin-1"] [:mode :armed] [:hold-ticks 0]}
             (set (map (juxt :key :value) (.-stateWrites frame))))))))

(deftest railgun-pulse-charges-item-and-transitions-to-release-when-full-test
  (let [doc (read-skill "railgun.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:item-charge-ticks 3} :capabilities {}
               :state {:mode :item-charge :hold-ticks 2}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= #{[:hold-ticks 3] [:fire-mode :item]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :charge-ready (:outcome (.-result frame))))
    (is (= :release (:next-phase (.-result frame)))))
  (testing "not yet full -> continue, no fire-mode write"
    (let [doc (read-skill "railgun.edn")
          host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
          input {:tunables {:item-charge-ticks 5} :capabilities {}
                 :state {:mode :item-charge :hold-ticks 1}}
          frame (compile-and-dispatch! doc :pulse host input)]
      (is (= [{:key :hold-ticks :value 2}] (vec (.-stateWrites frame))))
      (is (= :continue (:outcome (.-result frame)))))))

(deftest railgun-pulse-armed-mode-is-a-no-op-continue-test
  (let [doc (read-skill "railgun.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {} :state {:mode :armed :hold-ticks 0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (empty? (.-stateWrites frame)))
    (is (= :continue (:outcome (.-result frame))))))

(deftest railgun-coin-thrown-perform-and-miss-and-ignored-test
  (let [calls (atom [])
        doc (read-skill "railgun.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap
                         :entity/select [{:id "coin-1" :motion-progress 0.9 :owner-id "player-1"}]))
              :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
        input {:tunables {:qte-active-threshold 0.7 :qte-perform-threshold 0.8}
               :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/id "player-1"
                              :world/id "overworld"}
               :state {:mode :armed}}
        frame (compile-and-dispatch! doc :coin-thrown host input)]
    (testing "motion-progress 0.9 clears both thresholds -> perform, discard the coin, go to release"
      (is (some #(= [:entity/discard {:world-id "overworld" :entity {:id "coin-1"}}] %) @calls))
      (is (= #{[:coin-id "coin-1"] [:fire-mode :coin]}
             (set (map (juxt :key :value) (.-stateWrites frame)))))
      (is (= :qte-perform (:outcome (.-result frame))))
      (is (= :release (:next-phase (.-result frame))))))
  (testing "below the perform threshold -> miss, no fire-mode/discard"
    (let [doc (read-skill "railgun.edn")
          host {:query! (fn [cap _args _fr]
                         (case cap
                           :entity/select [{:id "coin-1" :motion-progress 0.5 :owner-id "player-1"}]))
                :command! (fn [_cap _args _fr])}
          input {:tunables {:qte-active-threshold 0.7 :qte-perform-threshold 0.8}
                 :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/id "player-1"
                                :world/id "overworld"}
                 :state {:mode :armed}}
          frame (compile-and-dispatch! doc :coin-thrown host input)]
      (is (= :qte-miss (:outcome (.-result frame))))))
  (testing "not armed (already item-charging) -> ignored, no query even issued"
    (let [doc (read-skill "railgun.edn")
          host {:query! (fn [_cap _args _fr] (throw (ex-info "should not be queried" {})))
                :command! (fn [_cap _args _fr])}
          input {:tunables {} :capabilities {} :state {:mode :item-charge}}
          frame (compile-and-dispatch! doc :coin-thrown host input)]
      (is (= :ignored (:outcome (.-result frame)))))))

(deftest railgun-release-coin-mode-fires-beam-breaks-blocks-and-cools-down-test
  (let [calls (atom [])
        doc (read-skill "railgun.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :kernel/trace-beam
                         {:blocks [{:hardness 1.0 :position {:x 0.0 :y 0.0 :z 0.0}}]
                          :entities [{:id "e1" :type "minecraft:creeper" :damage 5.0
                                     :damage-type :skill :reflection-accepted? false}]
                          :start {:x 0.0 :y 1.5 :z 0.0} :end {:x 0.0 :y 1.5 :z 10.0}}
                         :cost/spend true
                         :random/chance false
                         :block/break nil))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:max-distance 30.0 :beam-visual-distance 30.0 :beam-radius 0.3
                          :beam-query-radius 1.0 :beam-step 0.5 :beam-damage 6.0
                          :beam-block-energy 4.0 :reflection-distance 8.0 :reflection-damage 2.0
                          :cost-down-cp 4.0 :cost-down-overload 0.0 :cost-tick-cp 2.0
                          :cost-tick-overload 0.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/id "player-1" :world/id "overworld"
                              :progression/hit 1.0 :cooldown/main 40}
               :state {:fire-mode :coin}}
        frame (compile-and-dispatch! doc :release host input)]
    (testing "coin fire-mode spends the discounted coin budget, not the tick budget"
      (is (some #(= [:query :cost/spend {:budget {:cp 4.0 :overload 0.0}}] %) @calls)))
    (testing "the creeper hit fires the achievement event, and a plain score/mark always fires"
      (is (some #(and (= :achievement/trigger (:type %))
                      (= "electromaster.attack_creeper" (:id (:payload %))))
                (.-events frame)))
      (is (some #(and (= :score/mark (:type %)) (= :hit (:tag %))) (.-events frame))))
    (testing "the queried block was broken once, within the energy budget"
      (is (some #(= [:query :block/break {:position {:x 0.0 :y 0.0 :z 0.0} :drop? false}] %) @calls)))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 40}] %) @calls))
    (is (= [{:key :reflection-hit? :value false}] (vec (.-stateWrites frame))))
    (is (= :committed (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest railgun-release-item-mode-insufficient-resource-aborts-test
  (let [doc (read-skill "railgun.edn")
        host {:query! (fn [cap _args _fr]
                       (case cap :item/held {:item-id "minecraft:diamond"} :cost/spend false))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:max-distance 30.0 :beam-visual-distance 30.0 :beam-radius 0.3
                          :beam-query-radius 1.0 :beam-step 0.5 :beam-damage 6.0
                          :beam-block-energy 4.0 :reflection-distance 8.0 :reflection-damage 2.0
                          :cost-down-cp 4.0 :cost-down-overload 0.0 :cost-tick-cp 2.0
                          :cost-tick-overload 0.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/id "player-1" :world/id "overworld"}
               :state {:fire-mode :item}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest railgun-release-item-mode-consumes-iron-when-held-test
  (let [calls (atom [])
        doc (read-skill "railgun.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :item/held {:item-id "minecraft:iron_block"}
                         :kernel/trace-beam {:blocks [] :entities []
                                             :start {:x 0.0 :y 1.5 :z 0.0} :end {:x 0.0 :y 1.5 :z 10.0}}
                         :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:max-distance 30.0 :beam-visual-distance 30.0 :beam-radius 0.3
                          :beam-query-radius 1.0 :beam-step 0.5 :beam-damage 6.0
                          :beam-block-energy 4.0 :reflection-distance 8.0 :reflection-damage 2.0
                          :cost-down-cp 4.0 :cost-down-overload 0.0 :cost-tick-cp 2.0
                          :cost-tick-overload 0.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/id "player-1" :world/id "overworld"
                              :progression/hit 1.0 :cooldown/main 40}
               :state {:fire-mode :item}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (some #(= [:command :inventory/consume {:source :main-hand :count 1}] %) @calls))
    (testing "item fire-mode spends the tick budget, not the discounted coin budget"
      (is (some #(= [:query :cost/spend {:budget {:cp 2.0 :overload 0.0}}] %) @calls)))
    (is (= :committed (:outcome (.-result frame))))))

(defn- shift-teleport-host
  "target/raycast and target/block-placement share the SAME :raycast
   capability (dsl_vocabulary.clj) -- distinguish by the presence of
   :hit, block-placement's own mandatory param raycast never has."
  [& {:keys [present? placeable? valid? targets]
      :or {present? true placeable? true valid? true targets []}}]
  {:query! (fn [cap args _fr]
            (case cap
              :raycast (if (contains? args :hit)
                        {:valid? valid? :position {:x 1.0 :y 2.0 :z 3.0}
                         :line-position {:x 1.0 :y 2.5 :z 3.0}}
                        {:some-hit-result true})
              :item/held {:present? present? :placeable? placeable? :item-id "minecraft:dirt"}
              :entity/select targets
              :cost/spend true))
   :command! (fn [_cap _args _fr])})

(deftest shift-teleport-start-with-valid-placement-spawns-boxes-test
  (let [doc (read-skill "shift_teleport.edn")
        calls (atom [])
        target {:id "t1" :position {:x 1.0 :y 2.0 :z 3.0} :width 0.6 :height 1.8}
        host (assoc (shift-teleport-host :targets [target])
                    :command! (fn [_cap _args _fr]))
        input {:tunables {:maximum-range 8.0} :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0}
                                                              :caster/body {:x 0.0 :y 1.0 :z 0.0}
                                                              :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= #{:hand-item :trace :targets} (set (map :key (.-stateWrites frame)))))
    (is (some #(= :target-box-session (:effect-id %)) (.-vfx frame)))
    (is (some #(and (= [:activation :shift-teleport-target "t1"] (:instance-key %))
                    (= :spawn (:operation %)))
              (.-vfx frame)))
    (is (= :started (:outcome (.-result frame))))))

(deftest shift-teleport-start-without-item-finishes-no-item-test
  (let [doc (read-skill "shift_teleport.edn")
        host (shift-teleport-host :present? false)
        input {:tunables {:maximum-range 8.0} :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0}
                                                              :caster/body {:x 0.0 :y 1.0 :z 0.0}
                                                              :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= :no-item (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest shift-teleport-pulse-clears-stale-boxes-and-continues-test
  (let [doc (read-skill "shift_teleport.edn")
        vfx-ops (atom [])
        target {:id "t2" :position {:x 2.0 :y 2.0 :z 3.0} :width 0.6 :height 1.8}
        host (shift-teleport-host :targets [target])
        input {:tunables {:maximum-range 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/body {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 0.0 :y 0.0 :z 1.0}}
               :state {:targets [{:id "t1" :position {:x 1.0 :y 2.0 :z 3.0} :width 0.6 :height 1.8}]}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (some #(and (= [:activation :shift-teleport-target "t1"] (:instance-key %))
                    (= :destroy (:operation %)))
              (.-vfx frame)))
    (is (some #(and (= [:activation :shift-teleport-destination] (:instance-key %))
                    (= :update (:operation %)))
              (.-vfx frame)))
    (is (some #(and (= [:activation :shift-teleport-target "t2"] (:instance-key %))
                    (= :spawn (:operation %)))
              (.-vfx frame)))
    (is (= :continue (:outcome (.-result frame))))))

(deftest shift-teleport-pulse-aborts-when-placement-becomes-invalid-test
  (let [doc (read-skill "shift_teleport.edn")
        host (shift-teleport-host :valid? false)
        input {:tunables {:maximum-range 8.0} :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0}
                                                              :caster/body {:x 0.0 :y 1.0 :z 0.0}
                                                              :caster/aim {:x 0.0 :y 0.0 :z 1.0}}
               :state {:targets []}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (some #(and (= [:activation :shift-teleport-destination] (:instance-key %))
                    (= :destroy (:operation %)))
              (.-vfx frame)))
    (is (= :aborted (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest shift-teleport-release-performed-places-block-and-damages-line-targets-test
  (let [calls (atom [])
        doc (read-skill "shift_teleport.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend true))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:damage 6.0 :exp-base 2.0}
               :capabilities {:caster/creative? false :cooldown/main 100 :budget/release {:cp 3.0}}
               :state {:hand-item {:present? true :placeable? true}
                      :trace {:valid? true :position {:x 1.0 :y 2.0 :z 3.0}
                             :line-position {:x 1.0 :y 2.5 :z 3.0}}
                      :targets [{:id "t1" :position {:x 1.0 :y 2.0 :z 3.0}}
                               {:id "t2" :position {:x 1.0 :y 2.0 :z 3.0}}]}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (some #(= [:query :cost/spend {:budget {:cp 3.0} :scale 1.0}] %) @calls))
    (is (some #(= [:command :inventory/place-or-drop
                  {:source :main-hand :count 1
                   :plan {:valid? true :position {:x 1.0 :y 2.0 :z 3.0}
                         :line-position {:x 1.0 :y 2.5 :z 3.0}}
                   :creative? false}] %)
              @calls))
    (testing "both line targets took magic damage"
      (is (= 2 (count (filter #(and (= :command (first %)) (= :entity/damage (second %))) @calls)))))
    (testing "progression = exp-base(2.0) * (1 + hit-count(2)) = 6.0, weight = 1 + 2 = 3.0"
      (is (some #(and (= :score/mark (:type %)) (= 6.0 (:progression %)) (= 3.0 (:weight %)))
                (.-events frame))))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 100}] %) @calls))
    (is (= :performed (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest shift-teleport-release-insufficient-resource-clears-boxes-test
  (let [doc (read-skill "shift_teleport.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend false))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:damage 6.0 :exp-base 2.0}
               :capabilities {:caster/creative? true :budget/release {:cp 3.0}}
               :state {:hand-item {:present? true :placeable? true}
                      :trace {:valid? true :position {:x 1.0 :y 2.0 :z 3.0}}
                      :targets []}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest shift-teleport-release-without-a-valid-item-finishes-no-item-test
  (let [doc (read-skill "shift_teleport.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:damage 6.0 :exp-base 2.0} :capabilities {}
               :state {:hand-item {:present? false :placeable? false} :trace {:valid? false}
                      :targets []}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= :no-item (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest shift-teleport-abort-destroys-all-boxes-test
  (let [doc (read-skill "shift_teleport.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {} :state {:targets [{:id "t1"}]}}
        frame (compile-and-dispatch! doc :abort host input)]
    (is (some #(= [:activation :shift-teleport-destination] (:instance-key %)) (.-vfx frame)))
    (is (some #(= [:activation :shift-teleport-target "t1"] (:instance-key %)) (.-vfx frame)))
    (is (= :aborted (:outcome (.-result frame))))))

(deftest meltdowner-start-test
  (let [doc (read-skill "meltdowner.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:cost-down-overload 10.0}
               :capabilities {:caster/body {:x 0.0 :y 1.0 :z 0.0} :context/resources {:overload 40.0}}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= #{[:hold-ticks 0] [:time-rate 0.8] [:overload-floor 30.0]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= 3 (count (.-vfx frame))))
    (is (= :started (:outcome (.-result frame)))))
  (testing "insufficient activate budget"
    (let [doc (read-skill "meltdowner.edn")
          host {:query! (fn [cap _args _fr] (case cap :cost/spend false))
                :command! (fn [_cap _args _fr])}
          input {:tunables {} :capabilities {}}
          frame (compile-and-dispatch! doc :start host input)]
      (is (= :insufficient-resource (:outcome (.-result frame))))
      (is (true? (:end-ability? (.-result frame))))
      (is (empty? (.-stateWrites frame))))))

(deftest meltdowner-pulse-charges-and-continues-test
  (let [doc (read-skill "meltdowner.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-min-ticks 0 :charge-max-ticks 40 :charge-max-tolerant-ticks 60
                          :charge-time-rate [0.8 2.0]}
               :capabilities {:caster/body {:x 0.0 :y 1.0 :z 0.0}}
               :state {:overload-floor 5.0 :hold-ticks 19}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (testing "hold-ticks incremented, time-rate lerped by progress 20/40=0.5 -> 0.8+0.5*(2.0-0.8)=1.4"
      (is (= #{[:hold-ticks 20] [:time-rate 1.4]}
             (set (map (juxt :key :value) (.-stateWrites frame))))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest meltdowner-pulse-overcharged-destroys-vfx-and-ends-test
  (let [doc (read-skill "meltdowner.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-min-ticks 0 :charge-max-ticks 40 :charge-max-tolerant-ticks 45
                          :charge-time-rate [0.8 2.0]}
               :capabilities {:caster/body {:x 0.0 :y 1.0 :z 0.0}}
               :state {:overload-floor 5.0 :hold-ticks 45}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= 3 (count (filter #(= :destroy (:operation %)) (.-vfx frame)))))
    (is (= :overcharged (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest meltdowner-pulse-insufficient-tick-budget-ends-test
  (let [doc (read-skill "meltdowner.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend false))
              :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {} :state {:overload-floor 5.0 :hold-ticks 10}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= 3 (count (.-vfx frame))))
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest meltdowner-release-undercharged-ends-test
  (let [doc (read-skill "meltdowner.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-min-ticks 10} :capabilities {} :state {:hold-ticks 5}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= :undercharged (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest meltdowner-release-applies-damage-exactly-once-per-hit-test
  (let [calls (atom [])
        doc (read-skill "meltdowner.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :kernel/trace-beam
                         {:blocks [] :start {:x 0.0 :y 1.0 :z 0.0} :end {:x 0.0 :y 1.0 :z 10.0}
                          :entities [{:id "e1" :damage 5.0 :damage-type :magic
                                     :reflection-accepted? false}
                                    {:reflection-accepted? true :reflection-target "e2"
                                     :reflection-damage 3.0 :damage-type :magic
                                     :reflection-start {:x 0.0 :y 1.0 :z 5.0}
                                     :reflection-end {:x 1.0 :y 1.0 :z 5.0}}]}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:charge-min-ticks 10 :beam-damage 4.0 :beam-max-distance 30.0
                          :beam-visual-distance 30.0 :beam-radius 0.3 :beam-query-radius 1.0
                          :beam-step 0.5 :beam-block-energy 2.0 :reflection-shot-distance 6.0
                          :reflection-damage-multiplier 1.0 :reflection-base-damage 2.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 1.0 :z 0.0} :cooldown/main 60
                              :progression/use 1.0}
               :state {:hold-ticks 20 :time-rate 1.0}}
        frame (compile-and-dispatch! doc :release host input)]
    (testing "each hit entity is damaged exactly once, not twice (the old content's own double-damage bug)"
      (is (= [[:command :entity/damage {:target "e1" :amount 5.0 :damage-type :magic}]
             [:command :entity/damage {:target "e2" :amount 3.0 :damage-type :magic}]]
             (filter #(and (= :command (first %)) (= :entity/damage (second %))) @calls))))
    (testing "the reflection-accepted hit still gets its VFX"
      (is (some #(= :ray-beam-transient (:effect-id %)) (.-vfx frame))))
    (is (some #(and (= :score/mark (:type %)) (= :use (:tag %))) (.-events frame)))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 60}] %) @calls))
    (is (= :performed (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest meltdowner-abort-destroys-session-vfx-test
  (let [doc (read-skill "meltdowner.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {}}
        frame (compile-and-dispatch! doc :abort host input)]
    (is (= 3 (count (.-vfx frame))))
    (is (every? #(= :destroy (:operation %)) (.-vfx frame)))
    (is (= :aborted (:outcome (.-result frame))))))

(defn- light-shield-deactivate-calls-ok?
  [calls]
  (and (some #(= [:command :entity/discard {:entity {:id "shield-1"}}] %) calls)
       (some #(= [:command :entity/status {:target "player-1" :status-id :slowness
                                           :duration-ticks 100.0 :amplifier 1}] %)
             calls)
       (some #(= [:command :cooldown/start {:name :deactivate :ticks 80}] %) calls)))

(deftest light-shield-start-test
  (let [calls (atom [])
        doc (read-skill "light_shield.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :entity/spawn {:entity-id "shield-1"}))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:activate-overload 5.0 :max-active-ticks 200.0}
               :capabilities {:caster/id "player-1" :caster/body {:x 0.0 :y 1.0 :z 0.0}
                              :caster/eye {:x 0.0 :y 1.5 :z 0.0}
                              :context/resources {:overload 20.0}}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= #{:active-ticks :last-absorb-tick :overload-floor :shield-id}
           (set (map :key (.-stateWrites frame)))))
    (is (some #(= {:key :overload-floor :value 15.0} (select-keys % [:key :value]))
              (.-stateWrites frame)))
    (is (= 4 (count (.-vfx frame))))
    (is (= :started (:outcome (.-result frame))))))

(deftest light-shield-start-insufficient-resource-test
  (let [doc (read-skill "light_shield.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend false))
              :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (empty? (.-stateWrites frame)))))

(deftest light-shield-pulse-touches-in-cone-and-continues-test
  (let [calls (atom [])
        doc (read-skill "light_shield.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :owner/snapshot {:position {:x 0.0 :y 1.0 :z 0.0}
                                          :eye-position {:x 0.0 :y 1.5 :z 0.0}
                                          :look {:x 0.0 :y 0.0 :z 1.0}}
                         :entity/select [{:id "victim-1" :position {:x 0.0 :y 1.0 :z 2.0}
                                          :invulnerable-time 0.0}]))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:touch-radius 3.0 :front-cone-degrees 45.0 :touch-damage 4.0
                          :max-active-ticks 200.0}
               :capabilities {:caster/id "player-1" :progression/touch 0.5 :progression/tick 1.0}
               :state {:overload-floor 5.0 :active-ticks 10}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (some #(= [:command :entity/damage {:target "victim-1" :amount 4.0 :damage-type :magic}] %)
              @calls))
    (is (some #(and (= :score/mark (:type %)) (= :touch (:tag %))) (.-events frame)))
    (is (some #(and (= :score/mark (:type %)) (= :tick (:tag %))) (.-events frame)))
    (is (= #{[:active-ticks 11]} (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest light-shield-pulse-skips-touch-when-budget-insufficient-test
  (let [calls (atom [])
        doc (read-skill "light_shield.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :owner/snapshot {:position {:x 0.0 :y 1.0 :z 0.0}
                                          :eye-position {:x 0.0 :y 1.5 :z 0.0}
                                          :look {:x 0.0 :y 0.0 :z 1.0}}
                         :entity/select [{:id "victim-1" :position {:x 0.0 :y 1.0 :z 2.0}
                                          :invulnerable-time 0.0}]
                         :cost/spend (not= :touch-budget (:budget args))))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:touch-radius 3.0 :front-cone-degrees 45.0 :touch-damage 4.0
                          :max-active-ticks 200.0}
               :capabilities {:caster/id "player-1" :progression/touch 0.5 :progression/tick 1.0
                              :budget/tick :tick-budget :budget/touch :touch-budget}
               :state {:overload-floor 5.0 :active-ticks 10}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (not (some #(= :entity/damage (second %)) @calls)))
    (is (not (some #(and (= :score/mark (:type %)) (= :touch (:tag %))) (.-events frame))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest light-shield-pulse-timeout-deactivates-test
  (let [calls (atom [])
        doc (read-skill "light_shield.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :owner/snapshot {:position {:x 0.0 :y 1.0 :z 0.0}
                                          :eye-position {:x 0.0 :y 1.5 :z 0.0}
                                          :look {:x 0.0 :y 0.0 :z 1.0}}
                         :entity/select []))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:touch-radius 3.0 :front-cone-degrees 45.0 :touch-damage 4.0
                          :max-active-ticks 10.0 :slowness-duration-ticks 100.0
                          :slowness-amplifier 1}
               :capabilities {:caster/id "player-1" :progression/touch 0.5 :progression/tick 1.0
                              :cooldown/deactivate 80}
               :state {:overload-floor 5.0 :active-ticks 10 :shield-id "shield-1"}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (light-shield-deactivate-calls-ok? @calls))
    (testing "3 session-update vfx during the tick, then 3 destroy vfx from the timeout cleanup"
      (is (= 3 (count (filter #(= :destroy (:operation %)) (.-vfx frame))))))
    (is (= :timeout (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest light-shield-pulse-insufficient-tick-budget-deactivates-test
  (let [calls (atom [])
        doc (read-skill "light_shield.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend false))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:slowness-duration-ticks 100.0 :slowness-amplifier 1}
               :capabilities {:caster/id "player-1" :cooldown/deactivate 80}
               :state {:overload-floor 5.0 :shield-id "shield-1"}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (light-shield-deactivate-calls-ok? @calls))
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest light-shield-release-and-abort-both-deactivate-test
  (doseq [phase [:release :abort]]
    (let [calls (atom [])
          doc (read-skill "light_shield.edn")
          host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) nil)
                :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
          input {:tunables {:slowness-duration-ticks 100.0 :slowness-amplifier 1}
                 :capabilities {:caster/id "player-1" :cooldown/deactivate 80}
                 :state {:shield-id "shield-1"}}
          frame (compile-and-dispatch! doc phase host input)]
      (is (light-shield-deactivate-calls-ok? @calls) (str "phase " phase))
      (is (= (if (= phase :release) :released :aborted) (:outcome (.-result frame)))
          (str "phase " phase)))))

(deftest directed-shock-start-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= #{[:charge-ticks 0] [:punched? false] [:punch-ticks 0]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= 1 (count (.-vfx frame))))
    (is (= :started (:outcome (.-result frame))))))

(deftest directed-shock-pulse-charges-and-continues-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-max-tolerant-ticks 30 :punch-animation-ticks 6}
               :state {:charge-ticks 4 :punched? false :punch-ticks 0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= #{[:charge-ticks 5] [:punch-ticks 0]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (some #(= :prepare (:stage (:payload %))) (.-vfx frame)))
    (is (= :charging (:outcome (.-result frame))))))

(deftest directed-shock-pulse-aborts-when-overcharged-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-max-tolerant-ticks 10 :punch-animation-ticks 6}
               :state {:charge-ticks 9 :punched? false :punch-ticks 0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (some #(= :destroy (:operation %)) (.-vfx frame)))
    (is (= :aborted (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest directed-shock-pulse-punch-animation-completes-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-max-tolerant-ticks 30 :punch-animation-ticks 5}
               :state {:charge-ticks 20 :punched? true :punch-ticks 5}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= #{[:charge-ticks 21] [:punch-ticks 6]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (some #(= :punch (:stage (:payload %))) (.-vfx frame)))
    (is (= :performed (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest directed-shock-release-undercharged-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-min-ticks 5 :charge-max-accepted-ticks 30}
               :state {:charge-ticks 2}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= :undercharged (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest directed-shock-release-insufficient-resource-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend false))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-min-ticks 5 :charge-max-accepted-ticks 30 :targeting-distance 6.0
                          :target-eye-height 1.5 :hit-impulse 3.0 :knockback-y-adjust 0.0
                          :knockback-scale 2.0 :knockback-exp-threshold 1.0 :damage 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0} :progression/mastery 0.0
                              :progression/hit 1.0 :progression/miss 0.2 :cooldown/main 40}
               :state {:charge-ticks 10}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest directed-shock-release-miss-when-no-target-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true :raycast {:entity-id nil}))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:charge-min-ticks 5 :charge-max-accepted-ticks 30 :targeting-distance 6.0
                          :target-eye-height 1.5 :hit-impulse 3.0 :knockback-y-adjust 0.0
                          :knockback-scale 2.0 :knockback-exp-threshold 1.0 :damage 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0} :progression/mastery 0.0
                              :progression/hit 1.0 :progression/miss 0.2 :cooldown/main 40}
               :state {:charge-ticks 10}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (some #(and (= :score/mark (:type %)) (= 0.2 (:progression %))) (.-events frame)))
    (is (= :miss (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest directed-shock-release-low-mastery-hit-applies-damage-and-impulse-only-test
  (let [calls (atom [])
        doc (read-skill "directed_shock.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id "e1" :position {:x 0.0 :y 0.0 :z 5.0}
                                  :eye-height nil}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:charge-min-ticks 5 :charge-max-accepted-ticks 30 :targeting-distance 6.0
                          :target-eye-height 0.0 :hit-impulse 3.0 :knockback-y-adjust 0.0
                          :knockback-scale 2.0 :knockback-exp-threshold 1.0 :damage 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0} :progression/mastery 0.0
                              :progression/hit 1.0 :progression/miss 0.2 :cooldown/main 40}
               :state {:charge-ticks 10}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (some #(= [:command :entity/damage
                  {:target "e1" :amount 8.0 :damage-type :skill :damage-pipeline :skill}] %)
              @calls))
    (testing "low mastery: velocity-add only, no teleport, moved-hit-position == raw hit-position"
      (is (some #(= [:command :motion/entity-velocity-add
                    {:target "e1" :velocity {:vec3 [0.0 0.0 3.0]}}] %)
                @calls))
      (is (not (some #(= :entity/teleport (second %)) @calls)))
      (is (not (some #(= :motion/entity-velocity (second %)) @calls))))
    (is (= #{[:punched? true] [:punch-ticks 0]} (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 40}] %) @calls))
    (is (= :punched (:outcome (.-result frame))))))

(deftest directed-shock-release-high-mastery-hit-teleports-and-adds-knockback-test
  (let [calls (atom [])
        doc (read-skill "directed_shock.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :raycast {:entity-id "e1" :position {:x 0.0 :y 0.0 :z 4.0}
                                  :eye-height 0.0}))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:charge-min-ticks 5 :charge-max-accepted-ticks 30 :targeting-distance 6.0
                          :target-eye-height 1.5 :hit-impulse 3.0 :knockback-y-adjust 0.0
                          :knockback-scale 2.0 :knockback-exp-threshold 1.0 :damage 8.0}
               :capabilities {:caster/eye {:x 0.0 :y 0.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0} :progression/mastery 2.0
                              :progression/hit 1.0 :progression/miss 0.2 :cooldown/main 40}
               :state {:charge-ticks 10}}
        frame (compile-and-dispatch! doc :release host input)]
    (testing "eye-height present (0.0) is used as-is, not the target-eye-height tunable fallback"
      (is (some #(= [:command :entity/teleport {:target "e1" :position {:vec3 [0.0 0.1 4.0]}}] %)
                @calls)))
    (testing "high mastery: velocity replaces velocity-add; knockback collapsed to [0 0 0]
              (with-z set its own Z to its own Y, 0.0), so velocity is just hit-impulse-vec"
      (is (some #(and (= :command (first %)) (= :motion/entity-velocity (second %))
                      (= "e1" (:target (nth % 2)))
                      (let [[x y z] (:vec3 (:velocity (nth % 2)))]
                        (and (zero? x) (< 0.0749 y 0.0751) (< 2.998 z 3.0))))
                @calls))
      (is (not (some #(= :motion/entity-velocity-add (second %)) @calls))))
    (is (= :punched (:outcome (.-result frame))))))

(deftest directed-shock-abort-test
  (let [doc (read-skill "directed_shock.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [_cap _args _fr])}
        input {:tunables {} :capabilities {}}
        frame (compile-and-dispatch! doc :abort host input)]
    (is (some #(= :destroy (:operation %)) (.-vfx frame)))
    (is (= :aborted (:outcome (.-result frame))))))

(def ^:private mine-ray-runtime
  {:beam-style :beam :progress-color [255 0 0] :loop-sound-id "loop" :startup-sound-id "start"
   :particle :dust :fortune-level 0 :tool-tier-capped? false})

(deftest mine-ray-start-test
  (let [doc (read-skill "mine_ray.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:cost-down-overload 5.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :context/resources {:overload 20.0}
                              :context/ability-runtime mine-ray-runtime}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= #{[:target nil] [:hardness-left 3.4028235E38] [:starting-hardness 3.4028235E38]
             [:overload-floor 15.0]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= 4 (count (.-vfx frame))))
    (is (= :started (:outcome (.-result frame))))))

(deftest mine-ray-start-insufficient-resource-test
  (let [doc (read-skill "mine_ray.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend false))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:cost-down-overload 5.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :context/resources {:overload 20.0}
                              :context/ability-runtime mine-ray-runtime}}
        frame (compile-and-dispatch! doc :start host input)]
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (empty? (.-stateWrites frame)))))

(deftest mine-ray-pulse-insufficient-tick-budget-test
  (let [calls (atom [])
        doc (read-skill "mine_ray.edn")
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap :cost/spend false))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0}
                              :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0} :cooldown/main 40
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= 3 (count (.-vfx frame))))
    (is (every? #(= :destroy (:operation %)) (.-vfx frame)))
    (is (some #(= [:command :cooldown/start {:name :main :ticks 40}] %) @calls))
    (is (= :insufficient-resource (:outcome (.-result frame))))
    (is (true? (:end-ability? (.-result frame))))))

(deftest mine-ray-pulse-no-blocks-in-range-clears-target-test
  (let [doc (read-skill "mine_ray.edn")
        host {:query! (fn [cap _args _fr] (case cap :cost/spend true :block/select []))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0 :target {:x 1.0 :y 1.0 :z 1.0}}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= #{[:target nil] [:hardness-left 3.4028235E38] [:starting-hardness 3.4028235E38]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest mine-ray-pulse-continues-mining-the-same-target-test
  (let [doc (read-skill "mine_ray.edn")
        target-pos {:x 1.0 :y 1.0 :z 1.0}
        host {:query! (fn [cap _args _fr]
                       (case cap
                         :cost/spend true
                         :block/select [{:position target-pos :hardness 10.0 :block-id "stone"
                                         :breakable? true :requires-high-tier-tool? false}]))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :ability/destroy-blocks? true
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0 :target target-pos :hardness-left 10.0
                       :starting-hardness 10.0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (testing "new-hardness = 10-3 = 7, progress = 1 - 7/10 = 0.3"
      (is (= [{:key :hardness-left :value 7.0}] (vec (.-stateWrites frame))))
      (is (some #(let [p (:progress (:payload %))] (and p (< 0.299 p 0.301))) (.-vfx frame))))
    (is (some #(= :particle-burst (:effect-id %)) (.-vfx frame)))
    (is (= :continue (:outcome (.-result frame))))))

(deftest mine-ray-pulse-breaks-block-and-marks-progression-test
  (let [calls (atom [])
        doc (read-skill "mine_ray.edn")
        target-pos {:x 1.0 :y 1.0 :z 1.0}
        host {:query! (fn [cap args _fr]
                       (swap! calls conj [:query cap args])
                       (case cap
                         :cost/spend true
                         :block/select [{:position target-pos :hardness 10.0 :block-id "stone"
                                         :breakable? true :requires-high-tier-tool? false}]
                         :block/break :applied))
              :command! (fn [cap args _fr] (swap! calls conj [:command cap args]))}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :ability/destroy-blocks? true :progression/block 0.4
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0 :target target-pos :hardness-left 2.0
                       :starting-hardness 10.0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (some #(= [:query :block/break {:position target-pos :expected-block-id "stone" :drop? true
                                        :fortune-level 0 :tool-tier-capped? false :barrier? true}]
                  %)
              @calls))
    (is (some #(and (= :score/mark (:type %)) (= 0.4 (:progression %))) (.-events frame)))
    (is (= #{[:target nil] [:hardness-left 3.4028235E38] [:starting-hardness 3.4028235E38]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))
    (is (= :continue (:outcome (.-result frame))))))

(deftest mine-ray-pulse-break-not-applied-still-clears-target-test
  (let [doc (read-skill "mine_ray.edn")
        target-pos {:x 1.0 :y 1.0 :z 1.0}
        host {:query! (fn [cap _args _fr]
                       (case cap
                         :cost/spend true
                         :block/select [{:position target-pos :hardness 10.0 :block-id "stone"
                                         :breakable? true :requires-high-tier-tool? false}]
                         :block/break :cancelled))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :ability/destroy-blocks? true
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0 :target target-pos :hardness-left 2.0
                       :starting-hardness 10.0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (empty? (filter #(and (= :score/mark (:type %))) (.-events frame))))
    (is (= #{[:target nil] [:hardness-left 3.4028235E38] [:starting-hardness 3.4028235E38]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))))

(deftest mine-ray-pulse-starts-a-new-target-test
  (let [doc (read-skill "mine_ray.edn")
        new-pos {:x 2.0 :y 1.0 :z 1.0}
        host {:query! (fn [cap _args _fr]
                       (case cap
                         :cost/spend true
                         :block/select [{:position new-pos :hardness 12.0 :block-id "stone"
                                         :breakable? true :requires-high-tier-tool? false}]))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :ability/destroy-blocks? true
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0 :target nil}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= #{[:target new-pos] [:hardness-left 12.0] [:starting-hardness 12.0]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))))

(deftest mine-ray-pulse-cannot-destroy-blocks-clears-target-test
  (let [doc (read-skill "mine_ray.edn")
        target-pos {:x 1.0 :y 1.0 :z 1.0}
        host {:query! (fn [cap _args _fr]
                       (case cap
                         :cost/spend true
                         :block/select [{:position target-pos :hardness 10.0 :block-id "stone"
                                         :breakable? true :requires-high-tier-tool? false}]))
              :command! (fn [_cap _args _fr])}
        input {:tunables {:targeting-range 6.0 :break-speed 3.0}
               :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                              :caster/body {:x 0.0 :y 0.0 :z 0.0}
                              :ability/destroy-blocks? false
                              :context/ability-runtime mine-ray-runtime}
               :state {:overload-floor 5.0 :target target-pos :hardness-left 2.0
                       :starting-hardness 10.0}}
        frame (compile-and-dispatch! doc :pulse host input)]
    (is (= #{[:target nil] [:hardness-left 3.4028235E38] [:starting-hardness 3.4028235E38]}
           (set (map (juxt :key :value) (.-stateWrites frame)))))))

(deftest mine-ray-release-destroys-vfx-and-starts-cooldown-test
  (let [calls (atom [])
        doc (read-skill "mine_ray.edn")
        host {:query! (fn [_cap _args _fr]) :command! (fn [cap args _fr] (swap! calls conj [cap args]))}
        input {:tunables {} :capabilities {:cooldown/main 40}}
        frame (compile-and-dispatch! doc :release host input)]
    (is (= 3 (count (.-vfx frame))))
    (is (some #(= [:cooldown/start {:name :main :ticks 40}] %) @calls))
    (is (= :released (:outcome (.-result frame))))))
