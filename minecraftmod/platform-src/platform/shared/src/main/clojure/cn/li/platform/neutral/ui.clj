(ns cn.li.platform.neutral.ui
  (:require [clojure.string :as str])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]))

(def ^:private function-operations
  [:user-signal :dispose! :resize! :flush! :put-user-signal! :clock-ms-sig
   :partial-ticks-sig :game-ticks-sig :get-tape-arr :focus-idx :hovered-idx
   :node-by-idx :set-hovered-idx! :ensure-layout! :ensure-tape! :hit-test
   :dispatch-editable-key! :dispatch-key! :dispatch-char! :dispatch-mouse-press!
   :dispatch-mouse-release! :dispatch-mouse-drag! :dispatch-scroll! :sset-l! :sset-d!])
(def ^:private value-operations
  [:push-clip-sentinel :pop-clip-sentinel :push-transform-sentinel :pop-transform-sentinel
   :flag-hovered :flag-focused :flag-render-dirty])

(defn- unavailable [operation] (throw (IllegalStateException. (str "UI provider unavailable: " operation))))
(doseq [operation function-operations]
  (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))
(doseq [operation value-operations]
  (intern *ns* (symbol (name operation)) nil))
(def FLAG-HOVERED nil)
(def FLAG-FOCUSED nil)
(def FLAG-RENDER-DIRTY nil)

;; UiRt is a neutral Java API.  Keeping this tiny predicate on the AOT side
;; avoids making renderers statically require the Clojure UI runtime namespace.
(defn disposed? [^UiRt rt]
  (.isDisposed rt))

(defn install! [operations]
  (let [expected (into #{} (concat function-operations value-operations))]
    (when (or (not= expected (set (keys operations))) (some (complement ifn?) (vals operations)))
      (throw (ex-info "UI provider contract mismatch" {:actual (sort (keys operations))})))
    (doseq [operation function-operations]
      (alter-var-root (ns-resolve *ns* (symbol (name operation))) (constantly (get operations operation))))
    (doseq [operation value-operations]
      (alter-var-root (ns-resolve *ns* (symbol (name operation)))
                      (constantly ((get operations operation))))))
  (alter-var-root #'FLAG-HOVERED (constantly (var-get (ns-resolve *ns* 'flag-hovered))))
  (alter-var-root #'FLAG-FOCUSED (constantly (var-get (ns-resolve *ns* 'flag-focused))))
  (alter-var-root #'FLAG-RENDER-DIRTY (constantly (var-get (ns-resolve *ns* 'flag-render-dirty))))
  nil)
