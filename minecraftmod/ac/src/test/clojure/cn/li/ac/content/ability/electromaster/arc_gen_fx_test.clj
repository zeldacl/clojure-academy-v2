(ns cn.li.ac.content.ability.electromaster.arc-gen-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.arc-gen-fx :as arc-fx]))

(defn- with-fresh-arc-gen-fx-runtime [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (arc-fx/reset-arc-gen-fx-for-test!)
        (f)
        (finally
          (arc-fx/reset-arc-gen-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :arc-gen/fx-perform
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-arc-gen-fx-runtime)

(deftest init-registers-arc-gen-fx-channel-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                     (swap! registered-topics* conj topic)
                                                     nil)]
      (arc-fx/init!)
      (is (= :arc-gen (first @registered-level*)))
      (is (= #{:arc-gen/fx-perform} @registered-topics*)))))

(deftest fx-handler-routes-perform-payload-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                     (swap! handlers* assoc topic handler)
                                                     nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (arc-fx/init!)
      ((get @handlers* :arc-gen/fx-perform) "ctx-arc" :arc-gen/fx-perform {:start {:x 1.0 :y 2.0 :z 3.0}
                                                 :end {:x 4.0 :y 5.0 :z 6.0}
                                                 :hit-type :entity})
      (is (= [[:arc-gen {:mode :perform
                         :owner-key [:ctx "ctx-arc"]
                         :ctx-id "ctx-arc"
                         :channel :arc-gen/fx-perform
                         :start {:x 1.0 :y 2.0 :z 3.0}
                         :end {:x 4.0 :y 5.0 :z 6.0}
                         :hit-type :entity}
               {:ctx-id "ctx-arc"
                :channel :arc-gen/fx-perform
                :owner-key [:ctx "ctx-arc"]}]]
             @enqueued*)))))

(deftest enqueue-perform-adds-arc-and-plays-sound-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/enqueue-state!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                               (swap! sounds* conj payload)
                                                               nil)]
      (level-effects/update-effect-state! :arc-gen enqueue-state!
        (event "ctx-main"
               {:mode :perform
                :start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 3.0 :y 64.0 :z 3.0}
                :hit-type :block}))
      (let [plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
        (is (some? plan))
        (is (seq (:ops plan))))
      (is (= 1 (count (get (:arcs (arc-fx/arc-gen-fx-snapshot)) [:ctx "ctx-main"]))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.arc_weak" (:sound-id (first @sounds*)))))))

(deftest two-owners-keep-arc-gen-queues-independent-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/enqueue-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (level-effects/update-effect-state! :arc-gen enqueue-state!
        (event "ctx-a" {:mode :perform
                         :start {:x 0.0 :y 0.0 :z 0.0}
                         :end {:x 1.0 :y 0.0 :z 0.0}}))
      (level-effects/update-effect-state! :arc-gen enqueue-state!
        (event "ctx-b" {:mode :perform
                         :start {:x 0.0 :y 1.0 :z 0.0}
                         :end {:x 1.0 :y 1.0 :z 0.0}}))
      (let [snapshot (arc-fx/arc-gen-fx-snapshot)]
        (is (= 1 (count (get (:arcs snapshot) [:ctx "ctx-a"]))))
        (is (= 1 (count (get (:arcs snapshot) [:ctx "ctx-b"]))))
        (arc-fx/clear-arc-gen-owner! [:ctx "ctx-a"])
        (let [after-clear (arc-fx/arc-gen-fx-snapshot)]
          (is (nil? (get (:arcs after-clear) [:ctx "ctx-a"])))
          (is (= 1 (count (get (:arcs after-clear) [:ctx "ctx-b"])))))))))

(deftest arc-gen-fx-snapshot-default-without-registered-state-test
  (is (= {:arcs {}}
         (arc-fx/arc-gen-fx-snapshot))))
