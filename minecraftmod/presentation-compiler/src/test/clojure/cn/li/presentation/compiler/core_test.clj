(ns cn.li.presentation.compiler.core-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.compiler.core :as compiler]
            [cn.li.presentation.compiler.render :as render])
  (:import [cn.li.presentation.core TemplateId]))

(def symbols
  {:binding {:cp-ratio 0 :skills 1 :selected-skill 2}
   :action {:combat/select-skill 7}})

(deftest compiles-bindings-and-actions-to-numeric-ids
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:combat_hud")
                  "{:type :stack :key :root :children [{:type :progress :key :cp :value [:bind :cp-ratio]} {:type :skill-wheel :key :skills :items [:bind :skills] :on-select [:action :combat/select-skill]}]}"
                  symbols)]
    (is (= 0 (get (.bindings template) "cp-ratio")))
    (is (= 7 (get (.actions template) "combat/select-skill")))
    (is (= "stack" (.. template root type)))))

(deftest rejects-unknown-action
  (is (thrown? cn.li.presentation.compiler.TemplateCompileException
               (compiler/compile-edn
                (TemplateId. "academy:bad")
                "{:type :quad :key :root :on-select [:action :nope]}"
                symbols))))

(deftest render-interpreter-emits-core-command-records
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:quad")
                  "{:type :quad :key :root :value {:rgba -16711936}}"
                  {})
        commands (render/render-template template nil {:width 320 :height 180})]
    (is (= 1 (count commands)))
    (is (= "Quad" (.getSimpleName (class (first commands)))))))

(deftest button-node-compiles-and-renders-with-action-metadata
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:button")
                  "{:type :button :key :left :label \"<\" :on-click [:action :combat/select-skill] :semantics {:role :button}}"
                  symbols)
        commands (render/render-template template nil {:width 320 :height 180})]
    (is (= 7 (get (.actions template) "combat/select-skill")))
    (is (= 2 (count commands)))
    (is (= "Quad" (.getSimpleName (class (first commands)))))
    (is (= "GlyphRun" (.getSimpleName (class (second commands)))))))

(deftest textbox-node-is-a-dynamic-input-leaf
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:textbox")
                  "{:type :textbox :key :ssid :query [:bind :skills] :semantics {:role :textbox}}"
                  symbols)]
    (is (= "textbox" (.. template root type)))
    (is (empty? (.. template root children)))))

(deftest modal-is-not-painted-when-unbound
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:modal")
                  "{:type :modal :key :modal :semantics {:role :dialog}}"
                  {})]
    (is (empty? (render/render-template template nil {:width 320 :height 180})))))

(deftest virtual-list-is-a-dynamic-leaf
  (let [template (compiler/compile-edn
                  (TemplateId. "academy/list")
                  "{:type :virtual-list :key :items :items [:bind :skills] :semantics {:role :list}}"
                  symbols)]
    (is (= "virtual-list" (.. template root type)))
    (is (empty? (.. template root children)))))

(deftest rejects-invalid-layout-and-semantics-schema
  (is (thrown? cn.li.presentation.compiler.TemplateCompileException
               (compiler/compile-edn
                (TemplateId. "academy:bad-layout")
                "{:type :quad :key :root :width [:fraction 2.0]}"
                {})))
  (is (thrown? cn.li.presentation.compiler.TemplateCompileException
               (compiler/compile-edn
                (TemplateId. "academy:bad-semantics")
                "{:type :quad :key :root :semantics {:role :unknown}}"
                {}))))

(deftest flex-row-resolves-fraction-and-fill-widths
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:flex-row")
                  "{:type :flex :key :root
                    :children [{:type :quad :key :a :width [:fraction 0.25] :value {:rgba -1}}
                               {:type :quad :key :b :width :fill :value {:rgba -1}}]}"
                  {})
        [a b] (render/render-template template nil {:width 200 :height 100})]
    (is (= 50.0 (.width ^cn.li.mcmod.runtime.RenderCommand$Quad a)))
    (is (= 0.0 (.x ^cn.li.mcmod.runtime.RenderCommand$Quad a)))
    (is (= 150.0 (.width ^cn.li.mcmod.runtime.RenderCommand$Quad b)))
    (is (= 50.0 (.x ^cn.li.mcmod.runtime.RenderCommand$Quad b)))))

(deftest flex-column-direction-stacks-children-vertically
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:flex-column")
                  "{:type :flex :key :root :direction :column
                    :children [{:type :quad :key :a :height 30 :value {:rgba -1}}
                               {:type :quad :key :b :value {:rgba -1}}]}"
                  {})
        [a b] (render/render-template template nil {:width 100 :height 100})]
    (is (= 30.0 (.height ^cn.li.mcmod.runtime.RenderCommand$Quad a)))
    (is (= 0.0 (.y ^cn.li.mcmod.runtime.RenderCommand$Quad a)))
    (is (= 70.0 (.height ^cn.li.mcmod.runtime.RenderCommand$Quad b)))
    (is (= 30.0 (.y ^cn.li.mcmod.runtime.RenderCommand$Quad b)))))

(deftest grid-splits-children-into-even-cells
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:grid")
                  "{:type :grid :key :root
                    :children [{:type :quad :key :a :value {:rgba -1}}
                               {:type :quad :key :b :value {:rgba -1}}
                               {:type :quad :key :c :value {:rgba -1}}
                               {:type :quad :key :d :value {:rgba -1}}]}"
                  {})
        commands (render/render-template template nil {:width 100 :height 100})]
    (is (= 4 (count commands)))
    (is (every? #(= 50.0 (.width ^cn.li.mcmod.runtime.RenderCommand$Quad %)) commands))
    (is (every? #(= 50.0 (.height ^cn.li.mcmod.runtime.RenderCommand$Quad %)) commands))))

(deftest stack-children-each-fill-the-full-parent-rect
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:stack")
                  "{:type :stack :key :root
                    :children [{:type :quad :key :a :value {:rgba -1}}
                               {:type :quad :key :b :value {:rgba -1}}]}"
                  {})
        commands (render/render-template template nil {:width 100 :height 80})]
    (is (every? #(and (= 100.0 (.width ^cn.li.mcmod.runtime.RenderCommand$Quad %))
                       (= 80.0 (.height ^cn.li.mcmod.runtime.RenderCommand$Quad %)))
                commands))))

(deftest validates-all-selected-backend-capabilities
  (let [source {:type :quad :key :root
                :requires-capabilities [:post-process :uniform-buffers]}]
    (is (true? (compiler/validate-capabilities!
                source {:mc-1-20-1 #{:post-process :uniform-buffers}
                        :mc-1-21-1 #{:post-process :uniform-buffers}})))
    (is (thrown? cn.li.presentation.compiler.TemplateCompileException
                 (compiler/compile-template-for
                  (TemplateId. "academy:capability") source {}
                  {:mc-1-20-1 #{:post-process}
                   :mc-1-21-1 #{:post-process :uniform-buffers}})))))
