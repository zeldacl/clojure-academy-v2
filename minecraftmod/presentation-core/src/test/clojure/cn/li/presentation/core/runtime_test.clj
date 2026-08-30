(ns cn.li.presentation.core.runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.paint :as paint])
  (:import [cn.li.presentation.core HostGeometry]))

(def sample-artifact
  {:magic :pui3 :schema 3 :view-id :academy/test/runtime
   :semantics {:role :generic :label "test"}})

(deftest runtime-commits-state-before-effects
  (let [effects (atom [])
        rt (runtime/create-runtime)
        mount (runtime/mount!
                rt {:host {:stage :screen}
                    :view-id :academy/test/runtime
                    :artifact sample-artifact
                    :state {:value 0}
                    :reduce (fn [state action payload]
                              (if (= action :increment)
                                {:state (update state :value + payload)
                                 :effects [{:type :record :value (+ (:value state) payload)}]
                                 :event-result :consume}
                                {:state state :effects [] :event-result :pass}))
                    :run-effect! #(swap! effects conj %)} )]
    (is (= :consume (runtime/dispatch! rt mount {:action :increment :payload 2})))
    (is (= [{:type :record :value 2}] @effects))
    (is (= {:role :generic :label "test"} (runtime/semantics rt mount)))
    (runtime/update-host! rt mount (HostGeometry. 10.0 20.0 320 240 1.0))
    (is (= mount (-> (runtime/extract-stage! rt :screen {:time-nanos 1}) :mounts first :handle)))))

(deftest runtime-requires-owner-thread
  (let [foreign-thread (Thread.)
        rt (runtime/create-runtime {:owner-thread foreign-thread})]
    (is (thrown? IllegalStateException (runtime/unmount-all! rt)))))
(deftest runtime-extracts-new-ui-render-ir
  (let [rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/paint
                  :nodes {:id :root :type :rect :layout {}
                          :style {:rgba (unchecked-int 0xFF00FF00)}}}
        mount (runtime/mount!
                rt {:host {:stage :screen}
                    :view-id :academy/test/paint
                    :artifact artifact
                    :state {}
                    :paint-fn paint/paint-view})
        packet (runtime/extract-stage! rt :screen {:time-nanos 2})
        command (-> packet :mounts first :commands first)]
    (is (= 1 (count (:mounts packet))))
    (is (instance? cn.li.mcmod.runtime.RenderCommand$UiQuadBatch command))
    (is (= 1 (count (.quads ^cn.li.mcmod.runtime.RenderCommand$UiQuadBatch command))))
    (runtime/unmount! rt mount)))
(deftest painter-uses-button-binding-label
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/button
                  :nodes {:id :root :type :button :layout {:width 80 :height 20}
                          :bind {:text [:state :button]}
                          :semantics {:role :button}}}
        commands (paint/paint-view artifact {:button {:label "Save"}}
                                    {:viewport-width 100 :viewport-height 40})
        label-command (second commands)]
    (is (instance? cn.li.mcmod.runtime.RenderCommand$UiText label-command))
    (is (= "Save" (.text ^cn.li.mcmod.runtime.RenderCommand$UiText label-command)))))
(deftest painter-expands-collection-and-input-nodes
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/collection
                  :nodes {:id :root :type :column :layout {}
                          :children [{:type :scroll :bind {:items [:state :lines]}}
                                     {:type :text-input :bind {:text [:state :query]}}]}}
        commands (paint/paint-view artifact {:lines [{:label "One"} {:label "Two"}]
                                               :query "search"}
                                    {:viewport-width 120 :viewport-height 80})
        texts (->> commands
                   (filter #(instance? cn.li.mcmod.runtime.RenderCommand$UiText %))
                   (map #(.text ^cn.li.mcmod.runtime.RenderCommand$UiText %))
                   vec)]
    (is (= ["One" "Two" "search"] texts))))

(deftest painter-accepts-boolean-progress-values
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/progress
                  :nodes {:id :root :type :progress :layout {:width 20 :height 4}
                          :bind {:value [:state :loading?]}}}
        commands (paint/paint-view artifact {:loading? true}
                                    {:viewport-width 20 :viewport-height 4})]
    (is (= 1 (count commands)))
    (is (instance? cn.li.mcmod.runtime.RenderCommand$UiQuadBatch (first commands)))))
(deftest runtime-routes-pointer-to-button-action
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/input
                  :nodes {:id :root :type :row :layout {}
                          :children [{:type :button :key :left :layout {:height 20}
                                      :on {:activate :demo/left}}
                                     {:type :button :key :right :layout {:height 20}
                                      :on {:activate :demo/right}}]}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                                  :x 75 :y 10 :button 0})))
    (is (= [:demo/right {:target :right :button-id 1}] @seen))))
