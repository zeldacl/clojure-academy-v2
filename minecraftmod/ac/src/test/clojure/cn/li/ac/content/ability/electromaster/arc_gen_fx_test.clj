(ns cn.li.ac.content.ability.electromaster.arc-gen-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.arc-gen-fx :as arc-fx]))

(defn- invoke-level-enqueue! [ctx-id channel payload]
  (arc-beam/enqueue-for-test! :arc-gen ctx-id channel payload))

(defn- with-fresh-arc-gen-fx-runtime [f]
  (try
    (level-effects/reset-level-effect-registry-for-test!)
    (arc-fx/reset-arc-gen-fx-for-test!)
    (arc-fx/init!)
    (f)
    (finally
      (arc-fx/reset-arc-gen-fx-for-test!)
      (level-effects/reset-level-effect-registry-for-test!))))

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
                                                        (swap! enqueued* conj (into [effect-id ctx-id channel payload] opts))
                                                        nil)]
      (arc-fx/init!)
      ((get @handlers* :arc-gen/fx-perform) "ctx-arc" :arc-gen/fx-perform {:start {:x 1.0 :y 2.0 :z 3.0}
                                                 :end {:x 4.0 :y 5.0 :z 6.0}
                                                 :hit-type :entity})
      (is (= [[:arc-gen "ctx-arc" :arc-gen/fx-perform
               {:mode :perform
                :start {:x 1.0 :y 2.0 :z 3.0}
                :end {:x 4.0 :y 5.0 :z 6.0}
                :hit-type :entity}
               :owner-key [:ctx "ctx-arc"]]]
             @enqueued*)))))

(deftest enqueue-perform-adds-arc-and-plays-sound-test
  (let [spec (arc-beam/build-spec
               {:effect-id :arc-gen
                :sound-id "my_mod:em.arc_weak"
                :arc-life 10
                :arc-pattern :weak
                :channels []})
        build-plan (:build-plan-fn (:level spec))
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                               (swap! sounds* conj payload)
                                                               nil)]
      (invoke-level-enqueue! "ctx-main" :arc-gen/fx-perform
        {:mode :perform
         :start {:x 0.0 :y 64.0 :z 0.0}
         :end {:x 3.0 :y 64.0 :z 3.0}
         :hit-type :block})
      (let [plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
        (is (some? plan))
        (is (seq (:ops plan))))
      (is (= 1 (count (get (:arcs (arc-fx/arc-gen-fx-snapshot)) [:ctx "ctx-main"]))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.arc_weak" (:sound-id (first @sounds*)))))))

(deftest two-owners-keep-arc-gen-queues-independent-test
  (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
    (invoke-level-enqueue! "ctx-a" :arc-gen/fx-perform
      {:mode :perform
       :start {:x 0.0 :y 0.0 :z 0.0}
       :end {:x 1.0 :y 0.0 :z 0.0}})
    (invoke-level-enqueue! "ctx-b" :arc-gen/fx-perform
      {:mode :perform
       :start {:x 0.0 :y 1.0 :z 0.0}
       :end {:x 1.0 :y 1.0 :z 0.0}})
    (let [snapshot (arc-fx/arc-gen-fx-snapshot)]
      (is (= 1 (count (get (:arcs snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:arcs snapshot) [:ctx "ctx-b"]))))
      (arc-fx/clear-arc-gen-owner! [:ctx "ctx-a"])
      (let [after-clear (arc-fx/arc-gen-fx-snapshot)]
        (is (nil? (get (:arcs after-clear) [:ctx "ctx-a"])))
        (is (= 1 (count (get (:arcs after-clear) [:ctx "ctx-b"]))))))))

(deftest arc-gen-fx-snapshot-default-without-registered-state-test
  (arc-fx/reset-arc-gen-fx-for-test!)
  (is (= {:arcs {}}
         (arc-fx/arc-gen-fx-snapshot))))
