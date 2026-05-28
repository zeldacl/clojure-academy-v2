(ns cn.li.ac.content.ability.electromaster.current-charging-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]))

(defn- with-fresh-current-charging-fx-runtime [f]
  (current-charging-fx/call-with-current-charging-fx-runtime
    (current-charging-fx/create-current-charging-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (current-charging-fx/reset-current-charging-fx-for-test!))))))

(use-fixtures :each with-fresh-current-charging-fx-runtime)

(deftest init-registers-current-charging-fx-channels-test
  (let [registered* (atom nil)]
    (with-redefs [fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered* {:channels channels
                                                                           :handler handler})
                                                      nil)]
      (current-charging-fx/init!)
      (is (= [:current-charging/fx-start
              :current-charging/fx-update
              :current-charging/fx-end]
             (:channels @registered*)))
      (is (fn? (:handler @registered*))))))

(deftest fx-handler-updates-state-and-queues-loop-sound-test
  (let [queued* (atom [])
        handler* (atom nil)]
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (with-redefs [fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! queued* conj payload)
                                                              nil)]
      (current-charging-fx/init!)
      (@handler* "ctx-1" :current-charging/fx-start {:is-item true})
      (is (true? (:active? (current-charging-fx/current-state))))
      (is (true? (:active? (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (true? (:is-item (current-charging-fx/current-state))))
      (is (= 1 (count @queued*)))
      (@handler* "ctx-1" :current-charging/fx-update {:is-item true
                                                       :good? true
                                                       :charge-ticks 20
                                                       :target {:x 1.0 :y 2.0 :z 3.0}
                                                       :block-pos [1 2 3]
                                                       :charged 4.0})
      (is (= 20 (:charge-ticks (current-charging-fx/current-state))))
      (is (= 0.5 (:charge-ratio (current-charging-fx/current-state))))
      (is (= {:x 1.0 :y 2.0 :z 3.0} (:target (current-charging-fx/current-state))))
      (is (= [1 2 3] (:block-pos (current-charging-fx/current-state))))
      (@handler* "ctx-1" :current-charging/fx-end {:is-item true})
      (is (false? (:active? (current-charging-fx/current-state))))
      (is (true? (:blending? (current-charging-fx/current-state)))))))

(deftest two-owners-keep-current-charging-state-independent-test
  (let [queued* (atom [])
        handler* (atom nil)]
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (with-redefs [fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! queued* conj payload)
                                                              nil)]
      (current-charging-fx/init!)
      (@handler* "ctx-a" :current-charging/fx-start {:is-item false})
      (@handler* "ctx-b" :current-charging/fx-start {:is-item true})
      (@handler* "ctx-a" :current-charging/fx-update {:good? true :charge-ticks 10})
      (@handler* "ctx-b" :current-charging/fx-update {:good? false :charge-ticks 30})
      (let [snapshot (current-charging-fx/current-charging-fx-snapshot)
            state-a (get (:states snapshot) [:ctx "ctx-a"])
            state-b (get (:states snapshot) [:ctx "ctx-b"])]
        (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
               (set (keys (:states snapshot)))))
        (is (= 10 (:charge-ticks state-a)))
        (is (= 0.25 (:charge-ratio state-a)))
        (is (= 30 (:charge-ticks state-b)))
        (is (= 0.75 (:charge-ratio state-b))))
      (@handler* "ctx-a" :current-charging/fx-end {:is-item false})
      (is (false? (:active? (current-charging-fx/current-state [:ctx "ctx-a"]))))
      (is (true? (:blending? (current-charging-fx/current-state [:ctx "ctx-a"]))))
      (is (true? (:active? (current-charging-fx/current-state [:ctx "ctx-b"]))))
      (current-charging-fx/clear-current-charging-owner! [:ctx "ctx-a"])
      (let [snapshot (current-charging-fx/current-charging-fx-snapshot)]
        (is (nil? (get (:states snapshot) [:ctx "ctx-a"])))
        (is (= 30 (:charge-ticks (get (:states snapshot) [:ctx "ctx-b"])))))
      (is (= 2 (count @queued*))))))

(deftest current-charging-fx-runtime-isolation-test
  (let [runtime-a (current-charging-fx/create-current-charging-fx-runtime)
        runtime-b (current-charging-fx/create-current-charging-fx-runtime)
        handler* (atom nil)]
    (with-redefs [fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (current-charging-fx/call-with-current-charging-fx-runtime runtime-a current-charging-fx/init!)
      (current-charging-fx/call-with-current-charging-fx-runtime
        runtime-a
        (fn []
          (@handler* "ctx-a" :current-charging/fx-start {:is-item true})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:states (current-charging-fx/current-charging-fx-snapshot))))))))
      (current-charging-fx/call-with-current-charging-fx-runtime runtime-b current-charging-fx/init!)
      (current-charging-fx/call-with-current-charging-fx-runtime
        runtime-b
        (fn []
          (is (= #{[:ctx "ctx-b"]}
                 (do
                   (@handler* "ctx-b" :current-charging/fx-start {:is-item false})
                   (set (keys (:states (current-charging-fx/current-charging-fx-snapshot)))))))))
      (current-charging-fx/call-with-current-charging-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:states (current-charging-fx/current-charging-fx-snapshot)))))))))))
