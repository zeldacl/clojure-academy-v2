(ns cn.li.ac.block.developer.console-test
  "Tests for the console pure state machine."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.developer.console-reactive :as console]
            [cn.li.ac.test.support.framework :refer [with-fresh-framework]]
            [cn.li.mcmod.i18n :as i18n]))

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

(deftest reset-command-precheck-test
  ;; Client-side canReset gate, matching upstream SkillTree.scala initReset:
  ;; a refused reset prints one error line and never enters :developing.
  (with-fresh-framework
    (fn []
      (console/register-builtin-commands!)
      (let [started (atom 0)
            base (-> (console/init-state :reset "P1" true)
                     (assoc :phase :executing :exec-cmd "reset"
                            :on-start-development (fn [] (swap! started inc))))]
        (testing "non-advanced developer → reset_fail_dev, stays idle"
          (let [[s action] (console/exec-cmd
                             (assoc base :reset-precheck (constantly :reset_fail_dev)))]
            (is (nil? action))
            (is (= :idle (:phase s)))
            (is (= 0 @started))
            (is (.contains ^String (last (:lines s)) "reset_fail_dev"))))
        (testing "other unmet condition → reset_fail_other, stays idle"
          (let [[s action] (console/exec-cmd
                             (assoc base :reset-precheck (constantly :reset_fail_other)))]
            (is (nil? action))
            (is (= :idle (:phase s)))
            (is (= 0 @started))
            (is (.contains ^String (last (:lines s)) "reset_fail_other"))))
        (testing "precheck passes (nil) → development starts"
          (let [[s action] (console/exec-cmd
                             (assoc base :reset-precheck (constantly nil)))]
            (is (= :developing action))
            (is (= :developing (:phase s)))
            (is (= 1 @started))))))))

(deftest command-without-developer-is-invalid-test
  ;; Upstream never registers learn/reset when developer == null (viewer),
  ;; so typing them falls through to "Invalid command."
  (with-fresh-framework
    (fn []
      (console/register-builtin-commands!)
      (let [state (-> (console/init-state :learn "P1" false)
                      (assoc :phase :executing :exec-cmd "learn"
                             :on-start-development nil))
            [s action] (console/exec-cmd state)]
        (is (nil? action))
        (is (= :idle (:phase s)))
        (is (.contains ^String (last (:lines s)) "invalid_command"))))))

(defn- with-console-translations [f]
  ;; The default translate-fn returns the key itself; the console loc() also
  ;; unescapes literal \\n. Stub with the real console strings (trailing \n
  ;; included) so the line-splitting behavior is actually exercised.
  (with-redefs [i18n/*translate-fn*
                (fn [k _args]
                  (get {"skill_tree.academy.console.dev_begin" "Start stimulation......\\n"
                        "skill_tree.academy.console.invalid_command" "Invalid command.\\n"}
                       k k))]
    (f)))

(deftest learn-command-output-matches-upstream-lines-test
  ;; Upstream learn: enqueue echoes the typed input ("OS > learn" — already
  ;; done at Enter in the port), then printTask(dev_begin) and the progress
  ;; line. The old code printed a SECOND "OS >" prompt and conj'd dev_begin
  ;; with its trailing \n into a single line, so the console showed an extra
  ;; prompt and a broken line after learn.
  (with-fresh-framework
    (fn []
      (console/register-builtin-commands!)
      (with-console-translations
        (fn []
          (let [started (atom 0)
                state (-> (console/init-state :learn "P1" true)
                          (assoc :phase :idle :input "learn"))
                after-enter (console/process-key state {:keyCode 257 :typedChar null-char})
                [s action] (console/exec-cmd
                             (assoc after-enter :on-start-development (fn [] (swap! started inc))))]
            (is (= :developing action))
            (is (= :developing (:phase s)))
            (is (= 1 @started))
            ;; The Enter echo ("OS > learn") is the only prompt; dev_begin
            ;; becomes its own line with no embedded newline and no second
            ;; "OS >".
            (is (= ["OS > learn" "Start stimulation......"] (:lines s))
                "learn output: echo + dev_begin line, no extra prompt, no embedded \\n")))))))

(deftest invalid-command-echo-and-error-preserved-test
  ;; Unknown commands must keep the Enter echo + "Invalid command." line —
  ;; no second prompt, no embedded newline (the localized string ends with \n).
  (with-fresh-framework
    (fn []
      (console/register-builtin-commands!)
      (with-console-translations
        (fn []
          (let [state (-> (console/init-state :learn "P1" true)
                          (assoc :phase :idle :input "bogus"))
                after-enter (console/process-key state {:keyCode 257 :typedChar null-char})
                [s _action] (console/exec-cmd after-enter)]
            (is (= ["OS > bogus" "Invalid command."] (:lines s))
                "invalid command keeps the echo and the error line, no extra prompt")))))))
