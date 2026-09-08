(ns cn.li.presentation.core.runtime-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(deftest clean-frame-reuses-the-same-draw-list-object
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/memo
                           {:key :root :op UiOp/RECT :width [:fixed 20.0] :height [:fixed 10.0]
                            :bind {:rgba [:state :color]}})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/memo
                                  :artifact artifact :state {:color (unchecked-int 0xFF00FF00)}})
        dl1 (extracted-commands rt mount :screen 100 100)
        dl2 (extracted-commands rt mount :screen 100 100)]
    (is (identical? dl1 dl2))))

(deftest changed-binding-invalidates-the-cached-draw-list
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/memo-change
                           {:key :root :op UiOp/RECT :width [:fixed 20.0] :height [:fixed 10.0]
                            :bind {:rgba [:state :color]}})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/memo-change
                                  :artifact artifact :state {:color (unchecked-int 0xFF00FF00)}})
        dl1 (extracted-commands rt mount :screen 100 100)
        ;; CmdBuf hands out its live backing arrays uncopied (by design, see
        ;; its docstring) on the promise that a UiDrawList is fully consumed
        ;; before the next frame's paint overwrites them -- so read dl1's
        ;; values out to plain locals now, before triggering dl2.
        dl1-rgba (aget (.rgba dl1) 0)
        dl1-identity dl1]
    (runtime/update-view! rt mount (fn [state] (assoc state :color (unchecked-int 0xFFFF0000))))
    (let [dl2 (extracted-commands rt mount :screen 100 100)]
      (is (not (identical? dl1-identity dl2)))
      (is (= (unchecked-int 0xFF00FF00) dl1-rgba))
      (is (= (unchecked-int 0xFFFF0000) (aget (.rgba dl2) 0))))))

(deftest resize-invalidates-the-cached-draw-list-even-with-unchanged-state
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/memo-resize
                           {:key :root :op UiOp/RECT :width [:fill 1.0] :height [:fill 1.0]})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/memo-resize
                                  :artifact artifact :state {}})
        dl1 (extracted-commands rt mount :screen 100 100)
        ;; Read dl1's geometry now -- see changed-binding-invalidates-... for
        ;; why this must happen before the next extraction call.
        dl1-identity dl1
        dl1-w (double (aget (.geom dl1) 2))
        dl1-h (double (aget (.geom dl1) 3))
        dl2 (extracted-commands rt mount :screen 200 200)]
    (is (not (identical? dl1-identity dl2)))
    (is (= 100.0 dl1-w))
    (is (= 100.0 dl1-h))
    (is (= 200.0 (double (aget (.geom dl2) 2))))
    (is (= 200.0 (double (aget (.geom dl2) 3))))))

(deftest panel-geometry-pins-fit-content-to-minecraft-left-pos
  "Container hosts pass leftPos/topPos; design must paint there (not float-center)."
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/panel-fit
                           {:key :root :op UiOp/RECT :width [:fixed 290.0] :height [:fixed 187.0]
                            :rgba (unchecked-int 0xFF00FF00)}
                           :host {:kind :container :design-width 290 :design-height 187
                                  :scale-policy :fit})
        mount (runtime/mount! rt {:host {:stage :screen} :view-id :academy/test/panel-fit
                                  :artifact artifact :state {}})
        ;; Odd remainders: float center would be +0.5; Minecraft truncates.
        left 101
        top 47
        _ (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 800 480 1.0))
        dl (-> (runtime/extract-stage!
                 rt :screen {:width 800 :height 480
                             :panel-x left :panel-y top :panel-w 290 :panel-h 187})
               :mounts first :commands)
        ^HostGeometry g (:geometry (runtime/instance! rt mount))]
    (is (= (float left) (.originX g)))
    (is (= (float top) (.originY g)))
    (is (= 290 (.viewportWidth g)))
    (is (= 187 (.viewportHeight g)))
    (is (= (double left) (double (aget (.geom dl) 0))))
    (is (= (double top) (double (aget (.geom dl) 1))))))

