(ns cn.li.node.state-event-test
  "%key state reads, state! writes, and event! -- added after the golden
   thunder-bolt fixture already existed, because building combat-core's
   real vocabulary surfaced that session state (needed for railgun-style
   multi-phase abilities) and domain events (:score/mark, :domain/event)
   had no DSL surface form at all yet."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.pretty :as pretty]
            [cn.li.node.test-fixtures :as fx]))

(def ^:private text
  "{:ability :railgun-ish :tunables {}
    :state {:mode {:type :keyword :default :armed} :hold-ticks {:type :long :default 0}}
    :do [(state! :mode :charging)
         (let m %mode)
         (event! {:type :score/mark :tag :effective :weight 1.0})
         (finish {:outcome :performed :end-ability? true})]}")

(deftest state-write-and-read-and-event-compile-test
  (let [doc (surface/parse text)
        ir (compile/compile! doc fx/opts)
        instrs (mapcat :instrs (:blocks ir))]
    (testing "state! compiles to a :state-write instruction"
      (is (some #(and (= :state-write (:op %)) (= :mode (:key %))) instrs)))
    (testing "%mode compiles to a :state-read instruction"
      (is (some #(and (= :state-read (:op %)) (= :mode (:key %))) instrs)))
    (testing "event! compiles to an :event instruction carrying its :type"
      (is (some #(and (= :event (:op %)) (= :score/mark (:event-type %))) instrs)))))

(deftest unknown-state-key-is-a-real-error-test
  (doseq [text ["{:ability :bad :state {} :do [(state! :nope true) (finish {:outcome :performed})]}"
               "{:ability :bad :state {} :do [(let x %nope) (finish {:outcome :performed})]}"]]
    (let [doc (surface/parse text)]
      (try
        (compile/compile! doc fx/opts)
        (is false "expected compile! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :unknown-state-key (:code (ex-data e)))))))))

(deftest state-event-round-trip-test
  (let [doc1 (surface/parse text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)
        ir2 (compile/compile! doc2 fx/opts)
        doc3 (pretty/unparse ir2)]
    (is (= (:state doc1) (:state doc2)))
    (is (= doc2 doc3))))
