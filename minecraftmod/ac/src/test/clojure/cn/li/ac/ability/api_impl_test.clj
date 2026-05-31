(ns cn.li.ac.ability.api-impl-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.api.impl :as api]
            [cn.li.ac.ability.api.protocol :as proto]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.state-tick :as state-tick]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(deftest ability-system-uses-injected-session-id-provider-test
  (let [calls (atom [])
        system (api/ability-system {:session-id-provider (fn [] "api-session")})]
    (with-redefs [store/get-player-state* (fn [session-id player-uuid]
                                            (swap! calls conj [:get session-id player-uuid])
                                            {:dirty? false})
                  store/get-or-create-player-state! (fn [session-id player-uuid]
                                                      (swap! calls conj [:create session-id player-uuid])
                                                      {})
                  store/set-player-state!* (fn [session-id player-uuid state]
                                             (swap! calls conj [:set session-id player-uuid state])
                                             nil)
                  store/mark-player-dirty! (fn [session-id player-uuid]
                                             (swap! calls conj [:dirty session-id player-uuid])
                                             nil)
                  store/remove-player-state!* (fn [session-id player-uuid]
                                                (swap! calls conj [:remove session-id player-uuid])
                                                nil)
                  state-tick/server-tick-player-in-session! (fn [session-id player-uuid sync-fn]
                                                              (swap! calls conj [:tick session-id player-uuid (some? sync-fn)])
                                                              nil)
                  runtime-hooks/require-player-state-session-id (fn [_] (throw (ex-info "runtime-hooks should not be used" {})))]
      (proto/get-player-state system "player-a")
      (proto/get-or-create-player-state! system "player-a")
      (proto/set-player-state! system "player-a" {:value 1})
      (proto/mark-dirty! system "player-a")
      (proto/server-tick-player! system "player-a" identity)
      (proto/remove-player-state! system "player-a")
      (is (= [[:get "api-session" "player-a"]
              [:create "api-session" "player-a"]
              [:set "api-session" "player-a" {:value 1}]
              [:dirty "api-session" "player-a"]
              [:tick "api-session" "player-a" true]
              [:remove "api-session" "player-a"]]
             @calls)))))
