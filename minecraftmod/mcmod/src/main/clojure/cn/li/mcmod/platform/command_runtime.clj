(ns cn.li.mcmod.platform.command-runtime
  "Platform-neutral bridge for content-owned command initialization.

  State stored in Framework [:registry :commands :hooks]."
  (:require [cn.li.mcmod.framework :as fw]))

(def ^:private noop (fn [] nil))

(defn- default-state [] {:init-commands! noop})

(def ^:private cmd-hooks-path [:registry :commands :hooks])

(defn- command-hooks-snapshot []
  (if-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom cmd-hooks-path (default-state))
    (default-state)))

(defn register-command-hooks!
  [hooks]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in cmd-hooks-path
           (fn [current]
             (let [base (or current (default-state))]
               (reduce-kv (fn [m k v]
                            (if (and (contains? m k) (not= (get m k) v))
                              (throw (ex-info "Conflicting command hook" {:key k}))
                              (assoc m k v)))
                          base hooks)))))
  nil)

(defn reset-command-hooks-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in cmd-hooks-path (default-state)))
  nil)

(defn init-commands!
  []
  ((:init-commands! (command-hooks-snapshot))))
