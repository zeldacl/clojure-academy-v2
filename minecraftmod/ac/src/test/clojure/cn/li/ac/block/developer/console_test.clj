(ns cn.li.ac.block.developer.console-test
  "Tests for the console pure state machine."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.developer.console :as console]))

(def ^:private null-char (char 0))

(deftest init-state-test
  (let [state (console/init-state :learn "Player1")]
    (is (= [] (:lines state)))
    (is (= "" (:input state)))
    (is (= :boot (:phase state)))
    (is (= :learn (:mode state)))
    (is (= "Player1" (:player-name state)))
    (is (= 0 (:boot-step state)))))

(deftest process-key-enter-empty-input-test
  (let [state (assoc (console/init-state :learn "P1") :phase :idle)
        result (console/process-key state {:keyCode 257 :typedChar null-char})]
    (is (= "" (:input result)))
    (is (some? (seq (:lines result))))))

(deftest process-key-enter-with-command-test
  (let [state (-> (console/init-state :learn "P1")
                  (assoc :phase :idle :input "help"))
        result (console/process-key state {:keyCode 257 :typedChar null-char})]
    (is (= "" (:input result)))
    (is (= :executing (:phase result)))
    (is (= "help" (:exec-cmd result)))))

(deftest process-key-char-test
  (let [state (assoc (console/init-state :learn "P1") :phase :idle)
        result (console/process-key state {:keyCode 0 :typedChar \a})]
    (is (= "a" (:input result)))))

(deftest process-key-backspace-test
  (let [state (-> (console/init-state :learn "P1")
                  (assoc :phase :idle :input "ab"))
        result (console/process-key state {:keyCode 259 :typedChar null-char})]
    (is (= "a" (:input result)))))

(deftest process-key-ignores-during-boot-test
  (let [state (console/init-state :learn "P1")  ;; phase = :boot
        result (console/process-key state {:keyCode 0 :typedChar \a})]
    (is (= "" (:input result)))
    (is (= :boot (:phase result)))))

(deftest process-key-ignores-during-developing-test
  (let [state (-> (console/init-state :learn "P1")
                  (assoc :phase :developing :input ""))
        result (console/process-key state {:keyCode 0 :typedChar \a})]
    (is (= :developing (:phase result)))))

(deftest process-key-enter-trims-whitespace-test
  (let [state (-> (console/init-state :learn "P1")
                  (assoc :phase :idle :input "  help  "))
        result (console/process-key state {:keyCode 257 :typedChar null-char})]
    (is (= "help" (:exec-cmd result)))))

(deftest process-key-input-length-limit-test
  (let [long-input (apply str (repeat 60 "x"))
        state (-> (console/init-state :learn "P1")
                  (assoc :phase :idle :input long-input))
        result (console/process-key state {:keyCode 0 :typedChar \y})]
    (is (= long-input (:input result)))))

(deftest reset-mode-init-test
  (let [state (console/init-state :reset "P1")]
    (is (= :reset (:mode state)))
    (is (= :boot (:phase state)))))
