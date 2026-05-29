(ns cn.li.ac.ability.learning-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.server.service.learning-runtime :as learning-rt]))

(deftest learn-skill-bang-updates-player-state-and-fires-event-test
  (let [next-ability (adata/learn-skill (adata/new-ability-data) :s1)
        expected-event {:event/type :ability/skill-learn
                        :uuid "p1"
                        :skill-id :s1}]
    (with-redefs [command-rt/run-command! (fn [_ _]
                                            {:state {:ability-data next-ability}
                                             :events [expected-event]
                                             :effects []})]
      (let [{:keys [data event]} (learning-rt/learn-skill! "p1" :s1)]
        (is (contains? (:learned-skills data) :s1))
        (is (= next-ability data))
        (is (= expected-event event))))))
