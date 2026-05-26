(ns cn.li.ac.content.ability.electromaster.thunder-clap-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.thunder-clap-fx :as thunder-clap-fx]))

(defn- reset-fixture [f]
  (thunder-clap-fx/reset-thunder-clap-fx-for-test!)
  (f)
  (thunder-clap-fx/reset-thunder-clap-fx-for-test!))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-thunder-clap-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (thunder-clap-fx/init!)
      (is (= :thunder-clap (first @registered-level*)))
      (is (fn? (:enqueue-event-fn (second @registered-level*))))
      (is (= #{:thunder-clap/fx-start
               :thunder-clap/fx-update
               :thunder-clap/fx-end}
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
      (thunder-clap-fx/init!)
      (@handler* "ctx-tc" :thunder-clap/fx-start {:source-player-id "player-a"})
      (@handler* "ctx-tc" :thunder-clap/fx-update {:ticks 5
                                                    :charge-ratio 0.5
                                                    :target {:x 1.0 :y 64.0 :z 1.0}
                                                    :source-player-id "player-a"})
      (@handler* "ctx-tc" :thunder-clap/fx-end {:performed? true
                                                 :source-player-id "player-a"})
      (is (= [[:thunder-clap {:source-player-id "player-a" :mode :start}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-start}]
              [:thunder-clap {:source-player-id "player-a"
                              :mode :update
                              :ticks 5
                              :charge-ratio 0.5
                              :target {:x 1.0 :y 64.0 :z 1.0}}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-update}]
              [:thunder-clap {:source-player-id "player-a"
                              :mode :end
                              :performed? true}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-end}]]
             @enqueued*)))))

(deftest two-owners-keep-thunder-clap-state-independent-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.electromaster.thunder-clap-fx/enqueue!)]
    (enqueue! (event "ctx-a" :thunder-clap/fx-start {:mode :start :source-player-id "player-a"}))
    (enqueue! (event "ctx-b" :thunder-clap/fx-start {:mode :start :source-player-id "player-b"}))
    (enqueue! (event "ctx-a" :thunder-clap/fx-update {:mode :update
                                                       :ticks 7
                                                       :charge-ratio 0.7
                                                       :target {:x 1.0 :y 64.0 :z 0.0}
                                                       :source-player-id "player-a"}))
    (enqueue! (event "ctx-b" :thunder-clap/fx-update {:mode :update
                                                       :ticks 3
                                                       :charge-ratio 0.3
                                                       :target {:x 2.0 :y 64.0 :z 0.0}
                                                       :source-player-id "player-b"}))
    (let [snapshot (thunder-clap-fx/thunder-clap-fx-snapshot)]
      (is (= 7 (:ticks (get (:effect-state snapshot) [:ctx "ctx-a"]))))
      (is (= 3 (:ticks (get (:effect-state snapshot) [:ctx "ctx-b"]))))
      (thunder-clap-fx/clear-thunder-clap-owner! [:ctx "ctx-a"])
      (let [after-clear (thunder-clap-fx/thunder-clap-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"])))))))