(deftest a-no-op-dispatch-does-not-invalidate-a-clean-paint
  ;; dispatch! and extract-stage! share the same layout-freshness stamp
  ;; (ensure-layout-current!) but must NOT share paint freshness the same
  ;; way -- a pointer move that changes nothing must leave the next
  ;; extract-stage! free to keep reusing the already-painted UiDrawList.
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/dispatch-paint-share
                           {:key :root :op UiOp/RECT :width [:fixed 20.0] :height [:fixed 10.0]
                            :bind {:rgba [:state :color]}})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact
                                  :state {:color (unchecked-int 0xFF00FF00)}})
        dl1 (extracted-commands rt mount :screen 100 100)]
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 5.0 :y 5.0})
    (let [dl2 (-> (runtime/extract-stage! rt :screen {:width 100 :height 100}) :mounts first :commands)]
      (is (identical? dl1 dl2)))))

(deftest dispatch-triggered-state-change-invalidates-the-next-extract
  ;; The hazard this guards against: dispatch! updates the layout stamp
  ;; (it needed a current arena for hit-testing) but the reducer's state
  ;; change happens AFTER that hit-test, via present!, which never touches
  ;; the layout/paint stamps at all. The next extract-stage! must still
  ;; detect the binding change and repaint -- not be fooled by dispatch!
  ;; having already "seen" a matching stamp for the pre-toggle state.
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/dispatch-invalidate
                           {:key :root :flags #{:hit-testable} :width [:fixed 20.0] :height [:fixed 10.0]
                            :on {:activate :demo/toggle}
                            :children [{:op UiOp/RECT :width [:fill 1.0] :height [:fill 1.0]
                                       :bind {:rgba [:state :color]}}]})
        mount (runtime/mount!
               rt {:host {:stage :screen} :artifact artifact
                   :state {:color (unchecked-int 0xFF00FF00)}
                   :reduce (fn [state action _payload]
                             (if (= action :demo/toggle)
                               {:state (assoc state :color (unchecked-int 0xFFFF0000)) :event-result :consume}
                               {:state state :event-result :pass}))})
        dl1 (extracted-commands rt mount :screen 100 100)
        dl1-rgba (aget (.rgba dl1) 0)]
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 5.0 :y 5.0 :button 0})
    (let [dl2 (-> (runtime/extract-stage! rt :screen {:width 100 :height 100}) :mounts first :commands)]
      (is (= (unchecked-int 0xFF00FF00) dl1-rgba))
      (is (= (unchecked-int 0xFFFF0000) (aget (.rgba dl2) 0))))))

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
    (is (= [:demo/right {:target :right}] (update @seen 1 #(select-keys % [:target]))))))

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
    (is (= [:demo/go {:target :btn}] (update @seen 1 #(select-keys % [:target]))))))

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
    (is (= [:demo/go {:target :btn}] (update @seen 1 #(select-keys % [:target]))))))

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
    (is (= [:demo/item {:target :row/action :item {:label "Two"} :index 1}] (update @seen 1 #(select-keys % [:target :item :index]))))))

(deftest text-input-focuses-on-click-and-accepts-typed-characters
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/text-input
                           {:key :root :flags #{:hit-testable :focusable}
                            :width [:fixed 100.0] :height [:fixed 20.0]
                            :bind {:text [:state :query]}
                            :on {:change :edit/change :submit :edit/submit}
                            :semantics {:role :textbox :field :query}})
        ;; test_artifact may not copy :semantics onto node/semantics — inject it.
        artifact (assoc artifact :node/semantics [{:role :textbox :field :query}])
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state {:query ""}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})
    (is (= :query (:field (:focus (runtime/instance! rt mount)))))
    (runtime/dispatch! rt mount {:type :character :text "h"})
    (runtime/dispatch! rt mount {:type :character :text "i"})
    (is (= "hi" (get-in (runtime/instance! rt mount) [:view-state :query])))
    (is (= :query (:field (second (last @seen)))))))

(deftest tab-and-shift-tab-cycle-focusable-controls
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/tab-focus
                           {:key :root :direction :row :flags #{:has-direction}
                            :width [:fixed 120.0] :height [:fixed 20.0]
                            :children
                            [{:key :first :flags #{:hit-testable :focusable}
                              :width [:fixed 60.0] :height [:fixed 20.0]
                              :bind {:text [:state :first]}
                              :semantics {:role :textbox :field :first}}
                             {:key :second :flags #{:hit-testable :focusable}
                              :width [:fixed 60.0] :height [:fixed 20.0]
                              :bind {:text [:state :second]}
                              :semantics {:role :textbox :field :second}}]})
        artifact (assoc artifact :node/semantics [nil {:role :textbox :field :first}
                                                   {:role :textbox :field :second}])
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact
                                  :state {:first "" :second ""}
                                  :reduce (fn [state _ _] {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 120 20 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :key :key-code 258})))
    (is (= :first (:field (:focus (runtime/instance! rt mount)))))
    (is (= :consume (runtime/dispatch! rt mount {:type :key :key-code 258})))
    (is (= :second (:field (:focus (runtime/instance! rt mount)))))
    (is (= :consume (runtime/dispatch! rt mount {:type :key :key-code 258 :shift? true})))
    (is (= :first (:field (:focus (runtime/instance! rt mount)))))))
(defn- wireless-node-golden-file
  []
  (first (filter #(.isFile ^java.io.File %)
                 [(io/file "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/wireless-node.uic.edn")
                  (io/file ".." "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/wireless-node.uic.edn")
                  (io/file ".." "minecraftmod" "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/wireless-node.uic.edn")])))

(defn- hist-quad
  [h]
  {:kind :quad :x 22.4 :y (- 79.2 h) :w 6.4 :h (double h)
   :rgba (unchecked-int 0xFF25C4FF)})

(defn- painted-hist-heights
  "Collect RECT command heights that match hist-bar width 6.4 (energy column)."
  [dl]
  (into []
        (keep (fn [i]
                (when (and (= UiOp/RECT (aget (.op dl) (int i)))
                           (< (Math/abs (- (double (aget (.geom dl) (+ (* (int i) 4) 2))) 6.4)) 0.01))
                  (double (aget (.geom dl) (+ (* (int i) 4) 3))))))
        (range (.count dl))))

(deftest wireless-node-hist-bars-resize-on-present
  "Regression: info-area hist rects must grow when hist-bars :h changes
   (bound :width/:height on :rect — not :composite)."
  (let [art-file (wireless-node-golden-file)]
    (is (some? art-file) "wireless-node golden artifact must be on disk")
    (let [artifact (edn/read-string (slurp art-file))
          rt (runtime/create-runtime)
          initial {:tech-tabs []
                   :inv-page-visible? true
                   :wireless-page-visible? false
                   :info-area {:histograms [{:id :energy :label "Energy"
                                             :value "100 IF" :color (unchecked-int 0xFF25C4FF)}]
                               :hist-bars [(hist-quad 4.8)]
                               :sep-label "-- Info --" :sep-visible? true
                               :fields []}
                   :slot-anchors []}
          mount (runtime/mount!
                  rt {:host {:stage :screen} :artifact artifact
                      :state initial})
          _ (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 800 480 1.0))
          h0 (painted-hist-heights
               (-> (runtime/extract-stage! rt :screen {:width 800 :height 480})
                   :mounts first :commands))
          _ (runtime/present!
              rt mount
              (-> initial
                  (assoc-in [:info-area :hist-bars] [(hist-quad 36.0)])
                  (assoc-in [:info-area :histograms 0 :value] "12000 IF")))
          h1 (painted-hist-heights
               (-> (runtime/extract-stage! rt :screen {:width 800 :height 480})
                   :mounts first :commands))]
      (is (seq h0) "low energy hist quad must paint")
      (is (seq h1) "high energy hist quad must paint")
      (is (< (apply max h0) (apply max h1))))))

(deftest wireless-node-golden-text-input-accepts-click-and-characters
  "Regression: compact info-area text-inputs must focus and append typed chars."
  (let [art-file (wireless-node-golden-file)]
    (is (some? art-file) "wireless-node golden artifact must be on disk")
    (let [artifact (edn/read-string (slurp art-file))
          rt (runtime/create-runtime)
          seen (atom [])
          fields [{:id :range :label "Range" :value "0"
                   :editable? false :readonly? true :draft-key :range}
                  {:id :owner :label "Owner" :value "me"
                   :editable? false :readonly? true :draft-key :owner}
                  {:id :node-name :label "Node Name" :value "ab"
                   :editable? true :readonly? false :draft-key :node-name}
                  {:id :password :label "Password" :value ""
                   :editable? true :readonly? false :draft-key :network-password}]
          mount (runtime/mount!
                  rt {:host {:stage :screen} :artifact artifact
                      :state {:node-name "ab" :network-password ""
                              :tech-tabs []
                              :inv-page-visible? true
                              :wireless-page-visible? false
                              :info-area {:histograms [] :hist-bars []
                                          :sep-label "-- Info --" :sep-visible? true
                                          :fields fields}
                              :slot-anchors []}
                      :reduce (fn [state action payload]
                                (swap! seen conj [action (:field payload) (:value payload)])
                                {:state state :event-result :consume})})]
      ;; Fit 290×187 into 800×480 like a real container screen.
      (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 800 480 1.0))
      (runtime/extract-stage! rt :screen {:width 800 :height 480})
      (let [;; Integer center matches AbstractContainerScreen leftPos/topPos.
            ox (quot (- 800 290) 2)
            oy (quot (- 480 187) 2)
            ;; Node Name value cell: clip(179,5)+fields-root(y88)+sep(11)+row2
            ;; → value at ~(225, 5+88+11+20) = ~(225,124)
            mx (+ ox 225.0 10.0)
            my (+ oy 124.0 5.0)
            ;; Password row is one 10px row below name.
            px mx
            py (+ my 10.0)]
        (is (= :consume
               (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                            :space :viewport :x mx :y my :button 0})))
        (is (= :node-name (:field (:focus (runtime/instance! rt mount)))))
        (is (= [:state :node-name] (:path (:focus (runtime/instance! rt mount)))))
        (runtime/dispatch! rt mount {:type :character :text "Z"})
        (is (= "abZ" (get-in (runtime/instance! rt mount) [:view-state :node-name])))
        (is (= "abZ" (get-in (runtime/instance! rt mount) [:view-state :info-area :fields 2 :value])))
        (is (= :node-name (second (last @seen))))
        ;; Focusing password must not wipe the name draft.
        (is (= :consume
               (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                            :space :viewport :x px :y py :button 0})))
        (is (= :password (:field (:focus (runtime/instance! rt mount)))))
        (is (= "abZ" (get-in (runtime/instance! rt mount) [:view-state :node-name])))
        (runtime/dispatch! rt mount {:type :character :text "p"})
        (is (= "p" (get-in (runtime/instance! rt mount) [:view-state :network-password])))
        (is (= "abZ" (get-in (runtime/instance! rt mount) [:view-state :node-name])))))))

