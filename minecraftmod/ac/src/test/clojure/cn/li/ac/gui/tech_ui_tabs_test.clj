(ns cn.li.ac.gui.tech-ui-tabs-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.tech-ui-tabs :as tabs]))

(deftest page-visibility-without-opt-in
  (is (= {:inv-page-visible? true
          :wireless-page-visible? false
          :tech-tab-id "inv"}
         (tabs/page-visibility {}))))

(deftest page-visibility-with-opt-in
  (let [c {:presentation-tech-tabs? true :tab-index (atom 0)}]
    (is (true? (:inv-page-visible? (tabs/page-visibility c))))
    (is (false? (:wireless-page-visible? (tabs/page-visibility c))))
    (reset! (:tab-index c) 1)
    (is (false? (:inv-page-visible? (tabs/page-visibility c))))
    (is (true? (:wireless-page-visible? (tabs/page-visibility c))))))

(deftest tab-strip-geometry
  (testing "disabled yields empty strip"
    (is (= [] (tabs/tab-strip-items {}))))
  (testing "enabled yields inv then wireless at main geometry"
    (let [items (tabs/tab-strip-items {:presentation-tech-tabs? true :tab-index (atom 0)})]
      (is (= 2 (count items)))
      (is (= -20.0 (:layout-x (nth items 0))))
      (is (= -20.0 (:layout-x (nth items 1))))
      (is (= 0.0 (:layout-y (nth items 0))))
      (is (= 0.0 (:x (nth items 0))))
      (is (= 0.0 (:y (nth items 0))))
      (is (= 20.0 (:w (nth items 0))))
      (is (= 22.0 (:layout-y (nth items 1))))
      (is (= 0.0 (:y (nth items 1))))
      (is (= 0 (:tab-index (nth items 0))))
      (is (= 1 (:tab-index (nth items 1))))
      (is (true? (:selected? (nth items 0))))
      (is (false? (:selected? (nth items 1))))
      (is (= (unchecked-int 0xFFFFFFFF) (:rgba (nth items 0))))
      (is (= (unchecked-int 0x88FFFFFF) (:rgba (nth items 1))))
      (is (= "inv" (:tab-id (nth items 0))))
      (is (= "wireless" (:tab-id (nth items 1))))
      (is (re-find #"icon_inv\.png$" (:src (nth items 0))))
      (is (re-find #"icon_wireless\.png$" (:src (nth items 1))))))
  (testing "selection only changes tint, not position"
    (let [c {:presentation-tech-tabs? true :tab-index (atom 1)}
          items (tabs/tab-strip-items c)]
      (is (false? (:selected? (nth items 0))))
      (is (true? (:selected? (nth items 1))))
      (is (= -20.0 (:layout-x (nth items 0))))
      (is (= -20.0 (:layout-x (nth items 1))))
      (is (= (unchecked-int 0x88FFFFFF) (:rgba (nth items 0))))
      (is (= (unchecked-int 0xFFFFFFFF) (:rgba (nth items 1)))))))

(deftest mark-slot-anchors-respects-tab
  (let [anchors [{:slot-index 0 :x 1.0 :y 2.0 :width 16.0 :height 16.0 :visible? true}]
        inv {:presentation-tech-tabs? true :tab-index (atom 0)}
        wireless {:presentation-tech-tabs? true :tab-index (atom 1)}]
    (is (true? (:visible? (first (tabs/mark-slot-anchors inv anchors)))))
    (is (false? (:visible? (first (tabs/mark-slot-anchors wireless anchors)))))
    (is (true? (:visible? (first (tabs/mark-slot-anchors {} anchors)))))))

(deftest switch-tab-updates-atom
  (let [c {:presentation-tech-tabs? true
           :tab-index (atom 0)
           :minecraft-container nil}]
    ;; No menu container-id → send-set-tab! skipped via nil cid path.
    (is (= "wireless" (tabs/switch-tab! c 1)))
    (is (= 1 @(:tab-index c)))
    (is (= "inv" (tabs/switch-tab! c "inv")))
    (is (= 0 @(:tab-index c)))))
