(ns cn.li.presentation.compiler.artifact-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.compiler.artifact :as artifact])
  (:import [cn.li.mcmod.runtime.ui UiOp]))

(defn- compile* [root & {:keys [view-id] :or {view-id :academy/test/example}}]
  (artifact/compile-source
   {:ui/schema 2 :view/id view-id :root root}
   "example.ui.edn"))

(deftest compiles-a-flat-schema-5-artifact
  (let [compiled (compile* {:type :column
                            :children [{:type :text :bind {:text [:state :title]}}]})]
    (is (= :pui5 (:magic compiled)))
    (is (= 5 (:schema compiled)))
    (is (= 2 (:node-count compiled)))
    (is (= [-1 UiOp/TEXT] (:node/op compiled)))))

(deftest root-and-child-topology-is-pre-order
  (let [compiled (compile* {:type :column
                            :children [{:type :text} {:type :text}]})]
    (is (= 3 (:node-count compiled)))
    (is (= [-1 0 0] (:node/parent compiled)))
    (is (= [1 -1 -1] (:node/first-child compiled)))
    (is (= [-1 2 -1] (:node/next-sibling compiled)))
    (is (= [2 0 0] (:node/child-count compiled)))
    (is (= [UiOp/TEXT UiOp/TEXT] [(nth (:node/op compiled) 1) (nth (:node/op compiled) 2)]))))

(deftest bindings-are-deduplicated-and-ordered-by-first-use
  (let [compiled (compile* {:type :column
                            :children [{:type :text :bind {:text [:state :a]}}
                                       {:type :text :bind {:text [:state :b]}}
                                       {:type :text :bind {:text [:state :a]}}]})]
    (is (= [{:id 0 :path [:state :a]} {:id 1 :path [:state :b]}]
           (:bindings compiled)))
    (is (= 2 (:binding-count compiled)))))

(deftest item-scoped-bindings-do-not-get-a-global-binding-id
  (let [compiled (compile* {:type :repeater
                            :bind {:items [:state :rows]}
                            :children [{:type :text :bind {:text [:item :label]}}]})]
    ;; only :items [:state :rows] is state-scoped; [:item :label] is not.
    (is (= [{:id 0 :path [:state :rows]}] (:bindings compiled)))))

(deftest actions-are-deduplicated
  (let [compiled (compile* {:type :column
                            :children [{:type :button :on {:activate :demo/save}}
                                       {:type :button :on {:activate :demo/save}}
                                       {:type :button :on {:activate :demo/cancel}}]})]
    (is (= [{:id 0 :name :demo/save} {:id 1 :name :demo/cancel}]
           (:actions compiled)))))

(deftest alpha-is-a-compile-time-alias-for-rgba
  (let [compiled (compile* {:type :rect :bind {:alpha [:state :mask]}})]
    (is (= [{:id 0 :path [:state :mask]}] (:bindings compiled)))
    (is (= {:rgba [:state :mask]} (nth (:node/bind-map compiled) 0)))))

(deftest button-lowers-to-a-hit-testable-wrapper-with-rect-and-text-children
  (let [compiled (compile* {:type :button :bind {:text [:state :label]} :on {:activate :demo/go}})]
    (is (= 3 (:node-count compiled)))
    (is (= -1 (nth (:node/op compiled) 0))) ; wrapper: not directly drawable
    (is (pos? (bit-and (nth (:node/flags compiled) 0) 8))) ; HIT_TESTABLE = 8
    (is (= UiOp/RECT (nth (:node/op compiled) 1)))
    (is (= UiOp/TEXT (nth (:node/op compiled) 2)))
    (is (= {:text [:state :label]} (nth (:node/bind-map compiled) 2)))
    (is (= {:activate 0} (nth (:node/action-ids compiled) 0)))))

(deftest text-input-wrapper-is-hit-testable-and-focusable
  (let [compiled (compile* {:type :text-input :bind {:text [:state :query]}})
        wrapper-flags (nth (:node/flags compiled) 0)]
    (is (= (bit-or 8 64) (bit-and wrapper-flags (bit-or 8 64)))))) ; HIT_TESTABLE | FOCUSABLE

(deftest scroll-defaults-to-column-direction-with-clip-and-collection-flags
  (let [compiled (compile* {:type :scroll :bind {:items [:state :lines]}
                            :children [{:type :text}]})
        flags (nth (:node/flags compiled) 0)]
    (is (= 2 (nth (:node/direction compiled) 0))) ; Direction.COLUMN
    (is (= (bit-or 1 2 4 1024) flags)))) ; HAS_CLIP|IS_SCROLL|IS_COLLECTION|HAS_DIRECTION

