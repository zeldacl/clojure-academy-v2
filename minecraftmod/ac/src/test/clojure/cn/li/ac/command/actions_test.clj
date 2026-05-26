(ns cn.li.ac.command.actions-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.server.service.player-state-actions :as state-actions]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
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

(deftest registered-command-actions-delegate-stateful-mutations-to-service-test
  (ac-actions/install-command-actions!)
  (let [calls (atom [])
        context {}
        uuid "command-player"]
    (with-redefs [state-actions/learn-skill! (fn [player-uuid skill-id]
                                               (swap! calls conj [:learn-skill player-uuid skill-id])
                                               {:changed? true})
                  state-actions/unlearn-skill! (fn [player-uuid skill-id]
                                                 (swap! calls conj [:unlearn-skill player-uuid skill-id])
                                                 {:changed? true})
                  state-actions/learn-skills! (fn [player-uuid skill-ids]
                                                (swap! calls conj [:learn-skills player-uuid (vec skill-ids)])
                                                {:changed? true})
                  state-actions/set-level! (fn [player-uuid level]
                                             (swap! calls conj [:set-level player-uuid level])
                                             {:changed? true})
                  state-actions/set-skill-exp! (fn [player-uuid skill-id amount]
                                                 (swap! calls conj [:set-skill-exp player-uuid skill-id amount])
                                                 {:changed? true})
                  state-actions/recover-all! (fn [player-uuid]
                                               (swap! calls conj [:recover-all player-uuid])
                                               {:changed? true})
                  state-actions/clear-cooldowns! (fn [player-uuid]
                                                   (swap! calls conj [:clear-cooldowns player-uuid])
                                                   {:changed? true})
                  state-actions/reset-abilities! (fn [player-uuid]
                                                   (swap! calls conj [:reset-abilities player-uuid])
                                                   {:changed? true})
                  state-actions/maxout-progression! (fn [player-uuid skill-ids]
                                                      (swap! calls conj [:maxout player-uuid (vec skill-ids)])
                                                      {:changed? true})
                  ps/get-or-create-player-state! (fn [_]
                                                  {:ability-data {:category-id :electromaster}})
                  skill-query/get-skills-for-category (fn [_]
                                                        [{:id :railgun}
                                                         {:id :arc-gen}])]
      (doseq [action [{:action :learn-node :node-id :railgun :player-uuid uuid}
                      {:action :unlearn-node :node-id :railgun :player-uuid uuid}
                      {:action :learn-all-nodes :player-uuid uuid}
                      {:action :set-level :level 3 :player-uuid uuid}
                      {:action :set-node-exp :node-id :railgun :exp 0.5 :player-uuid uuid}
                      {:action :restore-cp :player-uuid uuid}
                      {:action :clear-cooldowns :player-uuid uuid}
                      {:action :reset-abilities :player-uuid uuid}
                      {:action :maxout-progression :player-uuid uuid}]]
        (is (:success? (command-actions/execute action context))))
            (is (= [[:learn-skill uuid :railgun]
              [:unlearn-skill uuid :railgun]
              [:learn-skills uuid [:arc-gen :railgun]]
              [:set-skill-exp uuid :arc-gen 1.0]
              [:set-skill-exp uuid :railgun 1.0]
              [:set-level uuid 3]
              [:set-skill-exp uuid :railgun 0.5]
              [:recover-all uuid]
              [:clear-cooldowns uuid]
              [:reset-abilities uuid]
              [:maxout uuid [:arc-gen :railgun]]]
             @calls)))))
