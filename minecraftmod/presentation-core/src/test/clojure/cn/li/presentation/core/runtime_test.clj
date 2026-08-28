(ns cn.li.presentation.core.runtime-test
  (:require [clojure.test :refer :all]
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