(deftest info-field-item-text-rewrites-focus-to-draft-key
  "Editable info rows bind [:item :value] but must write draft-key + fields[idx]."
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/info-draft
                           {:key :list :flags #{:is-collection :has-direction}
                            :direction :column :width [:fixed 100.0] :height [:fixed 40.0]
                            :bind {:items [:state :info-area :fields]}
                            :children
                            [{:key :row :flags #{:hit-testable :focusable}
                              :width [:fixed 100.0] :height [:fixed 20.0]
                              :bind {:text [:item :value]}
                              :on {:change :edit/change}
                              :children []}]})
        ;; Inject semantics without static :field so item :id wins.
        artifact (assoc artifact :node/semantics [nil {:role :textbox}])
        mount (runtime/mount!
                rt {:host {:stage :screen} :artifact artifact
                    :state {:node-name "ab"
                            :info-area {:fields
                                        [{:id :node-name :value "ab" :draft-key :node-name
                                          :editable? true}
                                         {:id :password :value "" :draft-key :network-password
                                          :editable? true}]}}
                    :reduce (fn [state _ _] {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 40 1.0))
    (is (= :consume
           (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})))
    (let [focus (:focus (runtime/instance! rt mount))]
      (is (= :node-name (:field focus)))
      (is (= [:state :node-name] (:path focus)))
      (is (= 0 (:item-index focus)))
      (is (integer? (:instance focus))))
    (runtime/dispatch! rt mount {:type :character :text "Z"})
    (is (= "abZ" (get-in (runtime/instance! rt mount) [:view-state :node-name])))
    (is (= "abZ" (get-in (runtime/instance! rt mount) [:view-state :info-area :fields 0 :value])))
    (let [first-inst (:instance (:focus (runtime/instance! rt mount)))]
      ;; Second repeater row must not reuse the first row's focus instance
      ;; (caret previously painted on the first matching compiled node).
      (is (= :consume
             (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 30 :button 0})))
      (let [focus2 (:focus (runtime/instance! rt mount))]
        (is (= :password (:field focus2)))
        (is (= 1 (:item-index focus2)))
        (is (not= first-inst (:instance focus2)))))))

