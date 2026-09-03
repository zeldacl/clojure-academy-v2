(ns cn.li.mcmod.runtime.effect-emit-test
  "IR fixtures here are hand-built maps matching cn.li.node.compile's shape
   rather than produced by that compiler: mcmod must not depend on
   node-core even in test scope (the same zero-dependency convention
   node-core's own pure-auto-test-runner docstring states for the reverse
   direction), and the shape is small/stable enough to hand-write directly
   -- exactly how cn.li.node.ir-test already covers its own validator."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.runtime.effect-emit :as emit])
  (:import [cn.li.mcmod.runtime.effect ExecutionFrame]))

(defn- reg [bank slot] [:reg bank slot])
(defn- const [bank slot] [:const bank slot])

(def ^:private invoke-op
  (fn [op args]
    (case op
      :add (+ (double (first args)) (double (second args)))
      :gt (> (double (first args)) (double (second args)))
      (throw (ex-info "unknown test op" {:op op})))))

(deftest pure-op-and-finish-test
  (let [ir {:ir/version 1 :id :pure-test :tunable-types {} :registers {:doubles 1 :longs 0 :booleans 0 :objects 0}
            :constants {:doubles [2.0 3.0] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks [{:id 0 :instrs
                      [{:op :pure :nid "n1" :dst (reg :doubles 0) :fn :add :args [(const :doubles 0) (const :doubles 1)]}
                       {:op :finish :nid "n2" :outcome :performed :next-phase nil :end-ability? true}]}]}
        program (emit/compile-program ir {:invoke-op invoke-op})
        frame (emit/new-frame program nil)]
    (emit/dispatch! program :default frame)
    (is (= 5.0 (aget ^doubles (.-doubles frame) 0)))
    (is (= {:outcome :performed :next-phase nil :end-ability? true} (.-result frame)))))

(deftest tun-cap-get-copy-convert-test
  (let [ir {:ir/version 1 :id :refs-test :tunable-types {:range :double} :registers {:doubles 1 :longs 1 :booleans 0 :objects 3}
            :constants {:doubles [] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks [{:id 0 :instrs
                      [{:op :tun :nid "n1" :dst (reg :doubles 0) :key :range}
                       {:op :cap :nid "n2" :dst (reg :objects 0) :key :caster/eye}
                       {:op :get :nid "n3" :dst (reg :objects 1) :src (reg :objects 0) :key :x}
                       {:op :copy :nid "n4" :dst (reg :objects 2) :src (reg :objects 1)}
                       {:op :convert :nid "n5" :dst (reg :longs 0) :src (reg :doubles 0) :to :long}
                       {:op :finish :nid "n6" :outcome :performed :next-phase nil :end-ability? false}]}]}
        program (emit/compile-program ir {:invoke-op invoke-op})
        frame (emit/new-frame program {:tunables {:range 12.5} :capabilities {:caster/eye {:x 7.0 :y 0.0 :z 0.0}}})]
    (emit/dispatch! program :default frame)
    (testing ":tun reads from input's :tunables"
      (is (= 12.5 (aget ^doubles (.-doubles frame) 0))))
    (testing ":cap reads from input's :capabilities, :get reads a field, :copy passes it through unchanged"
      (is (= 7.0 (aget ^objects (.-objects frame) 2))))
    (testing ":convert does a real numeric cast, not a reinterpretation"
      (is (= 12 (aget ^longs (.-longs frame) 0))))))

(deftest branch-and-loop-test
  (testing "a hand-rolled loop (header/body/back-edge), the same shape
            cn.li.node.compile/compile-each produces -- proves branch/jump
            correctly drive block selection across multiple visits to the
            SAME block, re-reading a register this block itself mutated on
            a previous visit (register contents are not block-scoped)"
    (let [;; constants: [0]=start 0.0, [1]=step 1.0, [2]=limit 5.0
          ir {:ir/version 1 :id :loop-test :tunable-types {} :registers {:doubles 1 :longs 0 :booleans 1 :objects 0}
              :constants {:doubles [0.0 1.0 5.0] :longs [] :booleans [] :objects []}
              :entries {:default 0}
              :blocks
              [{:id 0 :instrs [{:op :copy :nid "n1" :dst (reg :doubles 0) :src (const :doubles 0)}
                               {:op :jump :nid "n2" :target 1}]}
               {:id 1 :instrs [{:op :pure :nid "n3" :dst (reg :booleans 0) :fn :gt :args [(const :doubles 2) (reg :doubles 0)]}
                               {:op :branch :nid "n4" :test (reg :booleans 0) :then 2 :else 3}]}
               {:id 2 :instrs [{:op :pure :nid "n5" :dst (reg :doubles 0) :fn :add :args [(reg :doubles 0) (const :doubles 1)]}
                               {:op :jump :nid "n6" :target 1}]}
               {:id 3 :instrs [{:op :finish :nid "n7" :outcome :performed :next-phase nil :end-ability? false}]}]}
          program (emit/compile-program ir {:invoke-op invoke-op})
          frame (emit/new-frame program nil)]
      (emit/dispatch! program :default frame)
      (is (= 5.0 (aget ^doubles (.-doubles frame) 0)) "loop increments while 5.0 > counter, stopping at 5.0"))))

(deftest query-action-vfx-state-write-test
  (let [calls (atom [])
        flushed (atom false)
        host {:query! (fn [cap args _fr] (swap! calls conj [:query cap args]) {:hit true})
              :command! (fn [cap args _fr] (swap! calls conj [:action cap args]))
              :flush! (fn [_fr] (reset! flushed true))}
        ir {:ir/version 1 :id :host-test :tunable-types {} :registers {:doubles 0 :longs 0 :booleans 0 :objects 1}
            :constants {:doubles [] :longs [] :booleans [] :objects [:electric]}
            :entries {:default 0}
            :blocks [{:id 0 :instrs
                      [{:op :query :nid "n1" :dst (reg :objects 0) :node :target/raycast :capability :raycast
                        :barrier? true :args {:distance (const :objects 0)}}
                       {:op :action :nid "n2" :node :combat/damage :capability :entity/damage
                        :args {:type (const :objects 0)}}
                       {:op :vfx :nid "n3" :args {:effect (const :objects 0)}}
                       {:op :state-write :nid "n4" :key :mode :src (const :objects 0)}
                       {:op :finish :nid "n5" :outcome :performed :next-phase nil :end-ability? true}]}]}
        program (emit/compile-program ir {:invoke-op invoke-op :host host})
        frame (emit/new-frame program nil)]
    (emit/dispatch! program :default frame)
    (testing ":barrier? true flushes the host before the query runs"
      (is @flushed))
    (testing "query/action dispatch by the instruction's compile-time-resolved :capability"
      (is (= [:query :raycast {:distance :electric}] (first @calls)))
      (is (= [:action :entity/damage {:type :electric}] (second @calls))))
    (testing "vfx signals accumulate on the frame's own outbox, tagged with the instruction's :nid"
      (is (= [{:effect :electric :nid "n3"}] (into [] (.-vfx frame)))))
    (testing "state writes accumulate as patches for the caller to commit, never mutate persistent state directly"
      (is (= [{:key :mode :value :electric}] (into [] (.-stateWrites frame)))))))

(deftest dispatch-on-unknown-entry-throws-test
  (let [ir {:ir/version 1 :id :x :tunable-types {} :registers {:doubles 0 :longs 0 :booleans 0 :objects 0}
            :constants {:doubles [] :longs [] :booleans [] :objects []}
            :entries {:default 0}
            :blocks [{:id 0 :instrs [{:op :finish :nid "n1" :outcome :performed :next-phase nil :end-ability? true}]}]}
        program (emit/compile-program ir {:invoke-op invoke-op})
        frame (emit/new-frame program nil)]
    (is (thrown? clojure.lang.ExceptionInfo (emit/dispatch! program :not-a-real-entry frame)))))
