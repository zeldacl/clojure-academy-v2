(ns cn.li.mcmod.presentation-backend-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.presentation-backend :as backend])
  (:import [cn.li.mcmod.runtime RenderCommand RenderStage]))

(deftest opaque-submit-callback-records-frame
  (let [value (backend/create :mc-1-20-1)
        packet {:frame-id 42 :commands [:quad]}]
    (is (= :mc-1-20-1 (:profile value)))
    (is (= value ((:submit! value) :hud packet)))
    (is (= [{:stage :hud :frame-packet packet}]
           @(:submissions value)))))

(deftest capability-profile-is-version-owned
  (is (false? (get-in (backend/create :mc-1-20-1) [:capabilities :instancing?])))
  (is (true? (get-in (backend/create :mc-26-2) [:capabilities :instancing?]))))

(deftest neutral-render-command-vocabulary-is-sealed
  (is (= 16 (alength (.getPermittedSubclasses RenderCommand)))))

(deftest stage-keyword-maps-to-every-loader-facing-render-stage
  (is (= RenderStage/HUD (backend/stage->render-stage :hud)))
  (is (= RenderStage/WORLD_AFTER_TRANSLUCENT (backend/stage->render-stage :world-after-translucent)))
  (is (thrown? Exception (backend/stage->render-stage :not-a-stage))))

(deftest renderer-receives-opaque-context
  (let [seen (atom nil)
        value (backend/install-renderer!
                (backend/create :mc-1-21-1)
                (fn [context stage frame]
                  (reset! seen [context stage frame])))
        frame {:frame-id 11}]
    ((:submit! value) :hud frame :gui-graphics)
    (is (= [:gui-graphics :hud frame] @seen))))
