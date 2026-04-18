(ns cn.li.ac.ability.effect
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private op-registry (atom {}))

(defn register-op!
  [op-kw f]
  (swap! op-registry assoc op-kw f)
  op-kw)

(defmacro defop
  "Define an effect op. Accepts an optional docstring between the keyword and arglist."
  [op-kw & forms]
  (let [[_docstring arglist & body] (if (string? (first forms))
                                      forms
                                      (cons nil forms))]
    `(register-op! ~op-kw (fn ~arglist ~@body))))

(defn- resolve-param-value
  [evt value]
  (cond
    (fn? value) (value evt)
    (keyword? value) (get evt value value)
    :else value))

(defn resolve-params
  [evt params]
  (cond
    (map? params)
    (into {} (map (fn [[k v]] [k (resolve-param-value evt v)]) params))
    :else params))

(defn run-op!
  [evt [op-kw params]]
  (if-let [f (get @op-registry op-kw)]
    (try
      (or (f evt (resolve-params evt params)) evt)
      (catch Exception e
        (log/warn "Effect op failed" op-kw (ex-message e))
        evt))
    evt))

(defn run-ops!
  [evt ops]
  (reduce run-op! evt (or ops [])))

(defn run-stage!
  [spec evt stage]
  (let [ops (or (get-in spec [:ops stage])
                (case stage
                  :perform (:perform spec)
                  :down (:on-down spec)
                  :tick (:on-tick spec)
                  :up (:on-up spec)
                  :abort (:on-abort spec)
                  nil))]
    (cond
      (vector? ops) (run-ops! evt ops)
      (fn? ops) (or (ops evt) evt)
      :else evt)))
