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

(deftest brain-course-advanced-test
  (let [doc (read-skill "brain_course_advanced.edn")]
    (assert-trivial-passive-phases! doc {})
    (testing "passive-effects (+1500 max-cp, +100 max-overload) is untouched"
      (is (= [{:target :max-cp :operation :add :value 1500.0}
             {:target :max-overload :operation :add :value 100.0}]
             (:passive-effects doc))))))
