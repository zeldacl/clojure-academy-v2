(ns cn.li.platform.neutral.presentation-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.platform.neutral.vfx-render-plan :as vfx-plan]))

(deftest coalesce-frame-id-groups-calls-within-one-real-frame
  (testing "a second call microseconds later stays on the same frame id"
    (let [[same? next-nanos] (presentation/coalesce-frame-id 1000500 1000000)]
      (is (true? same?))
      (is (= 1000000 next-nanos))))
  (testing "a call past the coalesce window starts a new frame id"
    (let [[same? next-nanos] (presentation/coalesce-frame-id
                                (+ 1000000 presentation/frame-coalesce-window-nanos 1)
                                1000000)]
      (is (false? same?))
      (is (= (+ 1000000 presentation/frame-coalesce-window-nanos 1) next-nanos))))
  (testing "exactly at the window boundary is a new frame (half-open window)"
    (let [[same? _] (presentation/coalesce-frame-id
                       (+ 1000000 presentation/frame-coalesce-window-nanos)
                       1000000)]
      (is (false? same?)))))

(deftest typed-vfx-fallback-never-returns-an-empty-plan
  (let [line-plan (vfx-plan/neutral-op->plan
                   {:operation :draw-batch
                    :primitive :typed-vfx
                    :geometry {:kind :typed-vfx
                               :fields {:start [0.0 0.0 0.0]
                                        :end [1.0 0.0 0.0]}}
                    :material {}})
        marker-plan (vfx-plan/neutral-op->plan
                     {:operation :draw-batch
                      :primitive :typed-vfx
                      :geometry {:kind :typed-vfx :fields {}}
                      :material {}})]
    (is (= :line (:kind (first (:ops line-plan)))))
    (is (= 1 (count (:ops line-plan))))
    (is (= :quad (:kind (first (:ops marker-plan)))))
    (is (= 1 (count (:ops marker-plan))))))
(deftest direct-host-bypasses-lifecycle-map-on-render-path
  (let [lifecycle-lookups (atom 0)
        api {:frame! (fn [_frame-id _delta _width _height] :frame)}]
    (presentation/reset-host-for-test!)
    (try
      (presentation/install-host! api)
      (with-redefs [cn.li.mcbase.presentation.host-lifecycle/host-api
                    (fn [& _] (swap! lifecycle-lookups inc))]
        (is (= :frame (presentation/frame! 1 0.05 800 600)))
        (is (= {:host-id :presentation :stage :hud :frame :frame}
               (presentation/dispatch-stage-with-context!
                :hud 1 0.05 800 600 nil)))
        (is (zero? @lifecycle-lookups)))
      (finally
        (presentation/reset-host-for-test!)))))