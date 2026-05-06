(ns cn.li.mcmod.gui.animation-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.animation :as animation]
            [cn.li.mcmod.gui.components :as comp]))

(deftest animation-state-and-update-test
  (testing "create-animation-state initializes expected atoms"
    (let [state (animation/create-animation-state)]
      (is (= :unlinked @(get state :current-state)))
      (is (= 0 @(get state :current-frame)))
      (is (number? @(get state :last-update)))))
  (testing "update-animation! advances and wraps when interval reached"
    (let [state {:current-state (atom :ok)
                 :current-frame (atom 2)
                 :last-update (atom 0)}]
      (animation/update-animation! state {:frames 3 :frame-time 1})
      (is (= 0 @(get state :current-frame)))))
  (testing "update-animation! does not advance when interval not reached"
    (let [state {:current-state (atom :ok)
                 :current-frame (atom 1)
                 :last-update (atom (System/currentTimeMillis))}]
      (animation/update-animation! state {:frames 5 :frame-time 100000})
      (is (= 1 @(get state :current-frame))))))

(deftest render-animation-frame-contract-test
  (let [captured (atom nil)]
    (with-redefs [comp/render-texture-region
                  (fn [& args]
                    (reset! captured args)
                    :ok)]
      (is (= :ok (animation/render-animation-frame!
                   :widget "textures/gui/a.png" 1 2 3 4 2 8)))
      (is (= [:widget "textures/gui/a.png" 1 2 3 4 0.0 0.25 1.0 0.375]
             @captured)))))

(deftest status-poller-throttle-test
  (let [calls (atom 0)
        poller (animation/create-status-poller #(swap! calls inc) 1000)
        update-fn (:update-fn poller)
        last-query (:last-query poller)]
    (testing "first call runs immediately due seeded last-query"
      (update-fn)
      (is (= 1 @calls)))
    (testing "call within interval is throttled"
      (reset! last-query (System/currentTimeMillis))
      (update-fn)
      (is (= 1 @calls)))
    (testing "call after interval triggers query again"
      (reset! last-query 0)
      (update-fn)
      (is (= 2 @calls)))))

(clojure.test/run-tests 'cn.li.mcmod.gui.animation-test)
