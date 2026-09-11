(ns cn.li.node.graph-document-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.graph-document :as doc]
            [cn.li.node.graph-compile :as graph-compile]))

(defn- n [id type & kvs]
  (into {:nid id :type type} (apply hash-map kvs)))

(defn- e [id kind from to]
  {:id id :kind kind :from from :to to})

(def simple-graph
  {:nodes {:n/start (n :n/start :start)
           :n/end (n :n/end :end)}
   :links [(e :e/start :exec [:n/start :out] [:n/end :in])]})

(def skill
  {:schema :ac/skill-v4
   :id :test/skill
   :skill {:category :test :level 1}
   :activation {:mode :instant}
   :parameters {}
   :state {}
   :graphs {:default {:on :activation/start
                      :nodes (:nodes simple-graph)
                      :links (:links simple-graph)}}})

(deftest accepts-minimal-v4-skill
  (is (= skill (doc/validate-document! skill)))
  (is (= :skill (doc/kind skill)))
  (is (= (dissoc skill :editor) (doc/semantic-document skill))))

(deftest rejects-top-level-legacy-wrapper-fields
  (doseq [field [:program :scene]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (doc/validate-document! (assoc skill field {:legacy true}))))))

(deftest rejects-ordinary-cycle
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/a (n :n/a :local-set :key :x :operation :define)
                       :n/end (n :n/end :end)}
               :links [(e :e/one :exec [:n/start :out] [:n/a :in])
                       (e :e/two :exec [:n/a :out] [:n/a :in])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo _
            true)))))

(deftest accepts-explicit-loop-back
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/loop (n :n/loop :repeat :count 2)
                       :n/body (n :n/body :local-set :key :x :operation :define)
                       :n/end (n :n/end :end)
                       :n/loop-end (n :n/loop-end :loop-end)}
               :links [(e :e/one :exec [:n/start :out] [:n/loop :in])
                       (e :e/two :exec [:n/loop :body] [:n/body :in])
                       (e :e/three :exec [:n/loop :completed] [:n/end :in])
                       (e :e/four :exec [:n/body :out] [:n/loop-end :in])
                       (e :e/five :exec [:n/loop-end :continue] [:n/loop :loop-back])] }]
    (is (= graph (doc/validate-graph! graph [:graphs :default])))))

(deftest rejects-branch-without-both-outputs
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/branch (n :n/branch :branch)
                       :n/end (n :n/end :end)}
               :links [(e :e/one :exec [:n/start :out] [:n/branch :in])
                       (e :e/two :exec [:n/branch :true] [:n/end :in])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo e
            (re-find #"true and false" (.getMessage e)))))))

(deftest rejects-invalid-fixed-node-ports
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/branch (n :n/branch :branch)
                       :n/lit (n :n/lit :literal :value true)
                       :n/end (n :n/end :end)
                       :n/end2 (n :n/end2 :end)}
               :links [(e :e/one-link :exec [:n/start :out] [:n/branch :in])
                       (e :e/true-link :exec [:n/branch :out] [:n/end :in])
                       (e :e/false-link :exec [:n/branch :false] [:n/end2 :in])
                       (e :e/cond-link :data [:n/lit :value] [:n/branch :condition])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo e
            (re-find #"invalid source port" (.getMessage e)))))))

(deftest rejects-data-edges-on-control-only-nodes
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/end (n :n/end :end)
                       :n/lit (n :n/lit :literal :value true)}
               :links [(e :e/start-link :exec [:n/start :out] [:n/end :in])
                       (e :e/data-link :data [:n/lit :value] [:n/end :in])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo e
            (re-find #"invalid target port" (.getMessage e)))))))

(deftest rejects-implicit-multiway-convergence
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/branch (n :n/branch :branch)
                       :n/join (n :n/join :component :component :test/do)
                       :n/end (n :n/end :end)}
               :links [(e :e/one :exec [:n/start :out] [:n/branch :in])
                       (e :e/t :exec [:n/branch :true] [:n/join :in])
                       (e :e/f :exec [:n/branch :false] [:n/join :in])
                       (e :e/out :exec [:n/join :out] [:n/end :in])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo e
            (re-find #"V4" (.getMessage e)))))))

(deftest compiles-minimal-v4-graph
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/action (n :n/action :component :component :test/do)
                                                  :n/end (n :n/end :end :result {:outcome :done})}
                                          :links [(e :e/link-a :exec [:n/start :out] [:n/action :in])
                                                  (e :e/link-b :exec [:n/action :out] [:n/end :in])]}})
        {:keys [ir diagnostics]} (graph-compile/compile-skill! d {:vocab {:test/do {:params {}}}} :collect)]
    (is (empty? diagnostics))
    (is (map? ir))))

(deftest preserves-inline-component-inputs
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/action (n :n/action :component :component :test/do
                                                                  :inputs {:amount 3})
                                                  :n/end (n :n/end :end)}
                                          :links [(e :e/aaa :exec [:n/start :out] [:n/action :in])
                                                  (e :e/bbb :exec [:n/action :out] [:n/end :in])]}})
        {:keys [diagnostics]} (graph-compile/compile-skill! d {:vocab {:test/do {:params {:amount {:type :long}}}}} :collect)]
    (is (empty? diagnostics))))

