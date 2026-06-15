(ns cn.li.ac.config.gameplay
  "Gameplay/UI configuration owned by AC and exposed through mcmod config descriptors.

  Platform modules provide storage/backends only; gameplay defaults and typed
  accessors stay in AC so loader projects never import cn.li.ac.* directly."
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log]))

(def default-generic-config
  {:use-mouse-wheel false
   :give-cloud-terminal true
   :font "Microsoft YaHei"})

(def default-values
  default-generic-config)

(def descriptors
  [{:key :use-mouse-wheel
    :path "generic.use-mouse-wheel"
    :section :generic
    :type :boolean
    :default (:use-mouse-wheel default-values)
    :comment "Whether AC GUI interactions may use mouse wheel shortcuts."}
   {:key :give-cloud-terminal
    :path "generic.give-cloud-terminal"
    :section :generic
    :type :boolean
    :default (:give-cloud-terminal default-values)
    :comment "Whether players receive a Cloud Terminal through AC flows."}
   {:key :font
    :path "generic.font"
    :section :generic
    :type :string
    :default (:font default-values)
    :comment "Reserved label for a bundled UI font; Minecraft loads fonts from assets/<modid>/font/*.json + TTF in the jar, not from OS font names."}
   {:key :heads-or-tails
    :path "generic.heads-or-tails"
    :section :generic
    :type :boolean
    :default false
    :comment "Whether the Heads or Tails coin flip game is enabled."}])

(defn- value
  [k]
  (get (config-common/gameplay-config) k (get default-values k)))

(defn level-value
  "Read a level-indexed numeric list. Out-of-range or non-numeric levels return 0."
  [values level]
  (let [idx (if (number? level) (int level) -1)]
    (get (vec (or values [])) idx 0)))

(defn list-predicate
  "Build a predicate over a dynamic string list getter."
  [values-fn]
  (fn [id]
    (contains? (set (map str (values-fn))) (str id))))

(defn init-config!
  "Ensure gameplay defaults are present in the shared config registry."
  []
  (config-reg/register-config-descriptors! config-common/gameplay-domain descriptors)
  (config-reg/ensure-default-values! config-common/gameplay-domain default-values)
  (log/info "Initialized gameplay config descriptors" {:domain config-common/gameplay-domain})
  nil)

(defn use-mouse-wheel-enabled? []
  (boolean (value :use-mouse-wheel)))

(defn give-cloud-terminal-enabled? []
  (boolean (value :give-cloud-terminal)))

(defn get-font []
  (str (value :font)))

(defn validate-config!
  "Validate currently effective gameplay configuration values."
  []
  (let [errors (atom [])]
    (when-not (string? (value :font))
      (swap! errors conj "font must be a string"))
    (if (empty? @errors)
      (do
        (log/info "Gameplay configuration validation passed")
        nil)
      (do
        (log/error "Gameplay configuration validation failed:" @errors)
        (throw (ex-info "Invalid gameplay configuration" {:errors @errors}))))))
