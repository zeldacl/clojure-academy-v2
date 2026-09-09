(ns cn.li.ac.block.developer.console-test
  "The developer console state machine. It lost its implementation twice to GUI
   rewrites (console.clj -> console_reactive.clj -> nothing) while the screen
   kept calling it, so the point of these tests is to pin the surface
   cn.li.ac.block.developer.presentation actually uses -- max-lines, editing?,
   type-char, backspace-input, set-input, submit-input, tick, body-rows,
   prompt-line -- against behaviour, not just against existence."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.developer.console :as console]))

(defn- boot!
  "Run the boot animation to completion so the console is at the prompt.
   Each tick is a generous 1s so the slow-print (0.01s/char) and the 0.3/0.4s
   pauses finish quickly; 400 is an upper bound, not a measurement."
  [cs]
  (loop [cs cs n 0]
    (if (or (= :idle (:phase cs)) (> n 400))
      cs
      (recur (console/tick cs 1.0) (inc n)))))

(deftest boot-reaches-the-prompt-and-only-then-accepts-typing-test
  (let [cs (console/init-state :learn "Tester" true)]
    (is (= :boot (:phase cs)))
    (is (false? (console/editing? cs))
        "keys must not reach the console during the boot animation")
    (is (= "" (:input (console/type-char cs "a")))
        "type-char is a no-op outside :idle, like the old process-key")
    (let [booted (boot! cs)]
      (is (= :idle (:phase booted)) "boot animation must terminate")
      (is (true? (console/editing? booted)))
      (is (seq (:lines booted)) "boot prints something"))))

(deftest typing-and-editing-the-input-line-test
  (let [cs (boot! (console/init-state :learn "Tester" true))]
    (testing "printable characters accumulate"
      (is (= "help" (:input (-> cs (console/type-char "h") (console/type-char "elp"))))))
    (testing "control characters and the section sign are filtered"
      (is (= "ab" (:input (-> cs (console/type-char "a") (console/type-char "§")
                              (console/type-char "\n") (console/type-char "b"))))))
    (testing "backspace removes one character and stops at empty"
      (is (= "h" (:input (-> cs (console/type-char "hi") console/backspace-input))))
      (is (= "" (:input (-> cs console/backspace-input console/backspace-input)))))
    (testing "set-input replaces wholesale"
      (is (= "reset" (:input (-> cs (console/type-char "xx") (console/set-input "reset"))))))))

(deftest submit-echoes-the-line-and-defers-the-command-to-tick-test
  (let [cs (-> (console/init-state :learn "Tester" true) boot! (console/set-input "help"))
        submitted (console/submit-input cs)]
    (is (= :executing (:phase submitted)))
    (is (= "help" (:exec-cmd submitted)))
    (is (= "" (:input submitted)) "the input line is cleared on submit")
    (is (= "OS > help" (last (:lines submitted)))
        "the typed line is echoed once, at Enter -- handlers must not echo again")
    (let [ran (console/tick submitted 0.1)]
      (is (= :idle (:phase ran)))
      (is (re-find #"^Commands: " (last (:lines ran))))
      (is (nil? (:exec-cmd ran))))))

(deftest empty-submit-is-a-no-op-test
  ;; Upstream: Enter on a blank line does nothing at all -- no echo, no
  ;; :executing, and the (whitespace) input is left as typed.
  (let [blank (-> (console/init-state :learn "Tester" true)
                  boot!
                  (console/set-input "   "))]
    (is (= blank (console/submit-input blank)))))

(deftest unknown-command-reports-and-returns-to-the-prompt-test
  (let [ran (-> (console/init-state :learn "Tester" true)
                boot!
                (console/set-input "nope")
                console/submit-input
                (console/tick 0.1))]
    (is (= :idle (:phase ran)))
    (is (nil? (:exec-cmd ran)))))

(deftest clear-empties-the-body-but-keeps-the-prompt-test
  (let [ran (-> (console/init-state :learn "Tester" true)
                boot!
                (console/set-input "clear")
                console/submit-input
                (console/tick 0.1))]
    (is (= [] (:lines ran)))
    (is (= :idle (:phase ran)))))

(deftest learn-starts-development-and-completes-test
  (let [started (atom 0)
        cs (-> (console/init-state :learn "Tester" true)
               boot!
               (assoc :on-start-development (fn [] (swap! started inc)))
               (console/set-input "learn")
               console/submit-input
               (console/tick 0.1))]
    (is (= 1 @started) "the screen's start-development hook fires exactly once")
    (is (= :developing (:phase cs)))
    (testing "the first ticks are a grace window while the server answers"
      (let [waiting (nth (iterate #(console/tick % 0.05
                                                 :development-progress 0.0
                                                 :is-developing? false
                                                 :development-complete? false)
                                  cs)
                         3)]
        (is (= :developing (:phase waiting)) "must not give up inside the grace window")))
    (testing "progress runs, then completion is reported"
      (let [running (nth (iterate #(console/tick % 0.05
                                                 :development-progress 0.5
                                                 :is-developing? true
                                                 :development-complete? false)
                                  cs)
                         8)
            _ (is (= :developing (:phase running)))
            _ (is (true? (:was-developing? running)))
            done (console/tick running 0.05
                               :development-progress 1.0
                               :is-developing? false
                               :development-complete? true)]
        (is (= :done (:phase done)))
        (is (= :success (:dev-result done)))
        (testing "and after the result pause the prompt comes back"
          (is (= :idle (:phase (console/tick done 1.0)))))))))

(deftest development-never-starting-is-reported-as-a-rejection-test
  (let [cs (-> (console/init-state :learn "Tester" true)
               boot!
               (assoc :on-start-development (fn []))
               (console/set-input "learn")
               console/submit-input
               (console/tick 0.1))
        rejected (nth (iterate #(console/tick % 0.05
                                              :development-progress 0.0
                                              :is-developing? false
                                              :development-complete? false)
                               cs)
                      7)]
    (is (= :idle (:phase rejected)))
    (is (false? (:was-developing? rejected)))
    (is (some #(re-find #"ERROR" (str %)) (:lines rejected))
        "a request the server never picked up must say so, not hang on Progress")))

(deftest reset-mode-honours-the-client-side-precheck-test
  (let [started (atom 0)
        base (-> (console/init-state :reset "Tester" true)
                 boot!
                 (assoc :on-start-development (fn [] (swap! started inc))))]
    (testing "a refused reset prints its reason and never starts"
      (let [refused (-> base
                        (assoc :reset-precheck (fn [] :reset_fail_other))
                        (console/set-input "reset")
                        console/submit-input
                        (console/tick 0.1))]
        (is (= :idle (:phase refused)))
        (is (zero? @started))))
    (testing "an allowed reset starts development"
      (let [allowed (-> base
                        (assoc :reset-precheck (fn [] nil))
                        (console/set-input "reset")
                        console/submit-input
                        (console/tick 0.1))]
        (is (= :developing (:phase allowed)))
        (is (= 1 @started))))
    (testing "learn is not a reset-mode command"
      (let [wrong (-> base
                      (console/set-input "learn")
                      console/submit-input
                      (console/tick 0.1))]
        (is (= :idle (:phase wrong)))
        (is (= 1 @started) "still only the allowed reset above")))))

(deftest rendering-output-is-text-only-test
  (let [cs (boot! (console/init-state :learn "Tester" true))
        rows (console/body-rows cs)]
    (testing "body rows carry a label and nothing else"
      (is (every? #(= #{:label} (set (keys %))) rows)
          "no :x/:y/:w/:h -- developer.ui.edn's :scroll owns the layout, and a
           geometry key here would mean this namespace is laying out again")
      (is (every? #(string? (:label %)) rows)))
    (testing "the prompt is a plain string, pinned by the .ui.edn"
      (is (string? (console/prompt-line cs)))
      (is (re-find #"^OS > " (console/prompt-line cs))))))

(deftest body-window-never-exceeds-max-lines-test
  (let [cs (-> (console/init-state :learn "Tester" true)
               boot!
               (assoc :lines (mapv #(str "line " %) (range 40))))
        rows (console/body-rows cs)]
    (is (= console/max-lines (count rows))
        "only the last max-lines body lines are handed to the list, so the
         :scroll's fixed height always shows the newest output")
    (is (= "line 39" (:label (last rows)))
        "the newest line is the last row")))

(deftest tick-reports-dirty-only-when-the-painted-output-changes-test
  (let [cs (boot! (console/init-state :learn "Tester" true))
        ;; The cursor blinks on a 0.5s period; a much shorter tick changes only
        ;; an internal timer, and the screen must not be asked to repaint.
        quiet (console/tick cs 0.01)
        blinked (console/tick cs 0.6)]
    (is (false? (:dirty? quiet)))
    (is (true? (:dirty? blinked)))))
