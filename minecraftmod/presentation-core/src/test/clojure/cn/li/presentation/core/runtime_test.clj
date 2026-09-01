(ns cn.li.presentation.core.runtime-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.nodetable :as nodetable]
            [cn.li.presentation.core.test-artifact :as ta])
  (:import [cn.li.presentation.core HostGeometry]
           [cn.li.mcmod.runtime.ui UiOp]))

(use-fixtures :each
  (fn [f] (nodetable/clear-tables-for-test!) (f)))

(deftest runtime-commits-state-before-effects
  (let [effects (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/runtime {:key :root})
        mount (runtime/mount!
                rt {:host {:stage :screen}
                    :view-id :academy/test/runtime
                    :artifact artifact
                    :state {:value 0}
                    :reduce (fn [state action payload]
                              (if (= action :increment)
                                {:state (update state :value + payload)
                                 :effects [{:type :record :value (+ (:value state) payload)}]
                                 :event-result :consume}
                                {:state state :effects [] :event-result :pass}))
                    :run-effect! #(swap! effects conj %)})]
    (is (= :consume (runtime/dispatch! rt mount {:action :increment :payload 2})))
    (is (= [{:type :record :value 2}] @effects))
    (runtime/update-host! rt mount (HostGeometry. 10.0 20.0 320 240 1.0))
    (is (= mount (-> (runtime/extract-stage! rt :screen {:time-nanos 1 :width 320 :height 240}) :mounts first :handle)))))

(deftest runtime-requires-owner-thread
  (let [foreign-thread (Thread.)
        rt (runtime/create-runtime {:owner-thread foreign-thread})]
    (is (thrown? IllegalStateException (runtime/unmount-all! rt)))))

(defn- extracted-commands [rt mount stage width height]
  (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 width height 1.0))
  (-> (runtime/extract-stage! rt stage {:width width :height height})
      :mounts first :commands))

(deftest extract-stage-produces-a-run-batched-draw-list
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/paint
                           {:key :root :op UiOp/RECT :width [:fixed 20.0] :height [:fixed 10.0]
                            :rgba (unchecked-int 0xFF00FF00)})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/paint
                                  :artifact artifact :state {}})
        dl (extracted-commands rt mount :screen 100 100)]
    (is (= 1 (.count dl)))
    (is (= UiOp/RECT (aget (.op dl) 0)))
    (is (= (unchecked-int 0xFF00FF00) (aget (.rgba dl) 0)))))

(deftest bound-text-resolves-through-item-label-coercion
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/button
                           {:key :root :flags #{:hit-testable} :width [:fixed 80.0] :height [:fixed 20.0]
                            :children [{:op UiOp/RECT :width [:fill 1.0] :height [:fill 1.0]}
                                      {:op UiOp/TEXT :bind {:text [:state :button]}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/button
                                  :artifact artifact :state {:button {:label "Save"}}})
        dl (extracted-commands rt mount :screen 100 40)]
    (is (= 2 (.count dl)))
    (is (= "Save" (aget (.aux dl) 1)))))

(deftest collection-expands-once-per-item-and-resolves-item-scoped-bindings
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/collection
                           {:key :root :direction :column
                            :flags #{:is-collection :has-direction}
                            :bind {:items [:state :lines]}
                            :children [{:op UiOp/TEXT :height [:fixed 10.0] :bind {:text [:item :label]}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/collection
                                  :artifact artifact
                                  :state {:lines [{:label "One"} {:label "Two"}]}})
        dl (extracted-commands rt mount :screen 100 40)]
    (is (= 2 (.count dl)))
    (is (= ["One" "Two"] (vec (take 2 (.aux dl)))))))

(deftest progress-bar-accepts-a-boolean-value
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/progress
                           {:key :root :op UiOp/PROGRESS :width [:fixed 20.0] :height [:fixed 4.0]
                            :bind {:value [:state :loading?]}})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/progress
                                  :artifact artifact :state {:loading? true}})
        dl (extracted-commands rt mount :screen 20 4)]
    (is (= 2 (.count dl))) ; track + fill
    (is (= 20.0 (aget (.geom dl) (+ (* 1 4) 2)))))) ; fill width = track width * 1.0

(deftest pointer-down-on-a-hit-testable-node-fires-its-activate-action
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/input
                           {:key :root :direction :row :flags #{:has-direction}
                            :children [{:key :left :flags #{:hit-testable} :width [:fixed 50.0] :height [:fixed 20.0]
                                       :on {:activate :demo/left}}
                                      {:key :right :flags #{:hit-testable} :width [:fixed 50.0] :height [:fixed 20.0]
                                       :on {:activate :demo/right}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 75 :y 10 :button 0})))
    (is (= [:demo/right {:target :right}] @seen))))

