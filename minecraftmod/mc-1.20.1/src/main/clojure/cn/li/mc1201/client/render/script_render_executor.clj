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

(defonce ^:private executors* (atom {}))
(defonce ^:private noop-executor (->NoopPrimitiveExecutor))

(defn register-executor!
  [kind executor]
  (swap! executors* assoc kind executor)
  executor)

(defn executor-for-kind
  [kind]
  (get @executors* kind noop-executor))

(defn execute-draw-plan!
  [render-ctx draw-plan entity partial-tick]
  (let [kind (:kind draw-plan)
        executor (executor-for-kind kind)]
    (draw! executor render-ctx draw-plan entity partial-tick)))
