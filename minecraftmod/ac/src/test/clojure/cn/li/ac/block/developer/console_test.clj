(ns cn.li.ac.block.developer.console-test
  "Tests for the console pure state machine."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.developer.console-reactive :as console]))

(def ^:private null-char (char 0))

(deftest init-state-test
  (let [state (console/init-state :learn "Player1" true)]
    (is (= [] (:lines state)))
    (is (= "" (:input state)))
    (is (= :boot (:phase state)))
    (is (= :learn (:mode state)))
    (is (= "Player1" (:player-name state)))
    (is (= true (:has-developer state)))))

(deftest process-key-enter-empty-input-test
  ;; Empty enter: no-op (matching upstream where empty input does nothing)
  (let [state (assoc (console/init-state :learn "P1" true) :phase :idle)
        result (console/process-key state {:keyCode 257 :typedChar null-char})]
    (is (= "" (:input result)))
    (is (= state result))))

(deftest process-key-enter-with-command-test
  (let [state (-> (console/init-state :learn "P1" true)
                  (assoc :phase :idle :input "help"))
        result (console/process-key state {:keyCode 257 :typedChar null-char})]
    (is (= "" (:input result)))
    (is (= :executing (:phase result)))
    (is (= "help" (:exec-cmd result)))))

(deftest process-key-char-test
  (let [state (assoc (console/init-state :learn "P1" true) :phase :idle)
        result (console/process-key state {:keyCode 0 :typedChar \a})]
    (is (= "a" (:input result)))))

(deftest process-key-backspace-test
  (let [state (-> (console/init-state :learn "P1" true)
                  (assoc :phase :idle :input "ab"))
        result (console/process-key state {:keyCode 259 :typedChar null-char})]
    (is (= "a" (:input result)))))

(deftest process-key-ignores-during-boot-test
  (let [state (console/init-state :learn "P1" true)  ;; phase = :boot
        result (console/process-key state {:keyCode 0 :typedChar \a})]
    (is (= "" (:input result)))
    (is (= :boot (:phase result)))))

(deftest process-key-ignores-during-developing-test
  (let [state (-> (console/init-state :learn "P1" true)
                  (assoc :phase :developing :input ""))
        result (console/process-key state {:keyCode 0 :typedChar \a})]
    (is (= :developing (:phase result)))))

(deftest process-key-enter-trims-whitespace-test
  (let [state (-> (console/init-state :learn "P1" true)
                  (assoc :phase :idle :input "  help  "))
        result (console/process-key state {:keyCode 257 :typedChar null-char})]
    (is (= "help" (:exec-cmd result)))))

(deftest process-key-no-length-limit-test
  ;; Upstream has no length limit — chars beyond 50 are accepted
  (let [long-input (apply str (repeat 60 "x"))
        state (-> (console/init-state :learn "P1" true)
                  (assoc :phase :idle :input long-input))
        result (console/process-key state {:keyCode 0 :typedChar \y})]
    (is (= (str long-input "y") (:input result)))))

(deftest process-key-rejects-section-sign-test
  ;; § (section sign, char 167) is filtered — matching upstream
  ;; ChatAllowedCharacters.isAllowedCharacter rejection
  (let [state (-> (console/init-state :learn "P1" true)
                  (assoc :phase :idle :input ""))
        result (console/process-key state {:keyCode 0 :typedChar \§})]
    (is (= "" (:input result)))))

(deftest reset-mode-init-test
  (let [state (console/init-state :reset "P1" false)]
    (is (= :reset (:mode state)))
    (is (= :boot (:phase state)))
    (is (= false (:has-developer state)))))
