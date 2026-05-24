(ns cn.li.ac.content.ability.teleporter.teleporter-crit-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.combat-notice :as combat-notice]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.teleporter-crit-fx :as crit-fx]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.teleporter.teleporter-crit-fx/fx-state {:ttl 0})
  (combat-notice/reset-notices!)
  (f)
  (combat-notice/reset-notices!)
  (reset! @#'cn.li.ac.content.ability.teleporter.teleporter-crit-fx/fx-state {:ttl 0}))

(use-fixtures :each reset-fixture)

(deftest init-registers-teleporter-crit-channel-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (crit-fx/init!)
      (is (= :teleporter-crit (first @registered-level*)))
      (is (= #{:teleporter/fx-crit-hit}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-crit-payload-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (crit-fx/init!)
      (@handler* "ctx-1" :teleporter/fx-crit-hit {:x 1.0 :y 2.0 :z 3.0 :crit-level 2 :crit-rate 2.6 :message-key "ability.teleporter.critical_hit" :message-args ["x2.6"] :target-uuid "t" :skill-id :flesh-ripping})
      (is (= [[:teleporter-crit {:mode :crit-hit
                                 :x 1.0
                                 :y 2.0
                                 :z 3.0
                                 :crit-level 2
                 :crit-rate 2.6
                 :message-key "ability.teleporter.critical_hit"
                 :message-args ["x2.6"]
                                 :target-uuid "t"
                                 :skill-id :flesh-ripping}]]
             @enqueued*)))))

    (deftest enqueue-crit-hit-emits-level-scaled-effects-and-notice-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.teleporter-crit-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [payload]
                                                             (swap! particles* conj payload)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sounds* conj payload)
                                                      nil)
                  cn.li.mcmod.i18n/*translate-fn* (fn [k]
                                                    (case k
                                                      "ability.teleporter.critical_hit" "Critical Hit %s"
                                                      (str k)))]
      (enqueue! {:mode :crit-hit
             :x 1.0 :y 2.0 :z 3.0
             :crit-level 2
             :crit-rate 2.6
             :message-key "ability.teleporter.critical_hit"
             :message-args ["x2.6"]})
      (is (= 2 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= :portal (:particle-type (first @particles*))))
      (is (= :electric_spark (:particle-type (second @particles*))))
      (is (= "my_mod:tp.tp" (:sound-id (first @sounds*))))
      (is (= "ability.teleporter.critical_hit" (:message-key (combat-notice/active-notice :teleporter-crit (System/currentTimeMillis)))))
      (is (= ["x2.6"] (:message-args (combat-notice/active-notice :teleporter-crit (System/currentTimeMillis)))))
      (is (= "Critical Hit x2.6" (:text (combat-notice/active-notice :teleporter-crit (System/currentTimeMillis))))))))
