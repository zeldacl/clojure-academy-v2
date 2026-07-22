(ns cn.li.ac.content.ability.electromaster.mag-manip-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.content.ability.electromaster.mag-manip :as mag-manip]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(defn- skill-actions []
  (:actions (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-manip
                                 'mag-manip))))

(defn- mk-context-store [ctx-id seed]
  (atom {ctx-id seed}))

(deftest down-world-capture-requires-successful-break-test
  (let [ctx-id "ctx-world"
        contexts* (mk-context-store ctx-id {})
        down! (get (skill-actions) :down!)]
    (with-redefs [mag-manip/skill-exp (fn [_] 0.8)
                  mag-manip/pick-up-target-block (fn [_ _]
                                                   {:world-id "w"
                                                    :x 1 :y 2 :z 3
                                                    :block-id "minecraft:iron_block"})
                  entity/player-get-main-hand-item-id (fn [_] nil)
                  block-manip/available? (constantly true)
                  block-manip/can-break-block? (fn [& _] true)
                  block-manip/break-block! (fn [& _] false)
                  ctx/get-context (fn
                                    ([id] (get @contexts* id))
                                    ([_owner id] (get @contexts* id)))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                                      (swap! contexts* update id
                                                             (fn [ctx]
                                                               (assoc ctx :skill-state
                                                                      (if (and (= f identity) (= 1 (count args)))
                                                                        (first args)
                                                                        (apply f (or (:skill-state ctx) {}) args)))))
                                                      nil)
                  fx/send! (fn [& _] nil)]
      (cb/apply-invoke down! :player-id "p1" :ctx-id ctx-id :player-ref {:id "p"}))
    (is (= :capture-failed (get-in @contexts* [ctx-id :skill-state :mode])))))

(deftest up-too-far-rolls-back-hand-item-test
  (let [ctx-id "ctx-hand"
        contexts* (mk-context-store ctx-id
                                    {:skill-state {:mode :holding
                                                   :focus {:x 10.0 :y 0.0 :z 10.0}
                                                   :held-block {:block-id "minecraft:iron_block"
                                                                :from-hand? true
                                                                :from-world? false}}})
        give-calls* (atom [])
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)
        up! (get (skill-actions) :up!)]
    (with-redefs [mag-manip/skill-exp (fn [_] 0.5)
                  mag-manip/max-hold-distance-sq (fn [] 1.0)
                  skill-effects/player-path (fn [& _] {:x 0.0 :y 0.0 :z 0.0})
                  entity/player-creative? (fn [_] false)
                  pitem/stack-by-id (fn [item-id count]
                                                  {:item-id item-id :count count})
                  entity/player-give-item-stack! (fn [player stack]
                                                   (swap! give-calls* conj [player stack])
                                                   true)
                  ctx/get-context (fn
                                    ([id] (get @contexts* id))
                                    ([_owner id] (get @contexts* id)))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                                        (swap! contexts* update id
                                                               (fn [ctx]
                                                                 (assoc ctx :skill-state
                                                                        (if (and (= f identity) (= 1 (count args)))
                                                                          (first args)
                                                                          (apply f (or (:skill-state ctx) {}) args)))))
                                                        nil)
                  fx/send! send!]
      (cb/apply-invoke up! :player-id "p1" :ctx-id ctx-id :player-ref {:id "p"} :cost-ok? true))
    (is (= 1 (count @give-calls*)))
    (is (= :too-far (get-in @contexts* [ctx-id :skill-state :mode])))
    (is (= [[ctx-id :mag-manip/fx-end :end {:reason :too-far}]]
           @calls*))))

(deftest up-success-applies-cooldown-and-exp-test
  (let [ctx-id "ctx-success"
        contexts* (mk-context-store ctx-id
                                    {:skill-state {:mode :holding
                                                   :focus {:x 1.0 :y 2.0 :z 3.0}
                                                   :held-block {:block-id "minecraft:iron_block"
                                                                :from-world? true
                                                                :world-id "w"
                                                                :source-x 1 :source-y 2 :source-z 3}}})
        cooldown* (atom [])
        exp* (atom [])
        up! (get (skill-actions) :up!)]
    (with-redefs [mag-manip/skill-exp (fn [_] 0.6)
                  mag-manip/max-hold-distance-sq (fn [] 100.0)
                  skill-effects/player-path (fn [& _] {:x 1.0 :y 2.0 :z 3.0})
                  mag-manip/look-dir (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  mag-manip/try-place-thrown-block! (fn [& _] true)
                  skill-effects/set-main-cooldown! (fn [pid sid ticks]
                                                     (swap! cooldown* conj [pid sid ticks])
                                                     nil)
                  skill-effects/add-skill-exp! (fn [pid sid amount]
                                                 (swap! exp* conj [pid sid amount])
                                                 nil)
                  ctx/get-context (fn
                                    ([id] (get @contexts* id))
                                    ([_owner id] (get @contexts* id)))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                                        (swap! contexts* update id
                                                               (fn [ctx]
                                                                 (assoc ctx :skill-state
                                                                        (if (and (= f identity) (= 1 (count args)))
                                                                          (first args)
                                                                          (apply f (or (:skill-state ctx) {}) args)))))
                                                        nil)
                  fx/send! (fn [& _] nil)]
      (cb/apply-invoke up! :player-id "p1" :ctx-id ctx-id :player-ref {:id "p"} :cost-ok? true))
    (is (= :thrown (get-in @contexts* [ctx-id :skill-state :mode])))
    (is (= 1 (count @cooldown*)))
    (is (= 1 (count @exp*)))))
