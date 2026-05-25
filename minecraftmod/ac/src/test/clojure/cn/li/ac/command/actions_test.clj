(ns cn.li.ac.command.actions-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.command.actions :as ac-actions]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.command.actions :as command-actions]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest install-command-actions-registers-ac-manifest-test
  (ac-actions/install-command-actions!)
  (is (contains? (command-actions/valid-action-types) :learn-node))
  (is (contains? (command-actions/valid-action-types) :clear-cooldowns))
  (is (fn? (command-actions/get-action-executor :set-level))))

(deftest registered-command-actions-mutate-ac-player-state-test
  (ac-actions/install-command-actions!)
  (let [feedbacks (atom [])
        context {:metadata {:send-feedback-fn (fn [message translate? args error?]
                                                (swap! feedbacks conj [message translate? args error?]))}}
        uuid "command-player"
        initial (-> (ps/fresh-state)
                    (assoc-in [:ability-data :category-id] :electromaster)
                    (assoc-in [:resource-data :cur-cp] 1.0)
                    (assoc-in [:resource-data :max-cp] 42.0)
                    (assoc :cooldown-data {[:railgun :main] 20}))]
    (ps/set-player-state! uuid initial)

    (is (:success? (command-actions/execute {:action :set-level
                                             :level 3
                                             :player-uuid uuid}
                                            context)))
    (is (= 3 (get-in (ps/get-player-state uuid) [:ability-data :level])))

    (is (:success? (command-actions/execute {:action :restore-cp
                                             :player-uuid uuid}
                                            context)))
    (is (= 42.0 (get-in (ps/get-player-state uuid) [:resource-data :cur-cp])))

    (is (:success? (command-actions/execute {:action :clear-cooldowns
                                             :player-uuid uuid}
                                            context)))
    (is (= {} (:cooldown-data (ps/get-player-state uuid))))
    (is (true? (ps/dirty? uuid)))
    (is (some #(= "command.academy.aim.level.success" (first %)) @feedbacks))))
