(ns cn.li.ac.item.energy-items-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.item.energy-items :as energy-items]))

(deftest portable-developer-opens-skill-tree-context-test
  (testing "portable developer forwards portable learn-context to skill-tree entry"
    (let [calls (atom [])]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (is (= 'cn.li.ac.terminal.apps.skill-tree/open-skill-tree-gui sym))
                      (fn [player learn-context]
                        (swap! calls conj {:player player
                                           :learn-context learn-context})))]
        (is (= {:consume? true}
               (#'energy-items/open-portable-developer! {:player :player-1
                                                         :side :client})))
        (is (= [{:player :player-1
                 :learn-context {:developer-type :portable}}]
               @calls))))))