(deftest runtime-routes-repeater-button-with-item-context
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/repeater-input
                  :nodes {:id :root :type :scroll :layout {:width 100 :height 40}
                          :bind {:items [:state :items]}
                          :children [{:type :button :key :row/action
                                      :layout {:height 20}
                                      :bind {:text [:item :label]}
                                      :on {:activate :demo/item}}]}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {:items [{:label "One"} {:label "Two"}]}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 40 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                                  :x 10 :y 30 :button 0})))
    (is (= [:demo/item {:target :row/action
                        :item {:label "Two"}
                        :index 1}] @seen))))
(deftest runtime-routes-text-input
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/text-input
                  :nodes {:id :root :type :text-input :layout {:width 100 :height 20}
                          :bind {:text [:state :query]}
                          :on {:change :edit/change :submit :edit/submit}
                          :semantics {:role :textbox :field :query}}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {:query ""}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action state payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})
    (runtime/dispatch! rt mount {:type :character :text "a"})
    (is (= :edit/change (first (last @seen))))
    (is (= "a" (get-in (second (last @seen)) [:query])))
    (runtime/dispatch! rt mount {:type :key :key-code 259 :pressed? true})
    (is (= "" (get-in (second (last @seen)) [:query])))))
(deftest runtime-reuses-paint-for-clean-frame
  (let [paints (atom 0)
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/cache
                  :nodes {:type :rect :layout {}}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {:value 1}
                                  :paint-fn (fn [_ _ _]
                                               (swap! paints inc)
                                               [:paint])})]
    (runtime/extract-stage! rt :screen {})
    (runtime/extract-stage! rt :screen {})
    (is (= 1 @paints))
    (runtime/present! rt mount {:value 2})
    (runtime/extract-stage! rt :screen {})
    (is (= 2 @paints))))
(deftest runtime-routes-hover-enter-and-leave
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/hover
                  :nodes {:type :row :layout {:width 100 :height 20}
                          :children [{:type :button :key :tag
                                      :layout {:width 50 :height 20}
                                      :on {:hover :demo/hover}}]}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 10 :y 10})
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 90 :y 10})
    (is (= :demo/hover (ffirst @seen)))
    (is (= :enter (get-in @seen [0 1 :hover-event])))
    (is (= true (get-in @seen [0 1 :hover?])))
    (is (= :leave (get-in @seen [1 1 :hover-event])))
    (is (= false (get-in @seen [1 1 :hover?])))))
(deftest runtime-routes-scroll-and-paints-clipped-offsets
  (let [seen (atom nil)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/scroll
                  :nodes {:type :scroll :key :list :layout {:width 100 :height 20}
                          :bind {:items [:state :items]}
                          :children [{:type :text :layout {:height 10}
                                      :bind {:text [:item :label]}}]}}
        state {:items (mapv (fn [n] {:label (str n)}) (range 4))}
        commands (paint/paint-view artifact
                                    (assoc state :presentation/scroll-offsets {:list 10.0})
                                    {:viewport-width 100 :viewport-height 20}
                                    )
        rt (runtime/create-runtime)
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state state
                                  :reduce (fn [s action payload]
                                            (reset! seen [action payload])
                                            {:state s :event-result :consume})})]
    (is (instance? cn.li.mcmod.runtime.RenderCommand$PushClip (first commands)))
    (is (= -10.0 (double (.y ^cn.li.mcmod.runtime.RenderCommand$UiText
                              (second commands)))))
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 20 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :scroll :x 5 :y 10 :delta -1})))
    (is (= :input/scroll (first @seen)))
    (is (= :list (get-in @seen [1 :target])))
    (is (= 12.0 (double (get-in @seen [1 :scroll-offset]))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :drag :x 5 :y 10 :drag-y -5.0})
    (is (= true (get-in @seen [1 :drag?])))
    (is (= 17.0 (double (get-in @seen [1 :scroll-offset]))))))
(deftest runtime-applies-frame-host-geometry
  (let [rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/fill
                  :nodes {:type :rect :layout {:width :fill :height :fill}}}
        mount (runtime/mount! rt {:host {:stage :hud}
                                  :artifact artifact
                                  :paint-fn (fn [_ _ geometry] [geometry])})
        packet (runtime/extract-stage! rt :hud {:width 320 :height 180})
        geometry (-> packet :mounts first :commands first)]
    (is (= 320 (.viewportWidth ^HostGeometry geometry)))
    (is (= 180 (.viewportHeight ^HostGeometry geometry)))
    (runtime/unmount! rt mount)))

(deftest runtime-routes-progress-input
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/progress-input
                  :nodes {:type :progress :key :seek :layout {:width 100 :height 10}
                          :on {:change :media/seek}}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 10 1.0))
    (is (= :consume (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                                  :x 25 :y 5 :button 0})))
    (is (= :media/seek (first @seen)))
    (is (= 0.25 (double (get-in @seen [1 :value]))))
    (runtime/dispatch! rt mount {:type :pointer :event-type :drag
                                  :x 75 :y 5 :drag-x 50})
    (is (= 0.75 (double (get-in @seen [1 :value]))))))

