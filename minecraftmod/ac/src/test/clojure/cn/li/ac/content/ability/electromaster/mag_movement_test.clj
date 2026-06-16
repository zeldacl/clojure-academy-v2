(ns cn.li.ac.content.ability.electromaster.mag-movement-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.ac.test.support.skill-context :as skill-ctx]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.motion :as motion-op]
            [cn.li.ac.ability.effects.state :as state-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.content.ability.electromaster.mag-movement :as mag-movement]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- skill-actions []
  (:actions (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-movement
                                 'mag-movement-skill))))

(defn- skill-def []
  (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-movement
                       'mag-movement-skill)))

(defn- context-mocks
  [seed & {:keys [terminate-calls*]}]
  (let [ctx* (atom seed)
        get-context (fn
                      ([_ctx-id] @ctx*)
                      ([_owner _ctx-id] @ctx*))
        update-skill-state-root! (fn [_ctx-id f & args]
                                   (swap! ctx*
                                          (fn [ctx-data]
                                            (let [current (or (:skill-state ctx-data) {})
                                                  next-state (if (and (= f identity) (= 1 (count args)))
                                                               (first args)
                                                               (apply f current args))]
                                              (assoc ctx-data :skill-state next-state))))
                                   nil)
        assoc-skill-state! (fn [_ctx-id k v]
                             (swap! ctx*
                                    (fn [ctx-data]
                                      (let [path (if (vector? k) k [k])]
                                        (update ctx-data :skill-state #(assoc-in (or % {}) path v)))))
                             nil)
        clear-skill-state! (fn [_ctx-id]
                             (swap! ctx* dissoc :skill-state)
                             nil)
        terminate-context! (fn
                             ([ctx-id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj ctx-id))
                              nil)
                             ([_owner ctx-id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj ctx-id))
                              nil))]
    {:ctx* ctx*
     :get-context get-context
     :update-skill-state-root! update-skill-state-root!
     :assoc-skill-state! assoc-skill-state!
     :clear-skill-state! clear-skill-state!
     :terminate-context! terminate-context!}))

(defn- with-mag-env [f]
  (skill-ctx/with-server-skill-context f))

(deftest pattern-is-hold-channel-and-cost-fail-present-test
  (let [spec (skill-def)]
    (is (= :hold-channel (:pattern spec)))
    (is (fn? (get-in spec [:actions :cost-fail!])))))

(deftest cost-fail-down-finalizes-without-exp-test
  (let [ctx-id "ctx-down-fail"
        terminated* (atom [])
        {:keys [ctx* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks {:skill-state {:has-target false :finalized? false}}
                       :terminate-calls* terminated*)
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        cost-fail! (get (skill-actions) :cost-fail!)]
    (with-mag-env
      #(with-redefs [ctx/get-context get-context
                    ctx/terminate-context! terminate-context!
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! send!
                    motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! exp* conj [player-id skill-id amount])
                                                   nil)]
         (cost-fail! {:ctx-id ctx-id :player-id "p1" :cost-stage :down})))
    (is (empty? @exp*))
    (is (= [[ctx-id :mag-movement/fx-end :end nil]] @calls*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (:skill-state @ctx*)))))

(deftest down-no-target-terminates-without-exp-test
  (let [ctx-id "ctx-no-target"
        terminated* (atom [])
        {:keys [ctx* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks {} :terminate-calls* terminated*)
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        down! (get (skill-actions) :down!)]
    (with-mag-env
      #(with-redefs [mag-movement/resolve-target (fn [_] nil)
                    mag-movement/player-pos (fn [_] {:x 0.0 :y 0.0 :z 0.0})
                    ctx/get-context get-context
                    ctx/terminate-context! terminate-context!
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! send!
                    motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                    skill-effects/add-skill-exp! (fn [& args]
                                                   (swap! exp* conj args)
                                                   nil)]
         (down! {:ctx-id ctx-id :player-id "p1" :exp 0.4})))
    (is (empty? @exp*))
    (is (= [[ctx-id :mag-movement/fx-end :end nil]] @calls*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (:skill-state @ctx*)))))

(deftest tick-cost-fail-finalizes-once-with-exp-test
  (let [ctx-id "ctx-tick-fail"
        terminated* (atom [])
        {:keys [ctx* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks {:skill-state {:has-target true
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
                                       :overload-floor 10.0}}
                       :terminate-calls* terminated*)
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-mag-env
      #(with-redefs [mag-movement/player-pos (fn [_] {:x 3.0 :y 4.0 :z 0.0})
                    mag-movement/cfg-double (fn [k]
                                             (case k
                                               :progression.exp-min 0.005
                                               :progression.exp-distance-scale 0.0011
                                               :movement.acceleration 0.08
                                               :targeting.target-update-radius 4.0
                                               0.0))
                    ctx/get-context get-context
                    ctx/terminate-context! terminate-context!
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! send!
                    motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                    state-op/execute-overload-floor! (fn [_evt _params] nil)
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! exp* conj [player-id skill-id amount])
                                                   nil)]
         (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? false})
         (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? false})))
    (is (= 1 (count @exp*)))
    (is (= 1 (count (filter #(= :mag-movement/fx-end (second %)) @calls*))))
    (is (= [ctx-id] @terminated*))
    (is (nil? (:skill-state @ctx*)))))

(deftest tick-target-lost-finalizes-with-exp-test
  (let [ctx-id "ctx-target-lost"
        terminated* (atom [])
        {:keys [ctx* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks {:skill-state {:has-target true
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
                                       :overload-floor 10.0}}
                       :terminate-calls* terminated*)
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-mag-env
      #(with-redefs [mag-movement/update-entity-target (fn [_] nil)
                    mag-movement/player-pos (fn [_] {:x 0.0 :y 0.0 :z 5.0})
                    mag-movement/cfg-double (fn [k]
                                             (case k
                                               :progression.exp-min 0.005
                                               :progression.exp-distance-scale 0.0011
                                               :targeting.target-update-radius 4.0
                                               0.0))
                    ctx/get-context get-context
                    ctx/terminate-context! terminate-context!
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! send!
                    motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                    state-op/execute-overload-floor! (fn [_evt _params] nil)
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! exp* conj [player-id skill-id amount])
                                                   nil)]
         (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? true})))
    (is (= 1 (count @exp*)))
    (is (= [[ctx-id :mag-movement/fx-end :end nil]] @calls*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (:skill-state @ctx*)))))

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
        terminated* (atom [])
        {:keys [ctx* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks {:skill-state {:has-target true
                                       :finalized? false
                                       :target-kind :block
                                       :target-x 1.0
                                       :target-y 0.0
                                       :target-z 0.0
                                       :movement-ticks 5
                                       :start-x 0.0
                                       :start-y 0.0
                                       :start-z 0.0}}
                       :terminate-calls* terminated*)
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        exp* (atom [])
        up! (get (skill-actions) :up!)
        abort! (get (skill-actions) :abort!)]
    (with-mag-env
      #(with-redefs [mag-movement/player-pos (fn [_] {:x 1.0 :y 0.0 :z 0.0})
                    mag-movement/cfg-double (fn [k]
                                             (case k
                                               :progression.exp-min 0.005
                                               :progression.exp-distance-scale 0.0011
                                               0.0))
                    ctx/get-context get-context
                    ctx/terminate-context! terminate-context!
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! send!
                    motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! exp* conj [player-id skill-id amount])
                                                   nil)]
         (up! {:ctx-id ctx-id :player-id "p1"})
         (abort! {:ctx-id ctx-id :player-id "p1"})))
    (is (= 1 (count @exp*)))
    (is (= 1 (count @calls*)))
    (is (= [ctx-id] @terminated*))))
