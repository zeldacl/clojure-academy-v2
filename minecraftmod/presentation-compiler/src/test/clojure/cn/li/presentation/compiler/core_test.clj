(ns cn.li.presentation.compiler.core-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.compiler.core :as compiler]
            [cn.li.presentation.compiler.artifact :as artifact]
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

(deftest compiled-template-artifact-round-trips-as-data
  (let [template (compiler/compile-edn
                  (TemplateId. "academy:artifact")
                  "{:type :button :key :root :label \"OK\" :on-click [:action :combat/select-skill] :semantics {:role :button}}"
                  symbols)
        bytes (artifact/encode template {:targets [:mc-1-20-1 :mc-1-21-1 :mc-26-2]
                                         :dependencies ["academy:textures/ui.png"]})
        decoded (artifact/decode bytes)
        roundtrip (:template decoded)]
    (is (= (.contentHash template) (.contentHash roundtrip)))
    (is (= (into {} (.actions template)) (into {} (.actions roundtrip))))
    (is (= ["academy:textures/ui.png"] (get-in decoded [:metadata :dependencies])))
    (is (thrown? Exception (artifact/decode (.getBytes "bad" "UTF-8"))))))

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
