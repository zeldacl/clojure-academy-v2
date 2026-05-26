(ns cn.li.mcmod.client.platform-bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private bridge-vars
  [#'cn.li.mcmod.client.platform-bridge/*slot-key-down-fn*
   #'cn.li.mcmod.client.platform-bridge/*slot-key-tick-fn*
   #'cn.li.mcmod.client.platform-bridge/*slot-key-up-fn*
  #'cn.li.mcmod.client.platform-bridge/*slot-key-abort-fn*
   #'cn.li.mcmod.client.platform-bridge/*movement-key-down-fn*
   #'cn.li.mcmod.client.platform-bridge/*movement-key-tick-fn*
   #'cn.li.mcmod.client.platform-bridge/*movement-key-up-fn*
   #'cn.li.mcmod.client.platform-bridge/*open-screen-fn*
   #'cn.li.mcmod.client.platform-bridge/*open-simple-gui-fn*
   #'cn.li.mcmod.client.platform-bridge/*run-client-effect-fn*])

(defn- clear-bridge! []
  (doseq [bridge-var bridge-vars]
    (alter-var-root bridge-var (constantly nil))))

(defn- reset-bridge-state! [f]
  (clear-bridge!)
  (try
    (f)
    (finally
      (clear-bridge!))))

(use-fixtures :each reset-bridge-state!)

(deftest install-client-bridge-wires-generic-screen-host-test
  (let [calls (atom [])]
    (client-bridge/install-client-bridge!
      {:open-screen (fn [screen-key payload]
                      (swap! calls conj {:screen-key screen-key
                                         :payload payload})
                      :opened)})
    (is (= :opened (client-bridge/open-screen! :content/example-screen {:value 42})))
    (is (= [{:screen-key :content/example-screen
             :payload {:value 42}}]
           @calls))))

(deftest install-client-bridge-wires-generic-effect-host-test
  (let [calls (atom [])]
    (client-bridge/install-client-bridge!
      {:run-client-effect (fn [effect-key payload]
                            (swap! calls conj {:effect-key effect-key
                                               :payload payload})
                            :ran)})
    (is (= :ran (client-bridge/run-client-effect! :content/example-effect {:amount 3})))
    (is (= [{:effect-key :content/example-effect
             :payload {:amount 3}}]
           @calls))))

(deftest install-client-bridge-wires-key-callbacks-test
  (let [calls (atom [])]
    (client-bridge/install-client-bridge!
      {:slot-key-down (fn [player-uuid key-idx]
                        (swap! calls conj [:slot-down player-uuid key-idx]))
       :slot-key-abort (fn [player-uuid key-idx]
                         (swap! calls conj [:slot-abort player-uuid key-idx]))
       :movement-key-up (fn [player-uuid movement-key]
                          (swap! calls conj [:movement-up player-uuid movement-key]))})
    (client-bridge/on-slot-key-down! "p1" 0)
    (client-bridge/on-slot-key-abort! "p1" 0)
    (client-bridge/on-movement-key-up! "p1" :forward)
    (is (= [[:slot-down "p1" 0]
            [:slot-abort "p1" 0]
            [:movement-up "p1" :forward]]
           @calls))))
