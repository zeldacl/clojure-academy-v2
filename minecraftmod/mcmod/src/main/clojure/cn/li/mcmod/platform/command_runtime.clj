(ns cn.li.mcmod.platform.command-runtime
  "Platform-neutral bridge for content-owned command initialization.

  State stored in Framework [:registry :commands :hooks].

  Registered hooks start empty; platform readers apply noop fallbacks when a slot
  has not been claimed by content yet."
  (:require [cn.li.mcmod.framework :as fw]))

(def ^:private noop (fn [] nil))

(def ^:private cmd-hooks-path [:registry :commands :hooks])

(defn- registered-hooks
  []
  (if-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom cmd-hooks-path {})
    {}))

(defn register-command-hooks!
  "Register content-owned command hooks.

  Throws only when a slot was already claimed by content with a different fn."
  [hooks]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in cmd-hooks-path
           (fn [current]
             (let [base (or current {})]
               (reduce-kv (fn [m k v]
                            (if (and (contains? m k) (not= (get m k) v))
                              (throw (ex-info "Conflicting command hook" {:key k}))
                              (assoc m k v)))
                          base hooks)))))
  nil)

(defn reset-command-hooks-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in cmd-hooks-path {}))
  nil)

(defn init-commands!
  []
  ((or (:init-commands! (registered-hooks)) noop)))
