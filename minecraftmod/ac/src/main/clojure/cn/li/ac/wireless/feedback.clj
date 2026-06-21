(ns cn.li.ac.wireless.feedback
  "Maps wireless operation results to translatable feedback messages.

  Pure functions — callers are responsible for attaching :messages to
  handler response maps and for client-side toast rendering."
  (:require [cn.li.mcmod.i18n :as i18n]))


;; ============================================================================
;; Reason → i18n key mapping
;; ============================================================================

(def ^:private reason->key
  "Maps [domain reason] to translation keys from ac_content_translations.clj.
  Keys follow the original AcademyCraft pattern: app.my_mod.freq_transmitter.eN"
  {:node    {:password  "app.my_mod.freq_transmitter.e1"
             :capacity  "app.my_mod.freq_transmitter.e2"
             :range     "app.my_mod.freq_transmitter.e2"
             :aborted   "app.my_mod.freq_transmitter.e4"
             :success   "app.my_mod.freq_transmitter.e6"
             :pending   "app.my_mod.freq_transmitter.e5"}
   :matrix  {:not-initialized "app.my_mod.freq_transmitter.e0"
             :password        "app.my_mod.freq_transmitter.e1"
             :ssid-exists     "app.my_mod.freq_transmitter.e2"
             :ssid-taken      "app.my_mod.freq_transmitter.e2"
             :capacity        "app.my_mod.freq_transmitter.e2"
             :not-found       "app.my_mod.freq_transmitter.e0"
             :aborted         "app.my_mod.freq_transmitter.e4"
             :success         "app.my_mod.freq_transmitter.e6"}
   :generator {:password "app.my_mod.freq_transmitter.e1"
               :capacity "app.my_mod.freq_transmitter.e2"
               :range    "app.my_mod.freq_transmitter.e2"
               :aborted  "app.my_mod.freq_transmitter.e4"
               :success  "app.my_mod.freq_transmitter.e6"
               :pending  "app.my_mod.freq_transmitter.e5"}
   :developer {:password   "app.my_mod.freq_transmitter.e1"
               :capacity   "app.my_mod.freq_transmitter.e2"
               :range      "app.my_mod.freq_transmitter.e2"
               :not-found  "app.my_mod.freq_transmitter.e0"
               :not-a-node "app.my_mod.freq_transmitter.e0"
               :aborted    "app.my_mod.freq_transmitter.e4"
               :success    "app.my_mod.freq_transmitter.e6"
               :pending    "app.my_mod.freq_transmitter.e5"}})


;; ============================================================================
;; Public API
;; ============================================================================

(defn result->messages
  "Convert a wireless operation result to a :messages vector suitable for
  handler response maps and toast rendering.

  result: {:success bool :reason kw}
  domain: :node | :matrix | :generator | :developer

  Returns a vector of message maps (or nil if no message).
  Each message: {:type :translatable :key \"...\" :args [...]}"
  [domain result]
  (let [reason (if (:success result)
                 :success
                 (or (:reason result) :aborted))
        domain-map (get reason->key domain)
        key (get domain-map reason)]
    (when key
      [{:type :translatable :key key :args []}])))


(defn reason->i18n-key
  "Return the i18n translation key for a given domain and reason keyword.
  Returns nil if no mapping exists."
  [domain reason]
  (get-in reason->key [domain reason]))


(defn translate-message
  "Translate a single message map to a localized string.
  Uses the platform i18n translate function."
  [{:keys [key args]}]
  (let [template (i18n/translate key)]
    (if (seq args)
      (apply format template args)
      template)))
