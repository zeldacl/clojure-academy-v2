(ns cn.li.ac.ability.server.effect.core
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private op-registry (atom {}))

(defn register-op!
  [op-kw f]
  (swap! op-registry assoc op-kw f)
  op-kw)

(def ^:private default-op-namespaces
  '[cn.li.ac.ability.server.effect.damage
    cn.li.ac.ability.server.effect.fx
    cn.li.ac.ability.server.effect.geom
    cn.li.ac.ability.server.effect.motion
    cn.li.ac.ability.server.effect.world
    cn.li.ac.ability.server.effect.state
    cn.li.ac.ability.server.effect.potion
    cn.li.ac.ability.server.effect.beam])

(defonce ^:private default-ops-installed?
  (atom false))

(defn- op-var-symbol
  [op-kw]
  (symbol (str "op-"
               (-> (if (keyword? op-kw) (name op-kw) (str op-kw))
                   (str/replace #"[^A-Za-z0-9_?!*+<>=.-]" "-")))))

(defmacro defop
  "Declare an effect op. Accepts an optional docstring between the keyword and arglist.

  The declaration is require-safe; call init-default-ops! or
  register-op-declarations-in-ns! to install ops into the runtime registry."
  [op-kw & forms]
  (let [[_docstring arglist & body] (if (string? (first forms))
                                      forms
                                      (cons nil forms))]
    `(def ~(op-var-symbol op-kw)
       {::op-declaration true
        :op-kw ~op-kw
        :f (fn ~arglist ~@body)})))

(defn- op-declaration?
  [value]
  (and (map? value)
       (::op-declaration value)
       (keyword? (:op-kw value))
       (ifn? (:f value))))

(defn register-op-declarations-in-ns!
  [ns-sym]
  (doseq [declaration (->> (ns-interns ns-sym)
                           vals
                           (keep #(when (bound? %) (var-get %)))
                           (filter op-declaration?))]
    (register-op! (:op-kw declaration) (:f declaration)))
  nil)

(defn init-default-ops!
  "Require and register built-in effect op declarations once."
  []
  (when (compare-and-set! default-ops-installed? false true)
    (try
      (doseq [ns-sym default-op-namespaces]
        (require ns-sym)
        (register-op-declarations-in-ns! ns-sym))
      (catch Throwable t
        (reset! default-ops-installed? false)
        (throw (ex-info "Failed to initialize default effect ops" {} t)))))
  nil)

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
