(ns cn.li.mcmod.ui.events-test
  "Regression tests for UI event dispatch.
   dispatch-char! must deliver the typed character as a real string: char[]
   does not override toString, so the old (Character/toChars ...) .toString
   produced the identity string (\"[C@<hash>\") and every codepoint arrived
   as (first ...) = \\[ in :change-content consumers (developer console input
   showed [[[[[[ regardless of the key pressed)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.events :as events])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]))

(defn- char-runtime
  "Minimal runtime with a single focusable group node."
  ^UiRt []
  (let [r (rt/create-runtime)]
    (rt/build! r {:kind :group
                  :props {:id :root :x 0.0 :y 0.0 :w 100.0 :h 100.0}})
    (rt/resize! r 200.0 200.0)
    r))

(defn- capture-char
  "Register a :change-content handler on `id` returning the :char it received."
  [^UiRt rt id]
  (let [captured (atom nil)]
    (events/on! rt id :change-content
      (fn [_ _ evt] (reset! captured (:char evt))))
    (events/gain-focus! rt (.getIdx (rt/node-by-id rt id)))
    captured))

(deftest dispatch-char-delivers-real-char-string-test
  (let [rt (char-runtime)
        captured (capture-char rt :root)]
    (events/dispatch-char! rt (int \a))
    (is (= "a" @captured))))

(deftest dispatch-char-handles-surrogate-pair-test
  (let [rt (char-runtime)
        captured (capture-char rt :root)
        ;; U+1F600 — outside the BMP, Character/toChars returns 2 chars.
        codepoint 0x1F600]
    (events/dispatch-char! rt codepoint)
    (is (= (str (char 0xD83D) (char 0xDE00)) @captured))))

(deftest dispatch-char-unfocused-is-noop-test
  (let [rt (char-runtime)
        captured (atom :unset)]
    (events/on! rt :root :change-content
      (fn [_ _ evt] (reset! captured (:char evt))))
    ;; No gain-focus! — dispatch must not reach the handler.
    (events/dispatch-char! rt (int \a))
    (is (= :unset @captured))))
