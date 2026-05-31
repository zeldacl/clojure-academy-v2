(ns cn.li.ac.ability.server.handlers.common-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.server.handlers.common :as handlers-common]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-handler-common-runtime! [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (handlers-common/install-session-runtime!
        {:server-session-id-resolver (fn [] (runtime-hooks/player-state-server-session-id))
         :owner-resolver (fn [] (runtime-hooks/current-player-state-owner))})
      (try
        (f)
        (finally
          (store/reset-store!)
          (handlers-common/install-session-runtime!
            {:server-session-id-resolver (fn [] (runtime-hooks/player-state-server-session-id))
             :owner-resolver (fn [] (runtime-hooks/current-player-state-owner))}))))))

(use-fixtures :each reset-handler-common-runtime!)

(deftest get-state-uses-installed-session-resolver-test
  (handlers-common/install-session-runtime!
    {:server-session-id-resolver (fn [] :handler-session)
     :owner-resolver (fn [] {:server-session-id :handler-session})})
  (let [state (handlers-common/get-state "p1")]
    (is (map? state))
    (is (some? (store/get-player-state* :handler-session "p1")))))

(deftest get-state-session-resolution-still-fail-fast-test
  (handlers-common/install-session-runtime!
    {:server-session-id-resolver (fn [] nil)
     :owner-resolver (fn [] nil)})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires :server-session-id/:session-id"
                        (handlers-common/get-state "p2"))))
