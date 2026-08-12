(ns cn.li.mcmod.ui.events-test
  "Regression tests for UI event dispatch.
   dispatch-char! must deliver the typed character as a real string: char[]
   does not override toString, so the old (Character/toChars ...) .toString
   produced the identity string (\"[C@<hash>\") and every codepoint arrived
   as (first ...) = \\[ in :change-content consumers (developer console input
   showed [[[[[[ regardless of the key pressed).

   dispatch-mouse-press! must focus editable text nodes on click even when
   the click has no handler — 5d94832ce made unhandled clicks clear focus
   (so visual-only nodes no longer swallow vanilla inventory-slot clicks),
   which also killed typing in handler-less editable fields (wireless-node
   info-area name/password, wireless-tab password boxes)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.events :as events])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(defn- char-runtime
  "Minimal runtime with a single focusable group node."
  ^UiRt []
  (let [r (rt/create-runtime)]
    (rt/build! r {:kind :group
                  :props {:id :root :x 0.0 :y 0.0 :w 100.0 :h 100.0}})
    (rt/resize! r 200.0 200.0)
    r))

(defn- field-runtime
  "Runtime with one editable text field and one visual-only box."
  ^UiRt []
  (let [r (rt/create-runtime)]
    (rt/build! r {:kind :group
                  :props {:id :root :x 0.0 :y 0.0 :w 300.0 :h 100.0}
                  :children [{:kind :text
                              :props {:id :field :x 10.0 :y 10.0 :w 80.0 :h 10.0
                                      :text "" :font-size 8.0 :editable? true}}
                             {:kind :box
                              :props {:id :deco :x 10.0 :y 30.0 :w 80.0 :h 10.0
                                      :fill 0xFFFFFFFF}}]})
    (rt/resize! r 300.0 100.0)
    r))

(defn- text-value [^INode n]
  (str (.getOSlot n 0)))

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

(deftest click-on-editable-field-focuses-and-types-test
  (let [rt (field-runtime)]
    (layout/ensure-layout! rt)
    (layout/ensure-tape! rt)
    ;; Click on the editable field (no handler) — must gain focus.
    (events/dispatch-mouse-press! rt 15.0 15.0 0)
    (is (= (.getIdx (rt/node-by-id rt :field)) (rt/focus-idx rt)))
    ;; Typing then appends to the field.
    (events/dispatch-editable-key! rt 0 (char \a))
    (is (= "a" (text-value (rt/node-by-id rt :field))))))

(deftest click-on-visual-only-node-clears-focus-test
  (let [rt (field-runtime)]
    (layout/ensure-layout! rt)
    (layout/ensure-tape! rt)
    (events/dispatch-mouse-press! rt 15.0 15.0 0)
    (is (= (.getIdx (rt/node-by-id rt :field)) (rt/focus-idx rt)))
    ;; Click on a handler-less visual box — focus must clear (vanilla
    ;; inventory-slot click-through), NOT stick to the box.
    (events/dispatch-mouse-press! rt 15.0 35.0 0)
    (is (= -1 (rt/focus-idx rt)))))

(deftest reclick-editable-restores-typing-test
  (let [rt (field-runtime)]
    (layout/ensure-layout! rt)
    (layout/ensure-tape! rt)
    (events/dispatch-mouse-press! rt 15.0 15.0 0)
    (events/dispatch-editable-key! rt 0 (char \a))
    ;; Focus lost (click elsewhere), then re-click the field — typing resumes.
    (events/dispatch-mouse-press! rt 15.0 35.0 0)
    (is (= -1 (rt/focus-idx rt)))
    (events/dispatch-mouse-press! rt 15.0 15.0 0)
    (events/dispatch-editable-key! rt 0 (char \b))
    (is (= "ab" (text-value (rt/node-by-id rt :field))))))
