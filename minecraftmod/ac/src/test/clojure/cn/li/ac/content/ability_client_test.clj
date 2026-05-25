(ns cn.li.ac.content.ability-client-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability-client :as ability-client]))

(defn- reset-fx-init-guard-fixture [f]
  (let [guard* (var-get #'cn.li.ac.content.ability-client/fx-initialized?)
        before @guard*]
    (reset! guard* false)
    (try
      (f)
      (finally
        (reset! guard* before)))))

(use-fixtures :each reset-fx-init-guard-fixture)

(deftest init-client-fx-loads-discovered-namespaces-once-test
  (let [inited* (atom [])]
    (with-redefs [cn.li.ac.ability.discovery/discovered-fx-namespaces
                  (fn []
                    '[cn.li.ac.content.ability.electromaster.arc-gen-fx
                      cn.li.ac.content.ability.electromaster.thunder-bolt-fx])
                  cn.li.ac.content.ability-client/init-fx-namespace!
                  (fn [ns-sym]
                    (swap! inited* conj ns-sym)
                    nil)
                  cn.li.mcmod.util.log/info (fn [& _] nil)]
      (ability-client/init-client-fx!)
      (ability-client/init-client-fx!)
      (is (= '[cn.li.ac.content.ability.electromaster.arc-gen-fx
               cn.li.ac.content.ability.electromaster.thunder-bolt-fx]
             @inited*)))))
