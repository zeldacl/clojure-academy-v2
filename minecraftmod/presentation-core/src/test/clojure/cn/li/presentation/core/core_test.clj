(ns cn.li.presentation.core.core-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.core.input :as input]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.layout :as layout])
  (:import [cn.li.presentation.core FrameContext HostDescriptor HostDescriptor$HostKind
            HostDescriptor$InputPolicy TemplateId PresentationInputEvent$CharacterInput]
           [cn.li.mcmod.runtime RenderCommand$Quad RenderStage]))

(deftest input-dispatches-capture-target-bubble-and-pointer-capture
  (let [runtime (input/create)
        calls (atom [])
        handler (fn [phase _event]
                  (swap! calls conj phase)
                  (if (= phase :target) cn.li.presentation.core.EventResult/CAPTURE_POINTER
                      cn.li.presentation.core.EventResult/PASS))]
    (input/register-node! runtime :root nil handler)
    (input/register-node! runtime :button :root handler)
    ;; dispatch! must hand back the real terminal EventResult, not just
    ;; whether something consumed it -- callers outside presentation-core
    ;; (native Minecraft input fallback) need to distinguish CONSUME from
    ;; CAPTURE_POINTER, not just "was it PASS".
    (is (= cn.li.presentation.core.EventResult/CAPTURE_POINTER
           (input/dispatch! runtime :button :click)))
    (is (= [:capture :target] @calls))
    (is (= :button (:pointer-capture (input/snapshot runtime))))))

(deftest input-dispatch-returns-pass-when-nothing-handles-it
  (let [runtime (input/create)]
    (input/register-node! runtime :orphan nil nil)
    (is (= cn.li.presentation.core.EventResult/PASS
           (input/dispatch! runtime :orphan :click)))))

(deftest extract-emits-commands-for-mounted-host
  (let [rt (runtime/create-runtime
             {:template-resolver identity
              :template-renderer (fn [_template _model _ctx]
                                   [(RenderCommand$Quad. 0.0 0.0 8.0 8.0 -1)])})
        host (HostDescriptor. "hud" HostDescriptor$HostKind/HUD 0 0 nil
                              HostDescriptor$InputPolicy/PASSTHROUGH)
        _handle (runtime/mount! rt host ::template nil)
        packet (runtime/extract! rt (FrameContext. 9 0.016 320 180))]
    (is (= 9 (.frameId packet)))
    (is (= 1 (count (.passes packet))))
    (is (= RenderStage/HUD (.stage (first (.passes packet)))))
    (is (= 1 (count (.commands (first (.passes packet))))))))

(deftest extract-returns-empty-passes-with-no-mounts
  (let [rt (runtime/create-runtime)
        packet (runtime/extract! rt (FrameContext. 9 0.016 320 180))]
    (is (= 9 (.frameId packet)))
    (is (empty? (.passes packet)))))

(deftest extract-memoizes-same-frame-id-until-invalidated
  (let [render-calls (atom 0)
        rt (runtime/create-runtime
             {:template-resolver identity
              :template-renderer (fn [_template _model _ctx]
                                   (swap! render-calls inc)
                                   [(RenderCommand$Quad. 0.0 0.0 8.0 8.0 -1)])})
        host (HostDescriptor. "hud" HostDescriptor$HostKind/HUD 0 0 nil
                              HostDescriptor$InputPolicy/PASSTHROUGH)
        handle (runtime/mount! rt host ::template nil)]
    ;; mount! already invalidated once; a second call with the same frame id
    ;; must hit the cache rather than re-run the template for every stage
    ;; (HUD, Screen, world, ...) that asks for the same real frame.
    (runtime/extract! rt (FrameContext. 1 0.0 320 180))
    (is (= 1 @render-calls))
    (runtime/extract! rt (FrameContext. 1 0.0 320 180))
    (is (= 1 @render-calls) "same frame id must not re-render")
    (runtime/extract! rt (FrameContext. 2 0.0 320 180))
    (is (= 2 @render-calls) "a new frame id must rebuild")
    (runtime/set-input-handler! rt handle (fn [_] cn.li.presentation.core.EventResult/CONSUME))
    (runtime/dispatch! rt handle {:type :pointer :event-type :down :x 0.0 :y 0.0 :button 0})
    (runtime/extract! rt (FrameContext. 2 0.0 320 180))
    (is (= 3 @render-calls) "a consumed input event must invalidate even the same frame id")
    ;; mount!'s single input-node registration must keep tracking whatever
    ;; handler is currently set, across any number of dispatch! calls,
    ;; without dispatch! itself re-registering a node on every call.
    (runtime/set-input-handler! rt handle (fn [_] cn.li.presentation.core.EventResult/PASS))
    (is (= cn.li.presentation.core.EventResult/PASS
           (runtime/dispatch! rt handle {:type :pointer :event-type :down :x 0.0 :y 0.0 :button 0})))))

(deftest layout-resolves-row-fill-and-fraction
  ;; Plain {:type :props :children} maps -- the shape layout.clj actually
  ;; consumes (presentation-compiler/render.clj feeds it compiled
  ;; TemplateNodes via this same shape); no dependency on the deleted
  ;; core/tree.clj RNode representation.
  (let [root {:type :row
              :props {:direction :row :gap 4 :width :fill :height 20}
              :children [{:type :fixed :props {:width 20 :height :fill} :children []}
                         {:type :fraction :props {:width [:fraction 0.5] :height :fill} :children []}
                         {:type :fill :props {:width :fill :height :fill} :children []}]}
        laid (layout/layout root 100 20)
        children (:children laid)]
    (is (= 100.0 (get-in laid [:layout :width])))
    (is (= 20.0 (get-in (nth children 0) [:layout :width])))
    (is (= 50.0 (get-in (nth children 1) [:layout :width])))
    (is (> (get-in (nth children 2) [:layout :width]) 0.0))))

(deftest runtime-normalizes-neutral-input-maps
  (let [rt (runtime/create-runtime)
        host (HostDescriptor. "input" HostDescriptor$HostKind/SCREEN 0 0 nil
                              HostDescriptor$InputPolicy/CAPTURE)
        handle (runtime/mount! rt host (TemplateId. "input") nil)
        received (atom nil)]
    (runtime/set-input-handler! rt handle #(reset! received %))
    (runtime/dispatch! rt handle {:type :character :text "界" :composing? true})
    (is (instance? PresentationInputEvent$CharacterInput @received))
    (is (= "界" (.text ^PresentationInputEvent$CharacterInput @received)))
    (is (.composing ^PresentationInputEvent$CharacterInput @received))))
