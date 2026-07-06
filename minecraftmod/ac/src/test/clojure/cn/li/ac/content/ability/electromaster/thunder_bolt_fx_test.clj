(ns cn.li.ac.content.ability.electromaster.thunder-bolt-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.thunder-bolt-fx :as tb-fx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (tb-fx/reset-thunder-bolt-fx-for-test!)
        (f)
        (finally
          (tb-fx/reset-thunder-bolt-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :thunder-bolt/fx-perform
   :owner-key [:ctx ctx-id]})

(deftest init-registers-thunder-bolt-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                     (swap! registered-topics* conj topic)
                                                     nil)]
      (tb-fx/init!)
      (is (= :thunder-bolt-strike (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:thunder-bolt/fx-perform} @registered-topics*)))))

(deftest fx-handler-routes-payload-to-level-effect-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                     (swap! handlers* assoc topic handler)
                                                     nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (tb-fx/init!)
      ((get @handlers* :thunder-bolt/fx-perform) "ctx-1" :thunder-bolt/fx-perform {:start {:x 0.0 :y 64.0 :z 0.0}
                                                    :end {:x 1.0 :y 65.0 :z 1.0}
                                                    :aoe-points [{:x 2.0 :y 65.0 :z 1.0}]})
      (is (= [[:thunder-bolt-strike
               {:owner-key [:ctx "ctx-1"]
                :ctx-id "ctx-1"
                :channel :thunder-bolt/fx-perform
                :start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 1.0 :y 65.0 :z 1.0}
                :aoe-points [{:x 2.0 :y 65.0 :z 1.0}]}
               {:ctx-id "ctx-1"
                :channel :thunder-bolt/fx-perform
                :owner-key [:ctx "ctx-1"]}]]
             @enqueued*)))))

(deftest enqueue-main-and-aoe-arcs-tick-and-build-plan-test
  (let [
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& args]
                                                               (swap! sounds* conj (last args))
                                                               nil)
                  rand-int (fn [_] 0)]
      (arc-beam/enqueue-for-test! :thunder-bolt-strike "ctx-main" :thunder-bolt/fx-perform
               {:start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 3.0 :y 64.0 :z 3.0}
                :aoe-points [{:x 4.0 :y 64.0 :z 2.0}
                             {:x 2.0 :y 64.0 :z 4.0}]})
      (is (= 5 (count (get (:arcs (tb-fx/thunder-bolt-fx-snapshot)) [:ctx "ctx-main"]))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.arc_strong" (:sound-id (first @sounds*))))
      (is (some? (arc-beam/effect-build-plan :thunder-bolt-strike {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      (dotimes [_ 30]
        (level-effects/update-effect-state! :thunder-bolt-strike
          (fn [store] (arc-beam/effect-tick-state! :level :thunder-bolt-strike store))))
      (is (empty? (:arcs (tb-fx/thunder-bolt-fx-snapshot))))
      (is (nil? (arc-beam/effect-build-plan :thunder-bolt-strike {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

(deftest two-owners-keep-independent-arc-queues-test
  (let [
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& args]
                                                               (swap! sounds* conj (last args))
                                                               nil)]
      (arc-beam/enqueue-for-test! :thunder-bolt-strike "ctx-a" :thunder-bolt/fx-perform
               {:start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 3.0 :y 64.0 :z 3.0}
                :aoe-points []})
      (arc-beam/enqueue-for-test! :thunder-bolt-strike "ctx-b" :thunder-bolt/fx-perform
               {:start {:x 10.0 :y 64.0 :z 0.0}
                :end {:x 13.0 :y 64.0 :z 3.0}
                :aoe-points []})
      (let [snapshot (tb-fx/thunder-bolt-fx-snapshot)]
        (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
               (set (keys (:arcs snapshot)))))
        (is (= 3 (count (get (:arcs snapshot) [:ctx "ctx-a"]))))
        (is (= 3 (count (get (:arcs snapshot) [:ctx "ctx-b"])))))
      (tb-fx/clear-thunder-bolt-owner! [:ctx "ctx-a"])
      (let [snapshot (tb-fx/thunder-bolt-fx-snapshot)]
        (is (nil? (get (:arcs snapshot) [:ctx "ctx-a"])))
        (is (= 3 (count (get (:arcs snapshot) [:ctx "ctx-b"])))))
      (is (= 2 (count @sounds*))))))

(deftest enqueue-ignores-invalid-payload-test
  (let [
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& args]
                                                               (swap! sounds* conj (last args))
                                                               nil)]
      (arc-beam/enqueue-for-test! :thunder-bolt-strike "ctx-invalid" :thunder-bolt/fx-perform {:start {:x 0.0 :y 64.0 :z 0.0}})
      (is (empty? (:arcs (tb-fx/thunder-bolt-fx-snapshot))))
      (is (empty? @sounds*))
      (is (nil? (arc-beam/effect-build-plan :thunder-bolt-strike {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

(deftest thunder-bolt-fx-snapshot-defaults-without-registered-state-test
  (is (= {:arcs {}}
         (tb-fx/thunder-bolt-fx-snapshot))))