(deftest pointer-coordinates-are-offset-by-a-nonzero-host-origin
  ;; A HUD overlay mounted at a nonzero screen origin gets mount-local
  ;; pointer coordinates; hit-testing must add the geometry's origin back
  ;; in before comparing against the arranged (absolute) rect.
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/origin
                           {:key :root
                            :children [{:key :btn :flags #{:hit-testable} :width [:fixed 20.0] :height [:fixed 20.0]
                                       :on {:activate :demo/go}}]})
        mount (runtime/mount! rt {:host {:stage :hud} :artifact artifact :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 50.0 60.0 100 100 1.0))
    ;; mount-local (5,5) + origin (50,60) = absolute (55,65), inside the 50,60..70,80 button rect.
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 5 :y 5 :button 0})
    (is (= [:demo/go {:target :btn}] @seen))))

(deftest viewport-space-pointer-events-skip-the-origin-offset
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/viewport-space
                           {:key :root
                            :children [{:key :btn :flags #{:hit-testable} :width [:fixed 20.0] :height [:fixed 20.0]
                                       :on {:activate :demo/go}}]})
        mount (runtime/mount! rt {:host {:stage :hud} :artifact artifact :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 50.0 60.0 100 100 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 5 :y 5 :button 0 :space :viewport})
    (is (= :input/pointer (first @seen)) "a viewport-space click at (5,5) misses the button, which is at absolute (50,60)")
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 55 :y 65 :button 0 :space :viewport})
    (is (= [:demo/go {:target :btn}] @seen))))

(deftest pointer-down-in-a-repeater-carries-item-and-index
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/repeater-input
                           {:key :root :direction :column :flags #{:is-collection :has-direction}
                            :width [:fixed 100.0] :height [:fixed 40.0]
                            :bind {:items [:state :items]}
                            :children [{:key :row/action :flags #{:hit-testable}
                                       :width [:fill 1.0] :height [:fixed 20.0]
                                       :bind {:text [:item :label]} :on {:activate :demo/item}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact
                                  :state {:items [{:label "One"} {:label "Two"}]}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 40 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 30 :button 0})))
    (is (= [:demo/item {:target :row/action :item {:label "Two"} :index 1}] @seen))))

(deftest text-input-focuses-on-click-and-accepts-typed-characters
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/text-input
                           {:key :root :flags #{:hit-testable :focusable}
                            :width [:fixed 100.0] :height [:fixed 20.0]
                            :bind {:text [:state :query]}
                            :on {:change :edit/change :submit :edit/submit}})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state {:query ""}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})
    (runtime/dispatch! rt mount {:type :character :text "h"})
    (runtime/dispatch! rt mount {:type :character :text "i"})
    (is (= "hi" (get-in (runtime/instance! rt mount) [:view-state :query])))))

(deftest progress-drag-reports-a-clamped-ratio
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/progress-input
                           {:key :root :op UiOp/PROGRESS :flags #{:hit-testable}
                            :width [:fixed 20.0] :height [:fixed 4.0]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 20 4 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 15 :y 2 :button 0})
    (is (= :input/progress (first @seen)))
    (is (= 0.75 (double (:value (second @seen)))))))

(deftest scroll-wheel-over-a-scroll-container-advances-its-offset
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/scroll
                           {:key :list :flags #{:has-clip :is-scroll :is-collection :has-direction}
                            :direction :column :width [:fixed 100.0] :height [:fixed 20.0]
                            :bind {:items [:state :items]}
                            :children [{:height [:fixed 10.0] :bind {:text [:item :label]}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact
                                  :state {:items (mapv (fn [n] {:label (str n)}) (range 4))}})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    ;; dispatch!'s return value is the *reducer's* :event-result (:pass, since
    ;; no custom :reduce was supplied) -- the scroll action's effect is
    ;; observable via :scroll-offsets, which is what actually matters here.
    (runtime/dispatch! rt mount {:type :scroll :x 5 :y 10 :delta -1})
    (is (= 12.0 (double (get (:scroll-offsets (runtime/instance! rt mount)) :list))))))

(deftest hover-target-tracks-across-pointer-moves
  ;; One :input/hover dispatch per move (matching the pre-rewrite runtime's
  ;; single combined action per move rather than a separate leave+enter
  ;; pair) -- the payload's :hover-event/:previous-hover distinguish moving
  ;; onto vs off of a target.
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/hover
                           {:key :root :direction :row :flags #{:has-direction}
                            :children [{:key :left :flags #{:hit-testable} :width [:fixed 50.0] :height [:fixed 20.0]}
                                      {:key :right :flags #{:hit-testable} :width [:fixed 50.0] :height [:fixed 20.0]}]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state {}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action payload])
                                            {:state state :event-result :pass})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 10 :y 10})
    (is (= :left (:key (:hover-target (runtime/instance! rt mount)))))
    (is (= [:input/hover {:target :left :hover? true :hover-event :enter :previous-hover nil}]
           (first @seen)))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 90 :y 10})
    (is (= :right (:key (:hover-target (runtime/instance! rt mount)))))
    (is (= [:input/hover {:target :right :hover? true :hover-event :enter :previous-hover :left}]
           (second @seen)))))
