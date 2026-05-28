(ns cn.li.ac.ability.client.screens.location-teleport-screen-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.screens.location-teleport :as screen]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-screen-fixture [f]
  (screen/call-with-location-teleport-screen-runtime
    (screen/create-location-teleport-screen-runtime)
    (fn []
      (binding [runtime-hooks/*client-session-id* :test-session]
        (f)))))

(use-fixtures :each reset-screen-fixture)

(defn- tp-button-point []
  ;; row 0 => y=10, teleport button x=10+200-62=148, y=16
  [149 17])

(defn- sample-open-payload []
  {:locations [{:name "home"
                :world-id "minecraft:overworld"
                :x 10.0 :y 64.0 :z 10.0
                :can-perform? true
                :cross-dimension? false
                :cp-cost 200.0
                :distance 14.0}]
   :exp 0.6
   :limits {:cross-dimension-exp-threshold 0.83
            :max-location-name-length 8}
   :current-pos {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}})

(deftest perform-click-waits-server-success-before-close-and-fx-dispatch-test
  (let [perform-cb* (atom nil)
    fx-dispatch* (atom [])
        [mx my] (tp-button-point)]
    (screen/open-screen! "p1" (sample-open-payload))
    (with-redefs [api/req-location-teleport-perform! (fn [_name cb]
                                                       (reset! perform-cb* cb)
                                                       nil)
      fx-registry/dispatch-fx-channel! (fn [ctx-id channel payload]
                     (swap! fx-dispatch* conj [ctx-id channel payload])
                     true)]
      (is (true? (screen/handle-screen-click! mx my)))
        (is (= :perform (:pending-op (screen/screen-state-snapshot))))
        (is (true? (:open? (screen/screen-state-snapshot))))
  (is (empty? @fx-dispatch*))
      (@perform-cb* {:action {:success? false :op :perform :error :err-cp}
                     :snapshot {:locations []
                                :exp 0.6
                                :limits {:cross-dimension-exp-threshold 0.83
                                         :max-location-name-length 8}
                                :current-pos nil}})
        (is (true? (:open? (screen/screen-state-snapshot))))
        (is (nil? (:pending-op (screen/screen-state-snapshot))))
        (is (= :err-cp (:last-error (screen/screen-state-snapshot))))
  (is (empty? @fx-dispatch*)))))

(deftest perform-success-closes-screen-and-dispatches-success-fx-test
  (let [perform-cb* (atom nil)
    fx-dispatch* (atom [])
        [mx my] (tp-button-point)]
    (screen/open-screen! "p1" (sample-open-payload))
    (with-redefs [api/req-location-teleport-perform! (fn [_name cb]
                                                       (reset! perform-cb* cb)
                                                       nil)
      fx-registry/dispatch-fx-channel! (fn [ctx-id channel payload]
                     (swap! fx-dispatch* conj [ctx-id channel payload])
                     true)]
      (is (true? (screen/handle-screen-click! mx my)))
      (@perform-cb* {:action {:success? true :op :perform}
                     :snapshot {:locations []
                                :exp 0.61
                                :limits {:cross-dimension-exp-threshold 0.83
                                         :max-location-name-length 8}
                                :current-pos {:world-id "minecraft:overworld" :x 10.0 :y 64.0 :z 10.0}}})
        (is (false? (:open? (screen/screen-state-snapshot))))
  (is (= [[nil :location-teleport/fx-perform-success {:success? true :op :perform}]]
     @fx-dispatch*)))))

(deftest add-input-respects-server-limit-test
  (screen/open-screen! "p1" (sample-open-payload))
      (is (true? (screen/handle-screen-click! 11 39)))
  (screen/handle-char-typed! \a)
  (screen/handle-char-typed! \b)
  (screen/handle-char-typed! \c)
  (screen/handle-char-typed! \d)
  (screen/handle-char-typed! \e)
  (screen/handle-char-typed! \f)
  (screen/handle-char-typed! \g)
  (screen/handle-char-typed! \h)
  (screen/handle-char-typed! \i)
  (is (= "abcdefgh"
      (:add-text (screen/screen-state-snapshot)))))

(deftest screen-state-isolated-by-player-owner-test
  (screen/open-screen! "p1" (sample-open-payload))
  (screen/on-mouse-move 11 14)
  (screen/open-screen! "p2" {:snapshot {:locations [{:name "Mine"
                                                      :position {:x 4 :y 5 :z 6 :dimension :overworld}
                                                      :can-perform? true}]
                                         :exp 0.5
                                         :limits {:cross-dimension-exp-threshold 0.75
                                                  :max-location-name-length 4}
                                         :current-pos {:x 7 :y 8 :z 9 :dimension :overworld}}})
  (is (= "home" (-> (screen/screen-state-snapshot "p1") :locations first :name)))
  (is (= 0 (:selected (screen/screen-state-snapshot "p1"))))
  (is (= "Mine" (-> (screen/screen-state-snapshot "p2") :locations first :name)))
  (is (nil? (:selected (screen/screen-state-snapshot "p2")))))

(deftest screen-owner-requires-explicit-session-and-player-test
  (binding [runtime-hooks/*client-session-id* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Location teleport screen owner requires :client-session-id"
                          (screen/screen-state-snapshot "p1"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Location teleport screen owner requires :player-uuid"
                        (screen/screen-state-snapshot {:client-session-id :session-a}))))

(deftest stale-callback-updates-original-owner-only-test
  (let [perform-cb* (atom nil)]
    (with-redefs [api/req-location-teleport-perform! (fn [_ cb]
                                                       (reset! perform-cb* cb))
                  fx-registry/dispatch-fx-channel! (fn [& _] nil)]
      (screen/open-screen! "p1" (sample-open-payload))
      (let [[mx my] (tp-button-point)]
        (is (true? (screen/handle-screen-click! mx my))))
      (screen/open-screen! "p2" {:snapshot {:locations [{:name "Mine"
                                                         :position {:x 4 :y 5 :z 6 :dimension :overworld}
                                                         :can-perform? true}]
                                            :exp 0.5
                                            :current-pos {:x 7 :y 8 :z 9 :dimension :overworld}}})
      (@perform-cb* {:action {:success? false :error :err-cp}
                     :snapshot {:locations [{:name "Home"
                                             :position {:x 1 :y 2 :z 3 :dimension :overworld}
                                             :can-perform? false}]
                                :exp 0.1
                                :current-pos {:x 1 :y 2 :z 3 :dimension :overworld}}})
      (is (= :err-cp (:last-error (screen/screen-state-snapshot "p1"))))
      (is (nil? (:last-error (screen/screen-state-snapshot "p2"))))
      (is (= "Mine" (-> (screen/screen-state-snapshot "p2") :locations first :name))))))

(deftest screen-runtime-isolation-test
  (let [runtime-b (screen/create-location-teleport-screen-runtime)]
    (screen/open-screen! "p1" (sample-open-payload))
    (screen/call-with-location-teleport-screen-runtime
      runtime-b
      (fn []
        (screen/open-screen! "p1" {:snapshot {:locations [{:name "Mine"
                                                           :world-id "minecraft:overworld"
                                                           :x 4.0 :y 5.0 :z 6.0
                                                           :can-perform? true}]
                                              :exp 0.5
                                              :limits {:cross-dimension-exp-threshold 0.75
                                                       :max-location-name-length 4}
                                              :current-pos {:world-id "minecraft:overworld"
                                                            :x 7.0 :y 8.0 :z 9.0}}})
        (is (= "Mine" (-> (screen/screen-state-snapshot "p1") :locations first :name)))))
    (is (= "home" (-> (screen/screen-state-snapshot "p1") :locations first :name)))))