(deftest runtime-ignores-hidden-and-clipped-interaction-nodes
  (let [seen (atom [])
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/visibility
                  :nodes {:type :column :layout {:width 100 :height 60}
                          :children [{:type :button :key :hidden :layout {:height 20}
                                      :bind {:visible [:state :hidden?]}
                                      :on {:activate :demo/hidden :hover :demo/hidden-hover}}
                                     {:type :scroll :key :list :layout {:height 20}
                                      :bind {:items [:state :items]}
                                      :children [{:type :button :layout {:height 10}
                                                  :on {:activate :demo/item :hover :demo/item-hover}}]}]}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {:hidden? false :items [{:id 1} {:id 2} {:id 3}]}
                                  :reduce (fn [state action payload]
                                            (swap! seen conj [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 60 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :move :x 10 :y 10})
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 10 :button 0})
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 10 :y 55 :button 0})
    (is (not-any? #(#{:demo/hidden :demo/hidden-hover :demo/item :demo/item-hover} (first %)) @seen))))
(deftest runtime-treats-blank-visibility-as-hidden
  (let [rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/blank-visibility
                  :nodes {:type :button :key :hint :layout {:width 80 :height 20}
                          :bind {:visible [:state :hint] :text [:state :hint]}
                          :on {:activate :demo/hint}}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {:hint ""}
                                  :reduce (fn [state _action _payload]
                                            {:state state :event-result :pass})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 100 40 1.0))
    (is (empty? (:commands (first (:mounts (runtime/extract-stage! rt :screen {:width 100 :height 40}))))))
    (is (= :pass (runtime/dispatch! rt mount {:type :pointer :event-type :down
                                               :x 10 :y 10 :button 0})))))


(deftest painter-packs-row-children-by-declared-width
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/pack-row
                  :host {:kind :screen :design-width 100 :design-height 20 :scale-policy :fit}
                  :nodes {:type :row :layout {:width 100 :height 20}
                          :children [{:type :rect :key :a :layout {:width 20 :height 20}
                                      :style {:rgba (unchecked-int 0xFF0000FF)}}
                                     {:type :rect :key :b :layout {:width 50 :height 20}
                                      :style {:rgba (unchecked-int 0xFF00FF00)}}
                                     {:type :rect :key :c :layout {:width 30 :height 20}
                                      :style {:rgba (unchecked-int 0xFFFF0000)}}]}}
        commands (paint/paint-view artifact {} {:viewport-width 100 :viewport-height 20})
        quads (->> commands
                   (filter #(instance? cn.li.mcmod.runtime.RenderCommand$UiQuadBatch %))
                   (mapcat #(.quads ^cn.li.mcmod.runtime.RenderCommand$UiQuadBatch %))
                   vec)]
    (is (= 3 (count quads)))
    (is (= 0.0 (float (.x ^cn.li.mcmod.runtime.RenderCommand$UiQuad (nth quads 0)))))
    (is (= 20.0 (float (.width ^cn.li.mcmod.runtime.RenderCommand$UiQuad (nth quads 0)))))
    (is (= 20.0 (float (.x ^cn.li.mcmod.runtime.RenderCommand$UiQuad (nth quads 1)))))
    (is (= 50.0 (float (.width ^cn.li.mcmod.runtime.RenderCommand$UiQuad (nth quads 1)))))
    (is (= 70.0 (float (.x ^cn.li.mcmod.runtime.RenderCommand$UiQuad (nth quads 2)))))
    (is (= 30.0 (float (.width ^cn.li.mcmod.runtime.RenderCommand$UiQuad (nth quads 2)))))))