(deftest grid-defaults-to-row-direction
  (let [compiled (compile* {:type :grid :bind {:items [:state :tags]}
                            :children [{:type :text}]})]
    (is (= 1 (nth (:node/direction compiled) 0))))) ; Direction.ROW

(deftest composite-node-compiles-as-its-own-opcode
  (let [compiled (compile* {:type :repeater :bind {:items [:state :list]}
                            :children [{:type :composite :bind {:item [:state :item]}}]})]
    (is (= UiOp/COMPOSITE (nth (:node/op compiled) 1)))))

(deftest glow-line-lowers-to-a-nine-slice-approximation
  (let [compiled (compile* {:type :glow-line :bind {:x0 [:state :gx0]}})]
    (is (= UiOp/NINE (nth (:node/op compiled) 0)))))

(deftest dep-mask-includes-descendant-bindings
  (let [compiled (compile* {:type :column
                            :children [{:type :row
                                       :children [{:type :text :bind {:text [:state :deep]}}]}]})
        words (:mask-words compiled)
        mask (:node/dep-mask compiled)
        bit0 (fn [node] (bit-and 1 (nth mask (* node words))))]
    ;; root (0) and row (1) both transitively depend on binding 0; the leaf (2) depends on it directly.
    (is (= 1 (bit0 0)))
    (is (= 1 (bit0 1)))
    (is (= 1 (bit0 2)))))

(deftest sibling-subtrees-do-not-leak-bindings-into-each-other
  (let [compiled (compile* {:type :row
                            :children [{:type :text :bind {:text [:state :left]}}
                                       {:type :text :bind {:text [:state :right]}}]})
        words (:mask-words compiled)
        mask (:node/dep-mask compiled)
        bit (fn [node id] (bit-and 1 (bit-shift-right (nth mask (* node words)) id)))]
    (is (= 1 (bit 1 0))) (is (= 0 (bit 1 1))) ; left child only sees binding 0
    (is (= 0 (bit 2 0))) (is (= 1 (bit 2 1))))) ; right child only sees binding 1

(deftest rejects-unsupported-primitive
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported v3 primitive"
                        (compile* {:type :legacy-template}))))

(deftest rejects-invalid-direction
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported value"
                        (compile* {:type :box :layout {:direction :diagonal}}))))

(deftest rejects-missing-source-schema
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"required source schema 2"
                        (artifact/compile-source
                         {:view/id :academy/test/missing-schema :root {:type :rect}}
                         "missing-schema.ui.edn"))))

(deftest rejects-v1-source-schema-as-unsupported
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported source schema 1"
                        (artifact/compile-source
                         {:ui/schema 1 :view/id :academy/test/old :root {:type :rect}}
                         "old.ui.edn"))))

(deftest canonicalization-is-deterministic
  (let [a (compile* {:type :rect :style {:font-size 8 :rgba [1.0 1.0 1.0 1.0]}})
        b (compile* {:type :rect :style {:rgba [1.0 1.0 1.0 1.0] :font-size 8}})]
    (is (= (:source-hash a) (:source-hash b)))
    (is (= a b))))

(deftest sizes-parse-fixed-percent-weight-and-fill
  (let [compiled (compile* {:type :row
                            :children [{:type :rect :layout {:width 20}}
                                       {:type :rect :layout {:width [:pct 0.5]}}
                                       {:type :rect :layout {:width [:weight 2]}}
                                       {:type :rect :layout {:width :fill}}]})]
    (is (= [1 2 3 4] (rest (:node/width-mode compiled))))
    (is (= [20.0 0.5 2.0 1.0] (rest (:node/width-value compiled))))))

(deftest margin-and-padding-parse-into-the-box-array
  (let [compiled (compile* {:type :rect :layout {:margin 2 :padding {:l 1 :t 2 :r 3 :b 4}}})
        box (:node/box compiled)]
    ;; margin l,t,r,b | padding l,t,r,b | min-w,min-h,max-w,max-h -- 12 floats, node 0.
    (is (= [2.0 2.0 2.0 2.0 1.0 2.0 3.0 4.0 0.0 0.0 0.0 0.0] (vec (take 12 box))))))
