(ns cn.li.ac.content.ability.electromaster.railgun-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.railgun-fx :as railgun-fx]))

(defn- reset-fixture [f]
  (railgun-fx/reset-railgun-fx-for-test!)
  (f)
  (railgun-fx/reset-railgun-fx-for-test!))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-railgun-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (railgun-fx/init!)
      (is (= :railgun-shot (first @registered-level*)))
      (is (fn? (:enqueue-event-fn (second @registered-level*))))
      (is (= #{:railgun/fx-shot :railgun/fx-reflect}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-with-ctx-metadata-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (railgun-fx/init!)
      (@handler* "ctx-rail" :railgun/fx-shot {:mode :block-hit
                                                :start {:x 0.0 :y 64.0 :z 0.0}
                                                :end {:x 8.0 :y 64.0 :z 0.0}
                                                :world-id "minecraft:overworld"})
      (is (= [[:railgun-shot {:mode :block-hit
                              :start {:x 0.0 :y 64.0 :z 0.0}
                              :end {:x 8.0 :y 64.0 :z 0.0}
                              :world-id "minecraft:overworld"}
               {:ctx-id "ctx-rail" :channel :railgun/fx-shot}]]
             @enqueued*)))))

(deftest two-owners-keep-railgun-beams-independent-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.electromaster.railgun-fx/enqueue!)]
    (enqueue! (event "ctx-a" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                                :end {:x 6.0 :y 64.0 :z 0.0}}))
    (enqueue! (event "ctx-b" :railgun/fx-reflect {:start {:x 0.0 :y 65.0 :z 0.0}
                                                   :end {:x 6.0 :y 65.0 :z 0.0}}))
    (let [snapshot (railgun-fx/railgun-fx-snapshot)]
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-b"]))))
      (railgun-fx/clear-railgun-owner! [:ctx "ctx-a"])
      (let [after-clear (railgun-fx/railgun-fx-snapshot)]
        (is (nil? (get (:beam-effects after-clear) [:ctx "ctx-a"])))
        (is (= 1 (count (get (:beam-effects after-clear) [:ctx "ctx-b"]))))))))