(deftest compiles-component-inputs-addressed-only-by-data-links
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/value (n :n/value :literal :value 3)
                                                  :n/action (n :n/action :component :component :test/do)
                                                  :n/end (n :n/end :end)}
                                          :links [(e :e/exec-a :exec [:n/start :out] [:n/action :in])
                                                  (e :e/data-a :data [:n/value :value] [:n/action :amount])
                                                  (e :e/exec-b :exec [:n/action :out] [:n/end :in])]}})
        {:keys [diagnostics]} (graph-compile/compile-skill! d {:vocab {:test/do {:params {:amount {:type :long}}}}} :collect)]
    (is (empty? diagnostics))))

(deftest compiles-loop-end-as-foreach-body-terminator
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/items (n :n/items :literal :value [1 2])
                                                  :n/each (n :n/each :foreach :limit 8 :as :item)
                                                  :n/action (n :n/action :component :component :test/do)
                                                  :n/loop (n :n/loop :loop-end)
                                                  :n/end (n :n/end :end)}
                                          :links [(e :e/start :exec [:n/start :out] [:n/each :in])
                                                  (e :e/items :data [:n/items :value] [:n/each :collection])
                                                  (e :e/body :exec [:n/each :body] [:n/action :in])
                                                  (e :e/action :exec [:n/action :out] [:n/loop :in])
                                                  (e :e/continue :exec [:n/loop :continue] [:n/each :loop-back])
                                                  (e :e/completed :exec [:n/each :completed] [:n/end :in])]}})
        {:keys [diagnostics]} (graph-compile/compile-skill! d {:vocab {:test/do {:params {}}}} :collect)]
    (is (empty? diagnostics))))

(deftest accepts-branch-with-one-terminating-arm
  "One arm may finish at :end while the other continues; that is not a hidden
   fan-in and must not be rejected (see graph-compile/collect join rules)."
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/branch (n :n/branch :branch)
                                                  :n/end (n :n/end :end)
                                                  :n/action (n :n/action :component :component :test/do)
                                                  :n/end2 (n :n/end2 :end)}
                                          :links [(e :e/start-arm :exec [:n/start :out] [:n/branch :in])
                                                  (e :e/true-arm :exec [:n/branch :true] [:n/end :in])
                                                  (e :e/false-arm :exec [:n/branch :false] [:n/action :in])
                                                  (e :e/action-end :exec [:n/action :out] [:n/end2 :in])]}})
        opts {:vocab {:test/do {:params {}}}}
        {:keys [diagnostics]} (graph-compile/compile-skill! d opts :collect)]
    (is (empty? diagnostics))))