(deftest info-field-backspace-deletes-glyph-and-field-value
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/info-backspace
                           {:key :list :flags #{:is-collection :has-direction}
                            :direction :column :width [:fixed 100.0] :height [:fixed 20.0]
                            :bind {:items [:state :info-area :fields]}
                            :children
                            [{:key :row :flags #{:hit-testable :focusable}
                              :width [:fixed 100.0] :height [:fixed 20.0]
                              :bind {:text [:item :value]}
                              :on {:change :container/text-change}
                              :children []}]})
        artifact (assoc artifact :node/semantics [nil {:role :textbox}])
        mount (runtime/mount!
                rt {:host {:stage :screen} :artifact artifact
                    :state {:node-name "ab"
                            :info-area {:fields
                                        [{:id :node-name :value "ab" :draft-key :node-name
                                          :editable? true}]}}
                    :reduce (fn [state action payload]
                              (swap! seen conj [action (:value payload)])
                              {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})
    (is (= :consume
           (runtime/dispatch! rt mount {:type :key :key-code 259})))
    (is (= "a" (get-in (runtime/instance! rt mount) [:view-state :node-name])))
    (is (= "a" (get-in (runtime/instance! rt mount) [:view-state :info-area :fields 0 :value])))
    (is (= :container/text-change (ffirst (filter #(= :container/text-change (first %)) @seen))))
    (is (= "a" (second (first (filter #(= :container/text-change (first %)) @seen)))))))

(deftest wireless-row-password-accepts-typed-characters-without-draft-key
  "Wireless list rows bind [:item :password] with no draft-key; edits must land
   on :network-nodes and enrich :item for :container/wireless-row-password."
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/wireless-row
                           {:key :list :flags #{:is-collection :has-direction}
                            :direction :column :width [:fixed 100.0] :height [:fixed 20.0]
                            :bind {:items [:state :network-nodes]}
                            :children
                            [{:key :pwd :flags #{:hit-testable :focusable}
                              :width [:fixed 100.0] :height [:fixed 20.0]
                              :bind {:text [:item :password]}
                              :on {:change :container/wireless-row-password}
                              :semantics {:role :textbox :field :row-password}
                              :children []}]})
        artifact (assoc artifact :node/semantics
                        [nil {:role :textbox :field :row-password}])
        mount (runtime/mount!
                rt {:host {:stage :screen} :artifact artifact
                    :state {:network-nodes
                            [{:ssid "net" :pos-x 1 :pos-y 2 :pos-z 3
                              :node-name "A" :password "" :is-encrypted? true}]}
                    :reduce (fn [state action payload]
                              (swap! seen conj [action (:value payload) (:item payload)])
                              {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (is (= :consume
           (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})))
    (let [focus (:focus (runtime/instance! rt mount))]
      (is (= [:item :password] (:path focus)))
      (is (= 0 (:item-index focus)))
      (is (map? (:item focus))))
    (runtime/dispatch! rt mount {:type :character :text "s"})
    (runtime/dispatch! rt mount {:type :character :text "e"})
    (is (= "se" (get-in (runtime/instance! rt mount) [:view-state :network-nodes 0 :password])))
    (is (= :container/wireless-row-password (ffirst (filter #(= :container/wireless-row-password (first %)) @seen))))
    (is (= "se" (second (last @seen))))
    (is (= "se" (get-in (last @seen) [2 :password])))
    (runtime/dispatch! rt mount {:type :key :key-code 259})
    (is (= "s" (get-in (runtime/instance! rt mount) [:view-state :network-nodes 0 :password])))))

(deftest clear-focus-drops-caret-state
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/clear-focus
                           {:key :root :flags #{:hit-testable :focusable}
                            :width [:fixed 100.0] :height [:fixed 20.0]
                            :bind {:text [:state :query]}
                            :on {:change :edit/change}
                            :semantics {:role :textbox :field :query}})
        artifact (assoc artifact :node/semantics [{:role :textbox :field :query}])
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state {:query "hi"}
                                  :reduce (fn [state _ _] {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})
    (is (some? (:focus (runtime/instance! rt mount))))
    (runtime/clear-focus! rt mount)
    (is (nil? (:focus (runtime/instance! rt mount))))))

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
  ;; One hover dispatch per move (matching the pre-rewrite runtime's
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

(deftest collection-item-hover-dispatches-on-hover-with-item-payload
  ;; List/grid templates share one node key; hover must key off instance and
  ;; fire the node's :on :hover with the item, or tutorial list/tag hover never
  ;; updates while moving between rows.
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact (ta/build :academy/test/list-hover
                           {:key :list :flags #{:has-clip :is-scroll :is-collection :has-direction}
                            :direction :column :height [:fixed 40.0] :width [:fixed 80.0]
                            :bind {:items [:state :rows]}
                            :children [{:flags #{:hit-testable} :width [:fixed 80.0] :height [:fixed 20.0]
                                        :on {:hover :demo/row-hover :activate :demo/row}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact
                                  :state {:rows [{:id :a} {:id :b}]}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action payload])
                                            {:state state :event-result :pass})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 80 40 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 10 :y 5})
    (is (= :demo/row-hover (ffirst @seen)))
    (is (= :a (:id (:item (second (first @seen))))))
    (is (= :enter (:hover-event (second (first @seen)))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 10 :y 25})
    (is (= :demo/row-hover (first (second @seen))))
    (is (= :b (:id (:item (second (second @seen))))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 10 :y 5})
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 5})
    (is (= :demo/row (first (last @seen))))
    (is (= :a (:id (:item (second (last @seen))))))))

(deftest resource-index-for-uses-explicit-namespace-over-default
  (let [resource-index-for #'runtime/resource-index-for]
    (is (= 3 (resource-index-for {["bc" "icon"] 3 ["academy" "icon"] 9}
                                 "academy" {:namespace "bc" :path "icon"})))))

(deftest resource-index-for-falls-back-to-default-namespace-not-a-hardcoded-one
  ;; Regression for presentation-core hardcoding "academy" as the implicit
  ;; namespace for every unqualified resource ref, regardless of which
  ;; content module's view was asking (a real architectural leak this
  ;; refactor exists to close -- a BC view with an unqualified :src would
  ;; have silently resolved into AC's resource table, or missed entirely).
  ;; default-namespace is now the caller's own namespace, not a literal.
  (let [resource-index-for #'runtime/resource-index-for
        index {["academy" "icon"] 1 ["bc" "icon"] 2}]
    (is (= 1 (resource-index-for index "academy" {:path "icon"})))
    (is (= 2 (resource-index-for index "bc" {:path "icon"})))
    ;; A string src with its own "ns:path" prefix always wins over
    ;; default-namespace, map or string form alike.
    (is (= 1 (resource-index-for index "bc" "academy:icon")))
    (is (= 2 (resource-index-for index "academy" "bc:icon")))))

(deftest resource-index-for-misses-cleanly-with-no-namespace-anywhere
  ;; No explicit :namespace and no default-namespace (e.g. an unnamespaced
  ;; view-id): must miss (-1), not fabricate a "nil" string lookup key that
  ;; could accidentally collide with a real entry.
  (let [resource-index-for #'runtime/resource-index-for]
    (is (= -1 (resource-index-for {[nil "icon"] 5} nil {:path "icon"})))
    (is (= -1 (resource-index-for {} nil "icon")))))

(deftest item-label-no-longer-falls-back-to-skill-id
  ;; Regression: presentation-core's generic item-label coercion used to
  ;; read a :skill-id field -- domain (AC) knowledge with no business being
  ;; in a neutral rendering helper. A bare :skill-id-only item must not
  ;; resolve to a label anymore.
  (let [item-label #'runtime/item-label]
    (is (= "" (item-label {:skill-id :railgun})))
    (is (= "explicit" (item-label {:skill-id :railgun :label "explicit"})))))

(deftest scrollbar-press-returns-capture-pointer
  ;; Minecraft only delivers mouseDragged after mouseClicked returned true.
  ;; Presentation screen hosts treat :capture-pointer as consumed — without
  ;; this return, tutorial/settings scrollbar thumbs cannot be dragged.
  (let [rt (runtime/create-runtime)
        artifact (ta/build :academy/test/scrollbar
                           {:key :root :width [:fixed 40.0] :height [:fixed 100.0]
                            :children
                            [{:key :list :flags #{:has-clip :is-scroll :is-collection :has-direction}
                              :direction :column :width [:fixed 20.0] :height [:fixed 40.0]
                              :x 0.0 :y 0.0
                              :bind {:items [:state :items]}
                              :children [{:height [:fixed 20.0] :bind {:text [:item :label]}}]}
                             {:key :thumb :op UiOp/IMAGE
                              :flags #{:hit-testable :scrollbar}
                              :width [:fixed 10.0] :height [:fixed 20.0]
                              :x 25.0 :y 2.0
                              :scrollbar {:for :list :min-y 2.0 :max-y 20.0 :thumb? true}}]})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact
                                  :state {:items (mapv (fn [n] {:label (str n)}) (range 4))}})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 40 100 1.0))
    (is (= :capture-pointer
           (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 28 :y 10 :button 0})))
    (is (true? (get-in (runtime/instance! rt mount) [:pointer-capture :scrollbar?])))
    ;; :move while captured also advances the thumb (hosts that skip mouseDragged).
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 28 :y 18})
    (is (pos? (double (or (get (:scroll-offsets (runtime/instance! rt mount)) :list) 0.0))))
    ;; Wheel first, then press where the thumb visually sits after apply-scrollbar-thumbs!.
    (runtime/dispatch! rt mount {:type :pointer :event-type :up :x 28 :y 18 :button 0})
    (runtime/dispatch! rt mount {:type :scroll :x 10 :y 10 :delta -3.0})
    (let [offset-before (double (or (get (:scroll-offsets (runtime/instance! rt mount)) :list) 0.0))
          ;; max-off≈40, delta -3 → +36; thumb at min-y + 0.9*travel ≈ 18.2
          thumb-y 20.0]
      (is (pos? offset-before))
      (is (= :capture-pointer
             (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                          :x 28 :y thumb-y :button 0})))
      ;; Drag must change offset — a prior bug hardcoded :down for every
      ;; scrollbar :drag, resetting start-py each event so the thumb never moved.
      (runtime/dispatch! rt mount {:type :pointer :event-type :drag
                                   :x 28 :y (- thumb-y 10.0) :button 0
                                   :drag-x 0.0 :drag-y -10.0})
      (let [offset-after (double (or (get (:scroll-offsets (runtime/instance! rt mount)) :list) 0.0))]
        (is (not= offset-before offset-after)
            (str "thumb drag should change scroll offset, before=" offset-before
                 " after=" offset-after))
        ;; Capture must survive leaving the thin strip (main DragBar parity).
        (runtime/dispatch! rt mount {:type :pointer :event-type :drag
                                     :x 5 :y (- thumb-y 20.0) :button 0
                                     :drag-x -23.0 :drag-y -10.0})
        (let [off-strip (double (or (get (:scroll-offsets (runtime/instance! rt mount)) :list) 0.0))]
          (is (not= offset-after off-strip)
              (str "captured thumb drag must keep updating off-strip, mid=" offset-after
                   " after=" off-strip)))))))

(deftest custom-drag-capture-preserves-click-fallback
  (let [rt (runtime/create-runtime)
        actions (atom [])
        artifact (ta/build :academy/test/custom-drag
                           {:key :root :flags #{:hit-testable} :width [:fixed 80.0] :height [:fixed 20.0]
                            :on {:activate :demo/activate :drag-start :demo/drag-start}})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state { }
                                  :reduce (fn [state action payload]
                                            (swap! actions conj [action payload])
                                            {:state state :event-result :pass})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 80 20 1.0))
    (is (= :capture-pointer
           (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 5 :y 5 :button 0})))
    (is (= :demo/drag-start (first (first @actions))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 20 :y 5 :button 0})
    (is (= :input/pointer (first (last @actions))))
    (is (true? (:drag? (second (last @actions)))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :up :x 20 :y 5 :button 0})
    (is (= :input/pointer (first (last @actions))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 5 :y 5 :button 0})
    (runtime/dispatch! rt mount {:type :pointer :event-type :up :x 5 :y 5 :button 0})
    (is (= :demo/activate (first (last @actions))))))

(deftest custom-drag-drop-action-routes-to-hit-target
  (let [rt (runtime/create-runtime)
        actions (atom [])
        artifact (ta/build :academy/test/custom-drop
                           {:key :root :flags #{:hit-testable} :width [:fixed 80.0] :height [:fixed 20.0]
                            :on {:drag-start :demo/drag-start :drop :demo/drop}
                            :semantics {:role :generic :drop-zone :demo/canvas}})
        mount (runtime/mount! rt {:host {:stage :screen} :artifact artifact :state { }
                                  :reduce (fn [state action payload]
                                            (swap! actions conj [action payload])
                                            {:state state :event-result :pass})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 80 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 5 :y 5 :button 0})
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 20 :y 5 :button 0})
    (runtime/dispatch! rt mount {:type :pointer :event-type :up :x 20 :y 5 :button 0})
    (is (= :demo/drop (first (last @actions))))
    (is (true? (:drag? (second (last @actions)))))))
