(ns cn.li.forge1201.runtime.lifecycle-core-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mc1201.runtime.lifecycle-core :as lifecycle-core]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(deftest login-immediate-sync-clears-dirty-test
  (let [called (atom [])]
    (with-redefs-fn {#'lifecycle-core/player-uuid (fn [_] "p-login")
                     #'runtime-hooks/on-player-login! (fn [_] nil)}
      #(lifecycle-core/on-player-login! :player
                                        {:mark-player-dirty! (fn [uuid] (swap! called conj [:dirty uuid]))
                                         :send-sync-now! (fn [uuid] (swap! called conj [:sync uuid]))
                                         :clear-player-dirty! (fn [uuid] (swap! called conj [:clear uuid]))}))
    (is (= [[:dirty "p-login"]
            [:sync "p-login"]
            [:clear "p-login"]]
           @called))))

(deftest dimension-change-prefers-immediate-sync-over-tick-sync-test
  (let [called (atom [])]
    (with-redefs-fn {#'lifecycle-core/player-uuid (fn [_] "p-dim")
                     #'runtime-hooks/on-player-dimension-change! (fn [_ _ _] nil)}
      #(lifecycle-core/on-player-dimension-change! :player
                                                   "minecraft:overworld"
                                                   "minecraft:the_nether"
                                                   {:mark-player-dirty! (fn [uuid] (swap! called conj [:dirty uuid]))
                                                    :send-sync-now! (fn [uuid] (swap! called conj [:sync uuid]))
                                                    :clear-player-dirty! (fn [uuid] (swap! called conj [:clear uuid]))
                                                    :tick-sync! (fn [_send-sync-fn] (swap! called conj [:tick]))
                                                    :send-sync-fn (fn [_uuid _payload] nil)}))
    (is (= [[:dirty "p-dim"]
            [:sync "p-dim"]
            [:clear "p-dim"]]
           @called))))

(deftest clone-immediate-sync-targets-new-player-test
  (let [called (atom [])]
    (with-redefs-fn {#'lifecycle-core/player-uuid (fn [p] (case p :old "old-uuid" :new "new-uuid"))
                     #'runtime-hooks/on-player-clone! (fn [_old _new] nil)}
      #(lifecycle-core/on-player-clone! :old
                                        :new
                                        true
                                        {:mark-player-dirty! (fn [uuid] (swap! called conj [:dirty uuid]))
                                         :send-sync-now! (fn [uuid] (swap! called conj [:sync uuid]))
                                         :clear-player-dirty! (fn [uuid] (swap! called conj [:clear uuid]))}))
    (is (= [[:dirty "new-uuid"]
            [:sync "new-uuid"]
            [:clear "new-uuid"]]
           @called))))
