(ns cn.li.mcmod.platform.runtime
  "Shared helpers for platform SPI runtimes (process-static adapter installs).

  Platform namespaces keep a private ^:dynamic *runtime* (or ops map) and expose
  install-*/available?/current/call-with-runtime plus operation wrappers."
  (:require [cn.li.mcmod.util.log :as log]))

(defn install-impl!
  "Install a platform adapter implementation into a private runtime var."
  [runtime-var impl label]
  (when (nil? impl)
    (throw (ex-info "Platform install requires non-nil implementation"
                    {:label label :var (str runtime-var)})))
  (alter-var-root runtime-var (constantly impl))
  (log/info (or label "platform") "installed")
  nil)

(defn impl-available?
  [runtime-var]
  (some? (var-get runtime-var)))

(defn impl-current
  [runtime-var]
  (var-get runtime-var))

(defn register-hook!
  "Register into a runtime map atom with duplicate policy.

  - :same-value-idempotent — equal values allowed
  - :fail-on-duplicate (default) — different values throw"
  [runtime-atom key value & {:keys [duplicate-policy label]
                             :or {duplicate-policy :fail-on-duplicate}}]
  (let [prev (get @runtime-atom key)]
    (cond
      (nil? prev)
      (swap! runtime-atom assoc key value)

      (= prev value)
      nil

      (= duplicate-policy :same-value-idempotent)
      nil

      :else
      (throw (ex-info "Duplicate platform hook registration"
                      {:key key
                       :label label
                       :previous prev
                       :incoming value}))))
  nil)

(defn freeze-runtime!
  "Mark runtime atom as frozen; further register-hook! calls fail."
  [runtime-atom]
  (swap! runtime-atom assoc ::frozen? true)
  nil)

(defn register-hook-when-frozen!
  [runtime-atom key value opts]
  (when (::frozen? @runtime-atom)
    (throw (ex-info "Platform hook registry is frozen" {:key key})))
  (apply register-hook! runtime-atom key value opts))

(defmacro def-impl-wrappers
  "Define operation wrappers that delegate to a private runtime var.

  Usage: (def-impl-wrappers '*runtime* IRaycast
          [get-player-look-vector* get-player-look-vector player-uuid] ...)

  Each spec: [wrapper-name protocol-fn & arg-symbols]"
  [runtime-sym protocol-sym & wrapper-specs]
  `(do
     ~@(for [[wrapper-name protocol-fn & args] wrapper-specs]
         `(defn ~wrapper-name ~(vec args)
            (when-let [rt# ~runtime-sym]
              (~protocol-fn rt# ~@args))))))
