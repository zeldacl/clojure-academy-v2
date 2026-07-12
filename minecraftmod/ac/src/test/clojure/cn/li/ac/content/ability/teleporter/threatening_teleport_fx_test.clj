(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.threatening-teleport-fx :as tfx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- with-fresh-threatening-teleport-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (tfx/reset-fx-for-test!)
      (try
        (f)
        (finally
          (tfx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each with-fresh-threatening-teleport-fx-runtime)

(deftest init-registers-threatening-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (tfx/init!)
      (is (= :threatening-teleport (first @registered-level*)))
      (is (= #{:threatening-tp/fx-start
               :threatening-tp/fx-update
               :threatening-tp/fx-perform
               :threatening-tp/fx-end}
             @registered-topics*)))))

(deftest two-owners-keep-threatening-teleport-state-independent-test
  (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)
                client-particles/current-effect-owner (fn [] {:client-session-id "threatening-teleport-test"})]
    (tfx/init!)
    (level-effects/enqueue-level-effect! :threatening-teleport "ctx-a" :threatening-teleport/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-a"])
    (level-effects/enqueue-level-effect! :threatening-teleport "ctx-b" :threatening-teleport/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-b"])
    (level-effects/enqueue-level-effect! :threatening-teleport "ctx-a" :threatening-teleport/fx-update {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true}
                                         :owner-key [:ctx "ctx-a"])
    (level-effects/enqueue-level-effect! :threatening-teleport "ctx-b" :threatening-teleport/fx-update {:mode :update :target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? false}
                                         :owner-key [:ctx "ctx-b"])
    (let [snapshot (tfx/fx-snapshot)]
      (is (true? (:hit? (get (:fx-state snapshot) [:ctx "ctx-a"]))))
      (is (= {:x 4.0 :y 5.0 :z 6.0}
             (:aim (get (:fx-state snapshot) [:ctx "ctx-b"])))))
    (level-effects/enqueue-level-effect! :threatening-teleport "ctx-a" :threatening-teleport/fx-end {:mode :end}
                                         :owner-key [:ctx "ctx-a"])
    (let [snapshot (tfx/fx-snapshot)]
      (is (nil? (get (:fx-state snapshot) [:ctx "ctx-a"])))
      (is (some? (get (:fx-state snapshot) [:ctx "ctx-b"]))))
    (tfx/clear-fx-owner! [:ctx "ctx-b"])
    (is (empty? (:fx-state (tfx/fx-snapshot))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (tfx/fx-snapshot))))
