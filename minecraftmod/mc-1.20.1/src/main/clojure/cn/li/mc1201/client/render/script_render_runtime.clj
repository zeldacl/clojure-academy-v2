(ns cn.li.mc1201.client.render.script-render-runtime
  "ScriptRender runtime cache + kill-switch.

  This namespace is queried by Java dispatchers to decide whether a renderer-id
  should use the scripted rendering path." 
  (:require [cn.li.mc1201.client.render.script-render-compiler :as compiler]
            [cn.li.mcmod.config.script-render :as render-config]
            [cn.li.mcmod.client.render.script-render-registry :as registry]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private scripted-render-enabled?* (atom true))
(defonce ^:private draw-plan-cache* (atom {}))
(defonce ^:private renderer-overrides* (atom {}))
(defonce ^:private config-initialized? (atom false))

(defn- ensure-config-initialized!
  []
  (when (compare-and-set! config-initialized? false true)
    (try
      (render-config/init-descriptors!)
      (catch Throwable t
        (reset! config-initialized? false)
        (throw t))))
  nil)

(def ^:private scripted-effect-kinds
  #{:billboard-cross :ring-lines :polyline-arc})

(def ^:private scripted-marker-kinds
  #{:billboard-cross})

(def ^:private scripted-ray-kinds
  #{:ray-composite
    :ray-composite-lite})

(defn scripted-render-enabled?
  []
  (ensure-config-initialized!)
  (and @scripted-render-enabled?*
       (render-config/script-render-enabled?)))

(defn set-scripted-render-enabled!
  [enabled?]
  (reset! scripted-render-enabled?* (boolean enabled?))
  (when-not @scripted-render-enabled?*
    (reset! draw-plan-cache* {}))
  @scripted-render-enabled?*)

(defn set-renderer-enabled!
  [renderer-id enabled?]
  (ensure-config-initialized!)
  (when (and (string? renderer-id) (not (empty? renderer-id)))
    (swap! renderer-overrides* assoc renderer-id (boolean enabled?)))
  nil)

(defn clear-renderer-overrides!
  []
  (ensure-config-initialized!)
  (reset! renderer-overrides* {})
  nil)

(defn clear-cache!
  []
  (reset! draw-plan-cache* {})
  nil)

(defn cache-size
  []
  (count @draw-plan-cache*))

(defn rebuild-cache!
  []
  (if-not (scripted-render-enabled?)
    (do
      (clear-cache!)
      {})
    (let [compiled (compiler/compile-profiles (registry/snapshot))]
      (reset! draw-plan-cache* compiled)
      (log/info "ScriptRender cache rebuilt, size=" (count compiled))
      compiled)))

(defn get-draw-plan
  [renderer-id]
  (when (and (scripted-render-enabled?)
             (string? renderer-id)
             (not (empty? renderer-id)))
    (or (get @draw-plan-cache* renderer-id)
        (when-let [profile (registry/get-profile renderer-id)]
          (let [plan (compiler/compile-profile profile)]
            (swap! draw-plan-cache* assoc renderer-id plan)
            plan)))))

(defn- normalize-render-kind
  [render-kind]
  (cond
    (keyword? render-kind) render-kind
    (string? render-kind) (keyword render-kind)
    :else :effect))

(defn draw-plan-kind
  [renderer-id]
  (when-let [plan (get-draw-plan renderer-id)]
    (when-let [kind (:kind plan)]
      (name kind))))

(defn draw-plan-param-double
  [renderer-id param-key default-value]
  (let [k (cond
            (keyword? param-key) param-key
            (string? param-key) (keyword param-key)
            :else nil)
        value (when (and k (some? renderer-id))
                (get-in (get-draw-plan renderer-id) [:params k]))]
    (double (if (number? value) value default-value))))

(defn draw-plan-param-int
  [renderer-id param-key default-value]
  (let [k (cond
            (keyword? param-key) param-key
            (string? param-key) (keyword param-key)
            :else nil)
        value (when (and k (some? renderer-id))
                (get-in (get-draw-plan renderer-id) [:params k]))]
    (int (if (number? value) value default-value))))

(defn use-scripted-renderer?
  [renderer-id render-kind]
  (let [kind (normalize-render-kind render-kind)
        override (get @renderer-overrides* renderer-id ::unset)
        config-disabled? (contains? (render-config/disabled-renderer-ids) renderer-id)
        override-allowed? (and (not= override false)
                               (not config-disabled?))
        plan (get-draw-plan renderer-id)]
    (and (some? plan)
         (:enabled? plan)
         override-allowed?
         (case kind
           :effect (contains? scripted-effect-kinds (:kind plan))
           :marker (contains? scripted-marker-kinds (:kind plan))
           :ray (contains? scripted-ray-kinds (:kind plan))
           false))))
