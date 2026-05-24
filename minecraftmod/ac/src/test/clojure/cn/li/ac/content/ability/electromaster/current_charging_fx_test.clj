(ns cn.li.ac.content.ability.electromaster.current-charging-fx-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]))

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
  (reset! (var-get #'cn.li.ac.content.ability.electromaster.current-charging-fx/current-state*)
            {:active? false
             :blending? false
             :is-item false
             :good? false
             :charge-ticks 0
             :charge-ratio 0.0
             :target nil
             :block-pos nil
             :charged 0.0
             :started-at-ms 0
             :ending-at-ms 0})
    (with-redefs [fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                       (swap! queued* conj payload)
                                                       nil)]
      (current-charging-fx/init!)
      (@handler* "ctx-1" :current-charging/fx-start {:is-item true})
      (is (true? (:active? (current-charging-fx/current-state))))
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
