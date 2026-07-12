(ns cn.li.ac.content.ability.electromaster.railgun-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.railgun-fx :as railgun-fx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (railgun-fx/reset-fx-for-test!)
        (f)
        (finally
          (railgun-fx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-railgun-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (railgun-fx/init!)
      (is (= :railgun-shot (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:railgun/fx-shot :railgun/fx-reflect}
             @registered-topics*)))))

(deftest fx-handler-routes-with-ctx-metadata-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-shot) "ctx-rail" :railgun/fx-shot {:mode :block-hit
                                               :start {:x 0.0 :y 64.0 :z 0.0}
                                               :end {:x 8.0 :y 64.0 :z 0.0}
                                               :world-id "minecraft:overworld"})
      (is (= [[:railgun-shot {:mode :block-hit
                              :owner-key [:ctx "ctx-rail"]
                              :ctx-id "ctx-rail"
                              :channel :railgun/fx-shot
                              :start {:x 0.0 :y 64.0 :z 0.0}
                              :end {:x 8.0 :y 64.0 :z 0.0}
                              :world-id "minecraft:overworld"}
               {:ctx-id "ctx-rail"
                :channel :railgun/fx-shot
                :owner-key [:ctx "ctx-rail"]}]]
             @enqueued*)))))

(deftest enqueue-perform-adds-beam-and-builds-plan-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-main" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                           :end {:x 3.0 :y 64.0 :z 3.0}
                                           :hit-distance 18.0})
    (let [plan (arc-beam/effect-build-plan :railgun-shot {:x 0.0 :y 65.0 :z 0.0} nil 0)]
      (is (some? plan))
      (is (seq (:ops plan))))
    (is (= 1 (count (get (:beam-effects (railgun-fx/fx-snapshot)) [:ctx "ctx-main"]))))))

(deftest two-owners-keep-railgun-beams-independent-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-a" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                         :end {:x 6.0 :y 64.0 :z 0.0}})
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-b" :railgun/fx-reflect {:start {:x 0.0 :y 65.0 :z 0.0}
                                           :end {:x 6.0 :y 65.0 :z 0.0}})
    (let [snapshot (railgun-fx/fx-snapshot)]
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-b"]))))
      (railgun-fx/clear-fx-owner! [:ctx "ctx-a"])
      (let [after-clear (railgun-fx/fx-snapshot)]
        (is (nil? (get (:beam-effects after-clear) [:ctx "ctx-a"])))
        (is (= 1 (count (get (:beam-effects after-clear) [:ctx "ctx-b"]))))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:beam-effects {}}
         (railgun-fx/fx-snapshot))))
