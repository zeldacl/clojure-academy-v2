(ns cn.li.ac.block.machine-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.machine.runtime :as runtime]
            [cn.li.mcmod.platform.world :as world]))

(deftest schema-runtime-test
  (testing "schema-runtime builds lifecycle fns"
    (let [schema [{:key :energy :type :double :default 0.0 :persist? true}
                  {:key :update-ticker :type :int :default 0 :persist? false}]
          rt (runtime/schema-runtime schema :server-only? true)]
      (is (= 0.0 (get (:default-state rt) :energy)))
      (is (fn? (:load-fn rt)))
      (is (fn? (:save-fn rt))))))

(deftest make-tick-fn-test
  (testing "tick wrapper commits only on state change"
    (let [committed (atom nil)
          be {}
          tick-fn (runtime/make-tick-fn
                    {:default-state {:energy 0.0}
                     :tick-state (fn [state _level _pos _block-state _be]
                                   (assoc state :energy 1.0))})]
      (with-redefs [world/world-is-client-side* (fn [_] false)
                    runtime/state-or-default (fn [_ _] {:energy 0.0})
                    runtime/commit-state!
                    (fn [_ _ _ old new & _]
                      (reset! committed {:old old :new new}))]
        (tick-fn :level :pos :bs be)
        (is (= {:old {:energy 0.0} :new {:energy 1.0}} @committed))))))

(deftest make-tick-fn-initial-state-and-after-commit-test
  (testing "initial-state and after-commit hooks run on change"
    (let [committed (atom nil)
          after (atom nil)
          be {}
          tick-fn (runtime/make-tick-fn
                    {:default-state {:energy 0.0}
                     :initial-state (fn [_be _level _pos _block-state] {:energy 0.5})
                     :tick-state (fn [state _level _pos _block-state _be]
                                   (assoc state :energy 1.0))
                     :after-commit! (fn [_be _level _pos old new]
                                      (reset! after {:old old :new new}))})]
      (with-redefs [world/world-is-client-side* (fn [_] false)
                    runtime/commit-state!
                    (fn [_ _ _ old new & _]
                      (reset! committed {:old old :new new}))]
        (tick-fn :level :pos :bs be)
        (is (= {:old {:energy 0.5} :new {:energy 1.0}} @committed))
        (is (= {:old {:energy 0.5} :new {:energy 1.0}} @after))))))

(deftest commit-transform-test
  (testing "commit-transform! applies transform through commit boundary"
    (let [committed (atom nil)
          tile {}]
      (with-redefs [runtime/state-or-default (fn [_ _] {:energy 0.0})
                    runtime/commit-state!
                    (fn [_ _ _ old new & _]
                      (reset! committed {:old old :new new}))]
        (runtime/commit-transform! tile {:energy 0.0} #(assoc % :energy 5.0))
        (is (= {:old {:energy 0.0} :new {:energy 5.0}} @committed))))))

(deftest make-open-gui-handler-test
  (testing "open handler requires player world pos and respects sneaking"
    (let [handler (runtime/make-open-gui-handler :solar)
          opened (atom false)]
      (with-redefs [world/world-is-client-side* (fn [_] true)
                    cn.li.ac.gui.open/open-gui-by-type (fn [& _] (reset! opened true))]
        (handler :p :w :pos :solar :sneaking true)
        (is (false? @opened))
        (handler :p :w :pos :solar)
        (is (true? @opened))))))

(deftest make-open-gui-handler-predicate-test
  (testing "make-open-gui-handler* respects can-open? predicate"
    (let [handler (runtime/make-open-gui-handler* :wind-gen-main (fn [_ _ _ _ _] false))
          opened (atom false)]
      (with-redefs [world/world-is-client-side* (fn [_] true)
                    cn.li.ac.gui.open/open-gui-by-type (fn [& _] (reset! opened true))]
        (handler :p :w :pos :wind-gen-main)
        (is (false? @opened))))))

(deftest make-open-gui-handler-multiblock-options-test
  (testing "resolve-open-pos and server-before-open! hooks"
    (let [opened (atom nil)
          handler (runtime/make-open-gui-handler*
                    :developer
                    (constantly true)
                    :resolve-open-pos (fn [_ _ _] :controller-pos)
                    :server-before-open! (fn [_ _ _] true))]
      (with-redefs [world/world-is-client-side* (fn [_] false)
                    cn.li.ac.gui.open/open-gui-by-type
                    (fn [player gui-type world pos]
                      (reset! opened {:player player :gui-type gui-type :world world :pos pos}))]
        (handler :p :w :part-pos :developer)
        (is (= {:player :p :gui-type :developer :world :w :pos :controller-pos}
               @opened))))))
