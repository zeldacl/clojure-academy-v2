(ns cn.li.mcmod.events.world-lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.events.world-lifecycle :as lifecycle]))

(defn- clear-handlers! []
  (reset! @#'lifecycle/world-load-handlers [])
  (reset! @#'lifecycle/world-unload-handlers [])
  (reset! @#'lifecycle/world-save-handlers []))

(deftest register-and-dispatch-load-unload-test
  (clear-handlers!)
  (let [calls (atom [])]
    (lifecycle/register-world-lifecycle-handler!
      {:on-load (fn [world data] (swap! calls conj [:load world data]))
       :on-unload (fn [world] (swap! calls conj [:unload world]))})
    (lifecycle/dispatch-world-load :world {:k 1})
    (lifecycle/dispatch-world-unload :world)
    (is (= [[:load :world {:k 1}]
            [:unload :world]]
           @calls))))

(deftest dispatch-save-collects-non-nil-results-test
  (clear-handlers!)
  (lifecycle/register-world-lifecycle-handler! {:on-save (fn [_] nil)})
  (lifecycle/register-world-lifecycle-handler! {:on-save (fn [_] {:a 1})})
  (lifecycle/register-world-lifecycle-handler! {:on-save (fn [_] {:b 2})})
  (is (= [{:a 1} {:b 2}]
         (lifecycle/dispatch-world-save :world))))

(deftest dispatch-swallows-handler-exceptions-test
  (clear-handlers!)
  (let [seen (atom [])]
    (lifecycle/register-world-lifecycle-handler!
      {:on-load (fn [_ _] (throw (ex-info "boom-load" {})))
       :on-unload (fn [_] (throw (ex-info "boom-unload" {})))
       :on-save (fn [_] (throw (ex-info "boom-save" {})))})
    (lifecycle/register-world-lifecycle-handler!
      {:on-load (fn [_ _] (swap! seen conj :load-ok))
       :on-unload (fn [_] (swap! seen conj :unload-ok))
       :on-save (fn [_] (swap! seen conj :save-ok) {:ok true})})
    (testing "load/unload continue after faulty handlers"
      (is (nil? (lifecycle/dispatch-world-load :w nil)))
      (is (nil? (lifecycle/dispatch-world-unload :w))))
    (testing "save keeps collecting successful handlers"
      (is (= [{:ok true}] (lifecycle/dispatch-world-save :w))))
    (is (= [:load-ok :unload-ok :save-ok] @seen))))

(clojure.test/run-tests 'cn.li.mcmod.events.world-lifecycle-test)