(deftest known-op-links-use-arg-ports-not-type-keys
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/n-x (n :n/n-x :parameter-ref :key :x)
                       :n/n-y (n :n/n-y :parameter-ref :key :y)
                       :n/n-add (n :n/n-add :component :component :math/add)
                       :n/end (n :n/end :end)}
               :links [(e :e/start :exec [:n/start :out] [:n/n-add :in])
                       (e :e/n-x :data [:n/n-x :value] [:n/n-add :arg0])
                       (e :e/n-y :data [:n/n-y :value] [:n/n-add :arg1])
                       (e :e/out :exec [:n/n-add :out] [:n/end :in])]}
        d (assoc skill
                 :parameters {:x {:type :double :default 0.0}
                              :y {:type :double :default 0.0}}
                 :graphs {:default (assoc graph :on :activation/start)})
        stmts (get-in (graph-compile/skill->core d) [:entries :default])]
    (is (some #(and (list? %)
                    (= 'math/add (first %))
                    (= '$x (nth % 1))
                    (= '$y (nth % 2)))
              (tree-seq coll? identity stmts))
        "pure op inputs wired at :arg0/:arg1 must not compile as nil")))

(deftest defn-composite-accepts-argn-ports
  (testing "lib :defn composites accept V4 :argN wiring (railgun beam-strike)"
    (let [graph {:nodes {:n/start (n :n/start :start)
                         :n/n-o (n :n/n-o :parameter-ref :key :origin)
                         :n/n-len (n :n/n-len :parameter-ref :key :length)
                         :n/n-strike (n :n/n-strike :component :component :test/strike
                                       :inputs {:arg2 1.0})
                         :n/end (n :n/end :end)}
                 :links [(e :e/start :exec [:n/start :out] [:n/n-strike :in])
                         (e :e/origin :data [:n/n-o :value] [:n/n-strike :arg0])
                         (e :e/length :data [:n/n-len :value] [:n/n-strike :arg1])
                         (e :e/out :exec [:n/n-strike :out] [:n/end :in])]}
          d (assoc skill
                   :parameters {:origin {:type :vec3 :default nil}
                               :length {:type :double :default 0.0}}
                   :graphs {:default (assoc graph :on :activation/start)})
          opts {:fns {:test/strike {:params [{:name 'origin :type :vec3}
                                             {:name 'length :type :double}
                                             {:name 'step :type :double}]}}}
          stmts (get-in (graph-compile/skill->core d opts) [:entries :default])]
      (is (some #(and (list? %)
                      (= 'test/strike (first %))
                      (= '$origin (nth % 1))
                      (= '$length (nth % 2))
                      (= 1.0 (nth % 3)))
                (tree-seq coll? identity stmts))
          "composite :argN wiring must not lower to nil named params"))))

(deftest inline-args-vector-expands-to-arg-ports
  (testing "migrated nested :args vectors lower as positional op args"
    (let [graph {:nodes {:n/start (n :n/start :start)
                         :n/n-set (n :n/n-set :local-set :key :budget :operation :define
                                     :inputs {:value {:nid :n/n-sel
                                                      :component :math/select
                                                      :args [{:nid :n/n-cond
                                                              :ref [:local :coin?]}
                                                             {:nid :n/n-a
                                                              :ref [:parameter :a]}
                                                             {:nid :n/n-b
                                                              :ref [:parameter :b]}]}})
                         :n/end (n :n/end :end)}
                 :links [(e :e/start :exec [:n/start :out] [:n/n-set :in])
                         (e :e/out :exec [:n/n-set :out] [:n/end :in])]}
          d (assoc skill
                   :parameters {:a {:type :double :default 1.0}
                               :b {:type :double :default 2.0}}
                   :graphs {:default (assoc graph :on :activation/start)})
          stmts (get-in (graph-compile/skill->core d) [:entries :default])
          forms (tree-seq coll? identity stmts)]
      (is (some #(and (list? %)
                      (= 'math/select (first %))
                      (= 'coin? (nth % 1))
                      (= '$a (nth % 2))
                      (= '$b (nth % 3)))
                forms)
          "inline :args vector must expand to :arg0/:arg1/:arg2"))))

(deftest foreach-index-as-lowers-to-each-binding-vector
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/n-coll (n :n/n-coll :parameter-ref :key :items)
                       :n/n-loop (n :n/n-loop :foreach :as :item :index-as :idx :limit 4)
                       :n/n-use (n :n/n-use :component :component :math/add)
                       :n/n-end (n :n/n-end :end)
                       :n/n-loop-end (n :n/n-loop-end :loop-end)
                       :n/n-x (n :n/n-x :local-get :key :item)
                       :n/n-i (n :n/n-i :local-get :key :idx)}
               :links [(e :e/start :exec [:n/start :out] [:n/n-loop :in])
                       (e :e/coll :data [:n/n-coll :value] [:n/n-loop :collection])
                       (e :e/body :exec [:n/n-loop :body] [:n/n-use :in])
                       (e :e/n-x :data [:n/n-x :value] [:n/n-use :arg0])
                       (e :e/n-i :data [:n/n-i :value] [:n/n-use :arg1])
                       (e :e/use :exec [:n/n-use :out] [:n/n-loop-end :in])
                       (e :e/back :exec [:n/n-loop-end :continue] [:n/n-loop :loop-back])
                       (e :e/done :exec [:n/n-loop :completed] [:n/n-end :in])]}
        d (assoc skill
                 :parameters {:items {:type :any :default nil}}
                 :graphs {:default (assoc graph :on :activation/start)})
        stmts (get-in (graph-compile/skill->core d) [:entries :default])]
    (is (some? (some #(and (seq? %) (= 'each (first %))
                           (vector? (second %))
                           (= '[item idx] (vec (second %))))
                   (tree-seq coll? identity stmts)))
        "foreach :index-as must lower to (each [item idx] ...)")))

(deftest inline-ref-fragments-in-event-payload-lower-to-sigils
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/n-set (n :n/n-set :local-set :key :wid :operation :define
                                   :inputs {:value "overworld"})
                       :n/n-evt (n :n/n-evt :component :component :event/emit
                                   :inputs {:type :world/block-impact
                                            :payload {:world-id {:nid :n/n-wid :ref [:local :wid]}
                                                      :seed {:nid :n/n-seed :ref [:local :wid]}
                                                      :fishing-exp-threshold
                                                      {:nid :n/n-th :ref [:parameter :threshold]}
                                                      :position
                                                      {:nid :n/n-pos
                                                       :component :value/field
                                                       :inputs {:field :position
                                                                :value {:nid :n/n-hit
                                                                        :ref [:local :wid]}}}}})
                       :n/end (n :n/end :end)}
               :links [(e :e/start :exec [:n/start :out] [:n/n-set :in])
                       (e :e/n-set :exec [:n/n-set :out] [:n/n-evt :in])
                       (e :e/n-evt :exec [:n/n-evt :out] [:n/end :in])]}
        d (assoc skill
                 :parameters {:threshold {:type :double :default 0.5}}
                 :graphs {:default (assoc graph :on :activation/start)})
        stmts (get-in (graph-compile/skill->core d) [:entries :default])
        forms (tree-seq coll? identity stmts)
        payload (some (fn [x]
                        (when (and (map? x) (contains? x :seed) (contains? x :world-id))
                          x))
                      forms)]
    (is (= 'wid (:world-id payload)))
    (is (= 'wid (:seed payload)))
    (is (= '$threshold (:fishing-exp-threshold payload)))
    (is (= (list :position 'wid) (:position payload))
        "nested :value/field fragments must lower to field access, not stay as maps")))

(deftest effect-vfx-payload-map-keys-checked-at-skill-compile
  ;; The payload check MOVED: it used to throw during lowering (skill->core),
  ;; which meant the editor's :collect pass died on the first bad payload
  ;; instead of listing every problem. It is now an ordinary diagnostic
  ;; raised by cn.li.node.compile/check-vfx-payload!, so lowering itself no
  ;; longer rejects anything -- see cn.li.node.vfx-payload-check-test for the
  ;; full matrix. This case is kept here to pin the move down.
  (testing "lowering no longer throws; the payload survives to the compiler"
    (let [graph {:nodes {:n/start (n :n/start :start)
                         :n/vfx (n :n/vfx :component :component :effect/vfx
                                   :inputs {:effect-id :beam-arc-fade
                                            :operation :spawn
                                            :payload {:ring-radius 0.34}})
                         :n/end (n :n/end :end)}
                 :links [(e :e/start :exec [:n/start :out] [:n/vfx :in])
                         (e :e/out :exec [:n/vfx :out] [:n/end :in])]}
          d (assoc skill :graphs {:default (assoc graph :on :activation/start)})
          opts {:effect-inputs {:beam-arc-fade
                                {:ring-radius {:type :any
                                               :map-keys {:from :double :to :double}}}}}]
      (is (some? (graph-compile/skill->core d opts)))))
  (testing "matching {:from :to} map compiles"
    (let [graph {:nodes {:n/start (n :n/start :start)
                         :n/vfx (n :n/vfx :component :component :effect/vfx
                                   :inputs {:effect-id :beam-arc-fade
                                            :operation :spawn
                                            :payload {:ring-radius {:from 0.34 :to 0.34}}})
                         :n/end (n :n/end :end)}
                 :links [(e :e/start :exec [:n/start :out] [:n/vfx :in])
                         (e :e/out :exec [:n/vfx :out] [:n/end :in])]}
          d (assoc skill :graphs {:default (assoc graph :on :activation/start)})
          opts {:effect-inputs {:beam-arc-fade
                                {:ring-radius {:type :any
                                               :map-keys {:from :double :to :double}}}}}]
      (is (some? (graph-compile/skill->core d opts)))))
  (testing "VFX value/field on context-ref requires :map-keys on that input"
    (let [doc {:schema :ac/vfx-v4
               :id :toy
               :lifecycle {:mode :transient}
               :inputs {:ring-radius {:type :any}}
               :graphs {:render
                        {:nodes {:n/start (n :n/start :start)
                                 :n/ctx (n :n/ctx :context-ref :key :ring-radius)
                                 :n/field (n :n/field :component :component :value/field
                                             :inputs {:field :from})
                                 :n/end (n :n/end :end)}
                         :links [(e :e/start :exec [:n/start :out] [:n/end :in])
                                 (e :e/field-value :data [:n/ctx :value] [:n/field :value])]}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"map-keys"
                            (graph-compile/vfx->core doc))))))

;; A lowering failure (a `fail` in graph-compile, raised before there is any
;; compile env) used to escape as an exception. In :collect mode that aborted
;; the editor's whole diagnostics pass -- the author saw a stack trace instead
;; of a list, and lost every other diagnostic in the graph with it.
(deftest lowering-failures-become-diagnostics-in-collect-mode
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/src (n :n/src :literal :value 1.0)
                       ;; :value/field with no :field input at all, feeding a
                       ;; component that IS on the exec chain (an unreachable
                       ;; node is never lowered, so it could not fail).
                       :n/fld (n :n/fld :component :component :value/field)
                       :n/act (n :n/act :component :component :test/do)
                       :n/end (n :n/end :end)}
               :links [(e :e/one :exec [:n/start :out] [:n/act :in])
                       (e :e/two :exec [:n/act :out] [:n/end :in])
                       (e :e/three :data [:n/src :value] [:n/fld :value])
                       (e :e/four :data [:n/fld :value] [:n/act :amount])]}
        d (assoc skill :graphs {:default (assoc graph :on :activation/start)})
        opts {:vocab {:test/do {:params {:amount {:type :double}}}}}]
    (testing ":collect lists it instead of throwing"
      (let [{:keys [ir diagnostics]} (graph-compile/compile-skill! d opts :collect)]
        (is (nil? ir))
        (is (= [:invalid-value-field] (mapv :code diagnostics)))
        (is (= [:error] (mapv :severity diagnostics)))))
    (testing ":throw still dies loudly, so startup cannot accept the graph"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":value/field"
                            (graph-compile/compile-skill! d opts :throw))))))
