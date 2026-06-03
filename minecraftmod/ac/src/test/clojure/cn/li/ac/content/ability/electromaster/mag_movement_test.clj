(ns cn.li.ac.content.ability.electromaster.mag-movement-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.motion :as motion-op]
            [cn.li.ac.ability.effects.state :as state-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.electromaster.mag-movement :as mag-movement]
            [cn.li.mcmod.platform.raycast :as raycast]))

(def ^:private test-context-owner {:logical-side :server :session-id :test-session})

(defn- with-test-context-owner [f]
  (binding [ctx/*context-owner* test-context-owner]
    (ps-fix/with-test-player-state-owner f)))

(defn- skill-actions []
  (:actions (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-movement
                                 'mag-movement-skill))))

(defn- skill-def []
  (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-movement
                       'mag-movement-skill)))

(defn- mk-context-store [ctx-id seed]
  (atom {ctx-id seed}))

(defn- mock-update-skill-state-root! [contexts* id f & args]
  (swap! contexts* update id
         (fn [ctx]
           (assoc ctx :skill-state
                  (if (and (= f identity) (= 1 (count args)))
                    (first args)
                    (apply f (or (:skill-state ctx) {}) args))))))

(defn- mag-private-var [sym]
  (find-var (symbol "cn.li.ac.content.ability.electromaster.mag-movement" (name sym))))

(defn- skill-state-redef-pairs [contexts*]
  [(mag-private-var 'set-skill-state-root!)
   (fn [ctx-id state-map]
     (mock-update-skill-state-root! contexts* ctx-id identity state-map)
     nil)
   (mag-private-var 'set-skill-state!)
   (fn [ctx-id k v]
     (swap! contexts* update ctx-id
            (fn [ctx]
              (let [path (if (vector? k) k [k])]
                (update ctx :skill-state #(assoc-in (or % {}) path v)))))
     nil)
   (mag-private-var 'clear-skill-state!)
   (fn [ctx-id]
     (swap! contexts* update ctx-id dissoc :skill-state)
     nil)
   ctx/get-context (fn [id] (get @contexts* id))])

(deftest pattern-is-hold-channel-and-cost-fail-present-test
  (let [spec (skill-def)]
    (is (= :hold-channel (:pattern spec)))
    (is (fn? (get-in spec [:actions :cost-fail!])))))

(deftest cost-fail-down-finalizes-without-exp-test
  (let [ctx-id "ctx-down-fail"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target false :finalized? false}})
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        terminated* (atom [])
        cost-fail! (get (skill-actions) :cost-fail!)]
    (with-redefs (into (skill-state-redef-pairs contexts*)
                       [ctx/terminate-context! (fn [id _]
                                                (swap! terminated* conj id)
                                                nil)
                        fx/send! send!
                        motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                        skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                       (swap! exp* conj [player-id skill-id amount])
                                                       nil)])
      (with-test-context-owner
        #(cost-fail! {:ctx-id ctx-id :player-id "p1" :cost-stage :down})))
    (is (empty? @exp*))
    (is (= [[ctx-id :mag-movement/fx-end :end nil]] @calls*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest down-no-target-terminates-without-exp-test
  (let [ctx-id "ctx-no-target"
        contexts* (mk-context-store ctx-id {})
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        terminated* (atom [])
        down! (get (skill-actions) :down!)]
    (with-redefs (into (skill-state-redef-pairs contexts*)
                       [mag-movement/resolve-target (fn [_] nil)
                        mag-movement/player-pos (fn [_] {:x 0.0 :y 0.0 :z 0.0})
                        ctx/terminate-context! (fn [id _]
                                                (swap! terminated* conj id)
                                                nil)
                        fx/send! send!
                        motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                        skill-effects/add-skill-exp! (fn [& args]
                                                       (swap! exp* conj args)
                                                       nil)])
      (with-test-context-owner
        #(down! {:ctx-id ctx-id :player-id "p1" :exp 0.4})))
    (is (empty? @exp*))
    (is (= [[ctx-id :mag-movement/fx-end :end nil]] @calls*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest tick-cost-fail-finalizes-once-with-exp-test
  (let [ctx-id "ctx-tick-fail"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target true
                                                          :finalized? false
                                                          :target-kind :block
                                                          :target-x 1.0
                                                          :target-y 0.0
                                                          :target-z 0.0
                                                          :motion-x 0.0
                                                          :motion-y 0.0
                                                          :motion-z 0.0
                                                          :movement-ticks 0
                                                          :start-x 0.0
                                                          :start-y 0.0
                                                          :start-z 0.0
                                                          :overload-floor 10.0}})
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        terminated* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs (into (skill-state-redef-pairs contexts*)
                       [mag-movement/player-pos (fn [_] {:x 3.0 :y 4.0 :z 0.0})
                        mag-movement/cfg-double (fn [k]
                                                   (case k
                                                     :progression.exp-min 0.005
                                                     :progression.exp-distance-scale 0.0011
                                                     :movement.acceleration 0.08
                                                     :targeting.target-update-radius 4.0
                                                     0.0))
                        ctx/terminate-context! (fn [id _]
                                                (swap! terminated* conj id)
                                                nil)
                        fx/send! send!
                        motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                        state-op/execute-overload-floor! (fn [_evt _params] nil)
                        skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                       (swap! exp* conj [player-id skill-id amount])
                                                       nil)])
      (with-test-context-owner
        #(do
           (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? false})
           (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? false}))))
    (is (= 1 (count @exp*)))
    (is (= 1 (count (filter #(= :mag-movement/fx-end (second %)) @calls*))))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest tick-target-lost-finalizes-with-exp-test
  (let [ctx-id "ctx-target-lost"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target true
                                                          :finalized? false
                                                          :target-kind :entity
                                                          :target-world-id "w"
                                                          :target-entity-uuid "e1"
                                                          :target-x 2.0
                                                          :target-y 0.0
                                                          :target-z 0.0
                                                          :motion-x 0.0
                                                          :motion-y 0.0
                                                          :motion-z 0.0
                                                          :movement-ticks 2
                                                          :start-x 0.0
                                                          :start-y 0.0
                                                          :start-z 0.0
                                                          :overload-floor 10.0}})
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        terminated* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs (into (skill-state-redef-pairs contexts*)
                       [mag-movement/update-entity-target (fn [_] nil)
                        mag-movement/player-pos (fn [_] {:x 0.0 :y 0.0 :z 5.0})
                        mag-movement/cfg-double (fn [k]
                                                   (case k
                                                     :progression.exp-min 0.005
                                                     :progression.exp-distance-scale 0.0011
                                                     :targeting.target-update-radius 4.0
                                                     0.0))
                        ctx/terminate-context! (fn [id _]
                                                (swap! terminated* conj id)
                                                nil)
                        fx/send! send!
                        motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                        state-op/execute-overload-floor! (fn [_evt _params] nil)
                        skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                       (swap! exp* conj [player-id skill-id amount])
                                                       nil)])
      (with-test-context-owner
        #(tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? true})))
    (is (= 1 (count @exp*)))
    (is (= [[ctx-id :mag-movement/fx-end :end nil]] @calls*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest resolve-target-uses-hit-vec-and-eye-height-test
  (with-redefs [mag-movement/is-metal-block? (fn [_] true)
                mag-movement/is-metal-entity? (fn [_] true)
                raycast/available? (constantly true)
                raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                geom/eye-pos (fn [_] {:x 10.0 :y 20.0 :z 30.0})
                geom/world-id-of (fn [_] "w")
                mag-movement/cfg-double (fn [k]
                                          (case k
                                            :targeting.range 25.0
                                            0.0))
                raycast/raycast-combined* (fn [_ _ _ _ _ _ _ _]
                                           {:hit-type :block
                                            :block-id "minecraft:iron_block"
                                            :x 1.25 :y 2.5 :z 3.75})]
    (is (= {:target-kind :block
            :target-world-id "w"
            :target-x 1.25
            :target-y 2.5
            :target-z 3.75
            :target-block-id "minecraft:iron_block"}
           (@#'cn.li.ac.content.ability.electromaster.mag-movement/resolve-target "p1"))))
  (with-redefs [mag-movement/is-metal-block? (fn [_] true)
                mag-movement/is-metal-entity? (fn [_] true)
                raycast/available? (constantly true)
                raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                geom/eye-pos (fn [_] {:x 10.0 :y 20.0 :z 30.0})
                geom/world-id-of (fn [_] "w")
                mag-movement/cfg-double (fn [k]
                                          (case k
                                            :targeting.range 25.0
                                            0.0))
                raycast/raycast-combined* (fn [_ _ _ _ _ _ _ _]
                                           {:hit-type :entity
                                            :type "minecraft:iron_golem"
                                            :uuid "e-1"
                                            :x 4.0 :y 5.0 :z 6.0
                                            :eye-height 1.9})]
    (is (= {:target-kind :entity
            :target-world-id "w"
            :target-entity-uuid "e-1"
            :target-entity-type "minecraft:iron_golem"
            :target-x 4.0
            :target-y 6.9
            :target-z 6.0}
           (@#'cn.li.ac.content.ability.electromaster.mag-movement/resolve-target "p1")))))

(deftest up-then-abort-does-not-double-finalize-test
  (let [ctx-id "ctx-up-abort"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target true
                                                          :finalized? false
                                                          :target-kind :block
                                                          :target-x 1.0
                                                          :target-y 0.0
                                                          :target-z 0.0
                                                          :movement-ticks 5
                                                          :start-x 0.0
                                                          :start-y 0.0
                                                          :start-z 0.0}})
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        terminated* (atom [])
        up! (get (skill-actions) :up!)
        abort! (get (skill-actions) :abort!)]
    (with-redefs (into (skill-state-redef-pairs contexts*)
                       [mag-movement/player-pos (fn [_] {:x 1.0 :y 0.0 :z 0.0})
                        mag-movement/cfg-double (fn [k]
                                                   (case k
                                                     :progression.exp-min 0.005
                                                     :progression.exp-distance-scale 0.0011
                                                     0.0))
                        ctx/terminate-context! (fn [id _]
                                                (swap! terminated* conj id)
                                                nil)
                        fx/send! send!
                        motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                        skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                       (swap! exp* conj [player-id skill-id amount])
                                                       nil)])
      (with-test-context-owner
        #(do
           (up! {:ctx-id ctx-id :player-id "p1"})
           (abort! {:ctx-id ctx-id :player-id "p1"}))))
    (is (= 1 (count @exp*)))
    (is (= 1 (count @calls*)))
    (is (= [ctx-id] @terminated*))))

