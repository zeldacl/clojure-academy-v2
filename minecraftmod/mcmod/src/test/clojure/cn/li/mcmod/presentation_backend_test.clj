(ns cn.li.mcmod.presentation-backend-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.presentation-backend :as backend]))

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

(deftest neutral-command-vocabulary-is-explicit
  (is (= 15 (count backend/command-kinds)))
  (is (every? backend/command-kind-known?
             ["quad" "image" "glyph-run" "mesh" "billboard"
              "particle-batch" "ribbon" "beam" "item-preview"
              "camera-contribution" "post-process" "order-barrier"]))
  (is (false? (backend/command-kind-known? "legacy-draw-plan"))))

(deftest renderer-receives-opaque-context
  (let [seen (atom nil)
        value (backend/install-renderer!
                (backend/create :mc-1-21-1)
                (fn [context stage frame]
                  (reset! seen [context stage frame])))
        frame {:frame-id 11}]
    ((:submit! value) :hud frame :gui-graphics)
    (is (= [:gui-graphics :hud frame] @seen))))
