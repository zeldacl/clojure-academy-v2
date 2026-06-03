(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.threatening-teleport-fx :as tfx]))

(defn- with-fresh-threatening-teleport-fx-runtime [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (level-effects/reset-level-effect-registry-for-test!)
      (tfx/reset-threatening-teleport-fx-for-test!)
      (try
        (f)
        (finally
          (tfx/reset-threatening-teleport-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each with-fresh-threatening-teleport-fx-runtime)

(deftest two-owners-keep-threatening-teleport-state-independent-test
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "threatening-teleport-test"})]
    (tfx/init!)
    (level-effects/enqueue-level-effect! :threatening-teleport {:mode :start}
                                         {:ctx-id "ctx-a" :channel :threatening-tp/fx-update :owner-key [:ctx "ctx-a"]})
    (level-effects/enqueue-level-effect! :threatening-teleport {:mode :start}
                                         {:ctx-id "ctx-b" :channel :threatening-tp/fx-update :owner-key [:ctx "ctx-b"]})
    (level-effects/enqueue-level-effect! :threatening-teleport {:mode :update :drop-x 1.0 :drop-y 2.0 :drop-z 3.0 :attacked? true}
                                         {:ctx-id "ctx-a" :channel :threatening-tp/fx-update :owner-key [:ctx "ctx-a"]})
    (level-effects/enqueue-level-effect! :threatening-teleport {:mode :update :drop-x 4.0 :drop-y 5.0 :drop-z 6.0 :attacked? false}
                                         {:ctx-id "ctx-b" :channel :threatening-tp/fx-update :owner-key [:ctx "ctx-b"]})
    (let [snapshot (tfx/threatening-teleport-fx-snapshot)]
      (is (true? (:attacked? (get (:fx-state snapshot) [:ctx "ctx-a"]))))
      (is (= {:x 4.0 :y 5.0 :z 6.0}
             (:aim (get (:fx-state snapshot) [:ctx "ctx-b"])))))
    (level-effects/enqueue-level-effect! :threatening-teleport {:mode :end}
                                         {:ctx-id "ctx-a" :channel :threatening-tp/fx-end :owner-key [:ctx "ctx-a"]})
    (let [snapshot (tfx/threatening-teleport-fx-snapshot)]
      (is (nil? (get (:fx-state snapshot) [:ctx "ctx-a"])))
      (is (some? (get (:fx-state snapshot) [:ctx "ctx-b"]))))
    (tfx/clear-threatening-teleport-owner! [:ctx "ctx-b"])
    (is (empty? (:fx-state (tfx/threatening-teleport-fx-snapshot))))))

(deftest threatening-teleport-fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (tfx/threatening-teleport-fx-snapshot))))
