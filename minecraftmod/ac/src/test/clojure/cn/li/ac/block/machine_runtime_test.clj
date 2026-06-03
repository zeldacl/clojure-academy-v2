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
                     :tick-state (fn [state _] (assoc state :energy 1.0))})]
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
                     :initial-state (fn [_ _] {:energy 0.5})
                     :tick-state (fn [state _] (assoc state :energy 1.0))
                     :after-commit! (fn [_ _ _ old new _]
                                      (reset! after {:old old :new new}))})]
      (with-redefs [world/world-is-client-side* (fn [_] false)
                    runtime/commit-state!
                    (fn [_ _ _ old new & _]
                      (reset! committed {:old old :new new}))]
        (tick-fn :level :pos :bs be)
        (is (= {:old {:energy 0.5} :new {:energy 1.0}} @committed))
        (is (= {:old {:energy 0.5} :new {:energy 1.0}} @after))))))

(deftest make-open-gui-handler-test
  (testing "open handler requires player world pos and respects sneaking"
    (let [handler (runtime/make-open-gui-handler :solar)
          opened (atom false)]
      (with-redefs [cn.li.ac.gui.open/open-gui-by-type (fn [& _] (reset! opened true))]
        (handler {:player :p :world :w :pos :pos :sneaking true})
        (is (false? @opened))
        (handler {:player :p :world :w :pos :pos})
        (is (true? @opened))))))
