(ns cn.li.presentation.core.core-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.core.input :as input]
            [cn.li.presentation.core.frame :as frame]
            [cn.li.presentation.core.dirty :as dirty]
            [cn.li.presentation.core.semantics :as semantics]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.tree :as tree]
            [cn.li.presentation.core.layout :as layout]
            [cn.li.presentation.core.animation :as animation])
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
    (input/dispatch! runtime :button :click)
    (is (= [:capture :target] @calls))
    (is (= :button (:pointer-capture (input/snapshot runtime))))))

(deftest frame-mailbox-drops-stale-frames
  (let [mailbox (frame/mailbox)]
    (frame/publish! mailbox :frame-1)
    (frame/publish! mailbox :frame-2)
    (frame/publish! mailbox :frame-3)
    (is (= :frame-3 (frame/poll-latest! mailbox)))
    (is (nil? (frame/poll-latest! mailbox)))
    (is (= 3 @(:published mailbox)))))

(deftest dynamic-dirty-does-not-mark-layout
  (let [state (dirty/create)]
    (dirty/take! state)
    (dirty/dynamic-update! state :transform)
    (is (= #{:transform} @state))
    (is (thrown? Exception (dirty/dynamic-update! state :layout)))))

(deftest semantics-tree-produces-narration
  (let [tree (semantics/create)]
    (semantics/upsert! tree :root nil :dialog "Terminal" nil nil)
    (semantics/upsert! tree :input :root :textbox "Query" "abc" nil)
    (semantics/upsert! tree :submit :root :button "Search" nil nil)
    (is (= ["Terminal" "Query: abc" "Search"] (semantics/narration tree)))))

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
    (is (= 3 @render-calls) "a consumed input event must invalidate even the same frame id")))

(deftest keyed-reconcile-reuses-moved-nodes-and-disposes-removed-nodes
  (let [closed (atom [])
        spec (fn [keys]
               {:type :stack :key :root
                :children (mapv (fn [k]
                                  {:type :item :key k
                                   :subscriptions [(fn [] (swap! closed conj k))]})
                                keys)})
        first-tree (:node (tree/reconcile nil (spec [:a :b :c])))
        b-before (tree/find-by-key first-tree :b)
        second-tree (:node (tree/reconcile first-tree (spec [:c :b :a])))
        b-after (tree/find-by-key second-tree :b)]
    (is (identical? b-before b-after))
    (is (= [:c :b :a] (mapv :key (:children second-tree))))
    (let [third-tree (:node (tree/reconcile second-tree (spec [:c :a])))]
      (is (= [:b] @closed))
      (tree/dispose! third-tree)
      (is (= #{:a :b :c} (set @closed))))))

(deftest layout-resolves-row-fill-and-fraction
  (let [root (tree/node {:type :row :key :root :direction :row :gap 4
                         :width :fill :height 20
                         :children [{:type :fixed :key :left :width 20 :height :fill}
                                    {:type :fraction :key :middle :width [:fraction 0.5] :height :fill}
                                    {:type :fill :key :right :width :fill :height :fill}]})
        laid (layout/layout root 100 20)
        children (:children laid)]
    (is (= 100.0 (get-in laid [:layout :width])))
    (is (= 20.0 (get-in (nth children 0) [:layout :width])))
    (is (= 50.0 (get-in (nth children 1) [:layout :width])))
    (is (> (get-in (nth children 2) [:layout :width]) 0.0))))

(deftest runtime-owns-retained-tree-and-layout
  (let [rt (runtime/create-runtime)
        host (HostDescriptor. "tree" HostDescriptor$HostKind/SCREEN 0 0 nil
                              HostDescriptor$InputPolicy/PASSTHROUGH)
        spec (fn [key] {:type :stack :key :root :width :fill :height :fill
                        :children [{:type :text :key key :width :fill :height 10}]})
        handle (runtime/mount-tree! rt host (TemplateId. "tree") nil (spec :a))
        old-child (tree/find-by-key (runtime/retained-tree rt handle) :a)]
    (runtime/reconcile-tree! rt handle (spec :a))
    (is (identical? old-child
                   (tree/find-by-key (runtime/retained-tree rt handle) :a)))
    (runtime/layout-tree! rt handle 320 180)
    (is (= 320.0 (get-in (runtime/retained-tree rt handle) [:layout :width])))
    (is (= 180.0 (get-in (runtime/retained-tree rt handle) [:layout :height])))
    (is (nil? (runtime/unmount! rt handle)))
    (is (nil? (runtime/retained-tree rt handle)))))

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

(deftest frame-graph-validates-unknown-stages-and-cycles
  (let [edges (frame/frame-graph)]
    (is (= (set frame/stages) (set (frame/order edges))))
    (is (thrown? Exception
                 (frame/order (assoc edges :hud [:hud]))))
    (is (thrown? Exception
                 (frame/order (assoc edges :hud [:not-a-stage]))))))

(deftest animation-timeline-samples-and-completes-deterministically
  (let [tl (animation/timeline
             {:duration-ms 100
              :tracks {:alpha [{:at 0 :value 0.0}
                               {:at 100 :value 1.0 :easing :ease-in}]}})]
    (animation/advance! tl 50)
    (is (= 0.25 (double (:alpha (animation/sample tl)))))
    (animation/advance! tl 50)
    (is (= 1.0 (double (:alpha (animation/sample tl)))))
    (is (animation/done? tl))))