(deftest painter-centers-design-box-with-fit-policy
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/fit
                  :host {:kind :screen :design-width 40 :design-height 20 :scale-policy :fit}
                  :nodes {:type :rect :layout {:width 40 :height 20}
                          :style {:rgba (unchecked-int 0xFFFFFFFF)}}}
        commands (paint/paint-view artifact {} {:viewport-width 100 :viewport-height 60})
        quad (-> ^cn.li.mcmod.runtime.RenderCommand$UiQuadBatch (first commands)
                 (.quads) first)]
    (is (= 30.0 (float (.x ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))
    (is (= 20.0 (float (.y ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))
    (is (= 40.0 (float (.width ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))
    (is (= 20.0 (float (.height ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))))

(deftest painter-applies-absolute-child-offsets
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/absolute
                  :nodes {:type :absolute :layout {:width 100 :height 80}
                          :children [{:type :rect :layout {:x 10 :y 15 :width 20 :height 12}
                                      :style {:rgba (unchecked-int 0xFFFFFFFF)}}]}}
        commands (paint/paint-view artifact {} {:viewport-width 100 :viewport-height 80})
        quad (-> ^cn.li.mcmod.runtime.RenderCommand$UiQuadBatch (first commands)
                 (.quads) first)]
    (is (= 10.0 (float (.x ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))
    (is (= 15.0 (float (.y ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))
    (is (= 20.0 (float (.width ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))
    (is (= 12.0 (float (.height ^cn.li.mcmod.runtime.RenderCommand$UiQuad quad))))))

(deftest runtime-hits-packed-row-and-centered-design
  (let [seen (atom nil)
        rt (runtime/create-runtime)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/hit-fit
                  :host {:kind :screen :design-width 100 :design-height 20 :scale-policy :fit}
                  :nodes {:type :row :layout {:width 100 :height 20}
                          :children [{:type :button :key :left :layout {:width 40 :height 20}
                                      :on {:activate :demo/left}}
                                     {:type :button :key :right :layout {:width 60 :height 20}
                                      :on {:activate :demo/right}}]}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state {}
                                  :reduce (fn [state action payload]
                                            (reset! seen [action payload])
                                            {:state state :event-result :consume})})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 200 40 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 60 :y 20 :button 0})
    (is (= :demo/left (first @seen)))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 120 :y 20 :button 0})
    (is (= :demo/right (first @seen)))))

(deftest runtime-scrollbar-tracks-content-offset
  (let [seen (atom nil)
        artifact {:magic :pui3 :schema 3 :view-id :academy/test/scrollbar
                  :nodes {:type :absolute :layout {:width 120 :height 40}
                          :children [{:type :scroll :key :content
                                      :layout {:x 0 :y 0 :width 100 :height 20}
                                      :bind {:items [:state :items]}
                                      :children [{:type :text :layout {:height 10}
                                                  :bind {:text [:item :label]}}]}
                                     {:type :image :key :track
                                      :layout {:x 110 :y 0 :width 10 :height 40}
                                      :style {:resource {:namespace "academy" :path "t.png"}
                                              :scrollbar {:for :content :min-y 0.0 :max-y 20.0}}}
                                     {:type :image :key :thumb
                                      :layout {:x 110 :y 0 :width 10 :height 10}
                                      :style {:resource {:namespace "academy" :path "t.png"}
                                              :scrollbar {:for :content :min-y 0.0 :max-y 20.0 :thumb? true}}}]}}
        state {:items (mapv (fn [n] {:label (str n)}) (range 4))}
        rt (runtime/create-runtime)
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact
                                  :state state
                                  :reduce (fn [s action payload]
                                            (reset! seen [action payload])
                                            {:state s :event-result :consume})})
        painted (paint/paint-view artifact
                                   (assoc state :presentation/scroll-offsets {:content 10.0})
                                   {:viewport-width 120 :viewport-height 40})
        thumb (->> painted
                   (filter #(instance? cn.li.mcmod.runtime.RenderCommand$UiImageBatch %))
                   last)
        imgs (.images ^cn.li.mcmod.runtime.RenderCommand$UiImageBatch thumb)]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 120 40 1.0))
    (runtime/dispatch! rt mount {:type :pointer :event-type :down :x 115 :y 20 :button 0})
    (is (= :input/scroll (first @seen)))
    (is (= :content (get-in @seen [1 :target])))
    (is (= 20.0 (double (get-in @seen [1 :scroll-offset]))))
    (is (= 1 (count imgs)))
    (is (= 10.0 (float (.y ^cn.li.mcmod.runtime.RenderCommand$UiImage (first imgs)))))))

(deftest paint-glow-line-emits-textured-segments
  (let [artifact {:magic :pui3 :schema 3 :view-id :academy/test/glow
                  :nodes {:type :glow-line :layout {:x 100 :y 50 :width 0 :height 0}
                          :bind {:x0 [:state :x0] :x1 [:state :x1]
                                 :line-y [:state :line-y]
                                 :line-w [:state :line-w]
                                 :glow-sz [:state :glow-sz]}}}
        commands (paint/paint-view artifact
                                   {:x0 10.0 :x1 40.0 :line-y 2.0 :line-w 2.0 :glow-sz 2.0}
                                   {:viewport-width 200 :viewport-height 100})
        batches (filter #(instance? cn.li.mcmod.runtime.RenderCommand$UiImageBatch %) commands)
        paths (mapv (fn [^cn.li.mcmod.runtime.RenderCommand$UiImageBatch b]
                      (.path (.resource b)))
                    batches)]
    ;; 8 glow segments + center line
    (is (= 9 (count batches)))
    (is (some #(str/includes? % "glow_lu") paths))
    (is (some #(str/includes? % "line.png") paths))))

