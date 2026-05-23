(ns cn.li.ac.ability.client.screens.location-teleport-screen-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.screens.location-teleport :as screen]))

(defn- reset-screen-fixture [f]
  (reset! (var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state)
          {:open? false
           :player-uuid nil
           :locations []
           :selected nil
           :add-mode? false
           :add-text ""
           :pending-op nil
           :last-error nil
           :exp 0.0
           :limits {:cross-dimension-exp-threshold 0.8
                    :max-location-name-length 16}
           :current-pos nil})
  (f)
  (reset! (var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state)
          {:open? false
           :player-uuid nil
           :locations []
           :selected nil
           :add-mode? false
           :add-text ""
           :pending-op nil
           :last-error nil
           :exp 0.0
           :limits {:cross-dimension-exp-threshold 0.8
                    :max-location-name-length 16}
           :current-pos nil}))

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
      (is (= :perform (:pending-op @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state))))
      (is (true? (:open? @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state))))
  (is (empty? @fx-dispatch*))
      (@perform-cb* {:action {:success? false :op :perform :error :err-cp}
                     :snapshot {:locations []
                                :exp 0.6
                                :limits {:cross-dimension-exp-threshold 0.83
                                         :max-location-name-length 8}
                                :current-pos nil}})
      (is (true? (:open? @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state))))
      (is (nil? (:pending-op @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state))))
      (is (= :err-cp (:last-error @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state))))
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
      (is (false? (:open? @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state))))
  (is (= [[nil :location-teleport/fx-perform-success {:success? true :op :perform}]]
     @fx-dispatch*)))))

(deftest add-input-respects-server-limit-test
  (screen/open-screen! "p1" (sample-open-payload))
  (swap! (var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state)
         assoc :add-mode? true :add-text "")
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
         (:add-text @(var-get #'cn.li.ac.ability.client.screens.location-teleport/screen-state)))))
