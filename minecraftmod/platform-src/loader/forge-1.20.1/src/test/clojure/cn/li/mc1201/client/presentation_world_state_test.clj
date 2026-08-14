(ns cn.li.mc1201.client.presentation-world-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mc1201.client.effects.presentation-world :as presentation-world]))

(def ^:private owner-a {:client-session-id [:client :session-a]
                        :player-uuid "player-a"})
(def ^:private owner-b {:client-session-id [:client :session-b]
                        :player-uuid "player-b"})

(def ^:private fake-player (Object.))
(def ^:private default-walk-speed 0.1)

(use-fixtures :each
  (fn [f]
    (presentation-world/call-with-presentation-world-runtime
      (presentation-world/create-presentation-world-runtime)
      (fn []
        (f)))))

(deftest walk-speed-cache-isolated-by-owner-test
  (let [calls (atom [])]
    (with-redefs [presentation-world/set-local-walk-speed! (fn [_player speed]
                                                         (swap! calls conj speed)
                                                         nil)]
      (presentation-world/apply-local-walk-speed-from-plan! owner-a fake-player {:local-walk-speed 0.2})
      (presentation-world/apply-local-walk-speed-from-plan! owner-b fake-player {:local-walk-speed 0.3})
      (is (= [0.2 0.3] @calls))
      (is (= {[(:client-session-id owner-a) (:player-uuid owner-a)] 0.2
              [(:client-session-id owner-b) (:player-uuid owner-b)] 0.3}
             (presentation-world/walk-speed-snapshot))))))

(deftest clear-owner-walk-speed-restores-default-and-keeps-other-owners-test
  (let [calls (atom [])]
    (with-redefs [presentation-world/set-local-walk-speed! (fn [_player speed]
                                                         (swap! calls conj speed)
                                                         nil)]
      (presentation-world/apply-local-walk-speed-from-plan! owner-a fake-player {:local-walk-speed 0.2})
      (presentation-world/apply-local-walk-speed-from-plan! owner-b fake-player {:local-walk-speed 0.3})
      (presentation-world/clear-owner-walk-speed! owner-a fake-player)
      (is (= [0.2 0.3 default-walk-speed] @calls))
      (is (= {[(:client-session-id owner-b) (:player-uuid owner-b)] 0.3}
              (presentation-world/walk-speed-snapshot))))))

      (deftest presentation-world-runtime-isolation-test
        (let [runtime-b (presentation-world/create-presentation-world-runtime)
         calls (atom [])]
          (with-redefs [presentation-world/set-local-walk-speed! (fn [_player speed]
                       (swap! calls conj speed)
                       nil)]
            (presentation-world/apply-local-walk-speed-from-plan! owner-a fake-player {:local-walk-speed 0.2})
            (presentation-world/call-with-presentation-world-runtime
         runtime-b
         (fn []
           (presentation-world/apply-local-walk-speed-from-plan! owner-a fake-player {:local-walk-speed 0.4})
           (is (= {[(:client-session-id owner-a) (:player-uuid owner-a)] 0.4}
             (presentation-world/walk-speed-snapshot)))))
            (is (= {[(:client-session-id owner-a) (:player-uuid owner-a)] 0.2}
              (presentation-world/walk-speed-snapshot))))))
