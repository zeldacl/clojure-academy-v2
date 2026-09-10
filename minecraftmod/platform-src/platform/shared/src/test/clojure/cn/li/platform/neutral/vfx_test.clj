(ns cn.li.platform.neutral.vfx-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.platform.neutral.vfx :as vfx]
            [cn.li.platform.neutral.client-runtime :as client-runtime]))

(use-fixtures :each
  (fn [test-fn]
    (vfx/reset-host-for-test!)
    (try
      (test-fn)
      (finally
        (vfx/reset-host-for-test!)))))

(deftest installed-host-bypasses-client-runtime-adapter-test
  (let [calls (atom 0)]
    (vfx/install-host! {:required-anchors (constantly #{:camera})
                       :tick! (constantly nil)
                       :sample-frame! (constantly nil)
                       :frame-stage (constantly nil)
                       :latest-frame-stage (constantly nil)
                       :release-frame! (constantly nil)
                       :clear-world! (constantly nil)
                       :resource-snapshot (constantly nil)
                       :reload-resources! (constantly nil)
                       :active? (constantly false)
                       :fov-offset (constantly 0.0)
                       :drain-camera-pitch-deltas! (constantly nil)})
    (with-redefs [client-runtime/call-adapter
                  (fn [& _] (swap! calls inc))]
      (testing "host access uses the bootstrap-cached IFn"
        (is (= #{:camera} (vfx/required-anchors)))
        (is (true? (vfx/installed?)))
        (is (zero? @calls))))))
(deftest optional-presentation-operations-may-be-absent-test
  (vfx/install-host! {:required-anchors (constantly #{:camera})
                       :tick! (constantly nil)
                       :sample-frame! (constantly nil)
                       :frame-stage (constantly nil)
                       :latest-frame-stage (constantly nil)
                       :release-frame! (constantly nil)
                       :clear-world! (constantly nil)
                       :resource-snapshot (constantly nil)
                       :reload-resources! (constantly nil)})
  (is (nil? (vfx/fov-offset :owner-1)))
  (is (nil? (vfx/drain-camera-pitch-deltas! :owner-1))))