(ns cn.li.mc1201.client.render.script-render-executor
  "Primitive executor protocol for ScriptRender draw plans.

  Phase 2 provides a shell contract; concrete primitive implementations are
  added incrementally in later phases." )

(defprotocol IPrimitiveExecutor
  (draw! [this render-ctx draw-plan entity partial-tick]))

(defrecord NoopPrimitiveExecutor []
  IPrimitiveExecutor
  (draw! [_ _ _ _ _]
    nil))

(defn create-script-render-executor-runtime
  ([]
   (create-script-render-executor-runtime {}))
  ([initial-executors]
   {:executors (atom initial-executors)}))

(let [_instance (volatile! nil)]
  (defn- script-render-executor-instance []
    (or @_instance (vreset! _instance (create-script-render-executor-runtime)) @_instance)))

(def ^:dynamic *script-render-executor-runtime* nil)

(defn current-script-render-executor-runtime
  []
  (or *script-render-executor-runtime*
      (script-render-executor-instance)))

(defmacro with-script-render-executor-runtime
  [runtime & body]
  `(binding [*script-render-executor-runtime* ~runtime]
     ~@body))

(defn call-with-script-render-executor-runtime
  [runtime f]
  (binding [*script-render-executor-runtime* runtime]
    (f)))

(defn executors-atom
  []
  (:executors (current-script-render-executor-runtime)))

(defn executors-snapshot
  []
  @(executors-atom))

(defn clear-executors!
  []
  (reset! (executors-atom) {})
  nil)

(defn reset-executors-for-test!
  ([]
   (clear-executors!))
  ([executors]
   (reset! (executors-atom) executors)
   nil))

(defonce ^:private noop-executor (->NoopPrimitiveExecutor))

(defn register-executor!
  [kind executor]
  (swap! (executors-atom) assoc kind executor)
  executor)

(defn executor-for-kind
  [kind]
  (get (executors-snapshot) kind noop-executor))

(defn execute-draw-plan!
  [render-ctx draw-plan entity partial-tick]
  (let [kind (:kind draw-plan)
        executor (executor-for-kind kind)]
    (draw! executor render-ctx draw-plan entity partial-tick)))
