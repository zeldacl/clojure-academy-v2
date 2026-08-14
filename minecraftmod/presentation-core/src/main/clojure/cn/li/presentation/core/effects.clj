(ns cn.li.presentation.core.effects
  "Deterministic effect-instance runtime shared by HUD/world/first-person hosts.

   Templates are data compiled from *.fx.edn. Controllers may spawn/stop an
   instance, but cannot inject arbitrary backend commands or call a backend."
  (:require [cn.li.presentation.core.animation :as animation])
  (:import [cn.li.presentation.core RenderCommand$Beam RenderCommand$Billboard
            RenderCommand$Mesh RenderCommand$ParticleBatch RenderCommand$Ribbon
            RenderCommand$Quad RenderCommand$PostProcess
            RenderCommand$CameraContribution RenderPass RenderStage]))

(def stage-map
  {:world-before-translucent RenderStage/WORLD_BEFORE_TRANSLUCENT
   :world-after-translucent RenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person RenderStage/FIRST_PERSON
   :hud-underlay RenderStage/HUD_UNDERLAY
   :hud RenderStage/HUD
   :hud-overlay RenderStage/HUD_OVERLAY
   :screen RenderStage/SCREEN
   :post-process RenderStage/POST_PROCESS})

(def primitive-set #{:particle :billboard :ribbon :beam :arc :mesh :camera :post-process})

(defn create []
  {:next-id (atom 1)
   :templates (atom {})
   :instances (atom {})
   :resource-generation (atom 0)})

(defn register-template! [runtime template]
  (when-not (and (keyword? (:id template))
                 (contains? primitive-set (:primitive template))
                 (contains? stage-map (:stage template))
                 (keyword? (:owner template)))
    (throw (ex-info "invalid compiled effect template" {:template template})))
  (swap! (:templates runtime) assoc (:id template) template)
  (:id template))

(defn- template-duration [template]
  (long (or (:duration-ms template)
            (when-let [timeline (:timeline template)]
              (let [last-at (reduce max 0.0 (map #(double (or (:at %) 0.0)) timeline))]
                (if (<= last-at 1.0) 1000.0 last-at)))
            1000)))

(defn- compiled-timeline [template]
  (when (seq (:timeline template))
    (let [duration (template-duration template)
          tracks (->> (:timeline template)
                      (group-by :property)
                      (map (fn [[property frames]]
                             [property
                              (mapv #(update % :at (fn [at]
                                                     (let [at (double (or at 0.0))]
                                                       (if (<= at 1.0) (* duration at) at))))
                                    frames)]))
                      (into {}))]
      (animation/timeline {:duration-ms duration :tracks tracks}))))

(defn spawn! [runtime template-id owner params now-ms]
  (let [template (get @(:templates runtime) template-id)]
    (when-not template (throw (ex-info "unknown effect template" {:template-id template-id})))
    (let [id (swap! (:next-id runtime) inc)
          instance {:id id :template-id template-id :owner owner :params (or params {})
                    :started-ms (long now-ms) :age-ms 0 :alive? true
                    :timeline (compiled-timeline template)}]
      (swap! (:instances runtime) assoc id instance)
      id)))

(defn destroy! [runtime instance-id]
  (swap! (:instances runtime) dissoc instance-id)
  nil)

(defn clear-owner! [runtime owner]
  (swap! (:instances runtime)
         (fn [instances]
           (into {} (remove (fn [[_ instance]] (= owner (:owner instance))) instances))))
  nil)

(defn reload-resources! [runtime generation]
  (reset! (:resource-generation runtime) (long generation))
  generation)

(defn tick! [runtime delta-ms]
  (swap! (:instances runtime)
         (fn [instances]
           (into {}
                 (keep (fn [[id instance]]
                         (let [age (+ (:age-ms instance) (long delta-ms))
                               template (get @(:templates runtime) (:template-id instance))
                               duration (template-duration template)]
                           (when (< age duration)
                             (when-let [timeline (:timeline instance)]
                               (animation/advance! timeline (long delta-ms)))
                             [id (assoc instance :age-ms age)]))))
                 instances)))
  nil)

(defn- command [template instance]
  (let [{:keys [primitive]} template
        {:keys [params age-ms timeline]} instance
        sampled (when timeline (animation/sample timeline))
        params (merge params sampled)
        material (int (or (:material-id params) 0))
        count (int (or (:count params) 1))
        alpha (double (or (:alpha params) 1.0))
        rgba (unchecked-int (or (:rgba params) -1))
        rgba* (bit-or (bit-and rgba 0x00FFFFFF)
                      (bit-shift-left (int (max 0 (min 255 (Math/round (* 255.0 alpha))))) 24))]
    (case primitive
      :beam (RenderCommand$Beam. material count)
      :billboard (RenderCommand$Billboard. (int (or (:texture-id params) 0)) material count
                                            (float (or (:x params) 0.0))
                                            (float (or (:y params) 0.0))
                                            (float (or (:z params) 0.0)))
      :mesh (RenderCommand$Mesh. (int (or (:mesh-id params) 0)) material count)
      :particle (RenderCommand$ParticleBatch. material count
                                              (float (or (:x params) 0.0))
                                              (float (or (:y params) 0.0))
                                              (float (or (:z params) 0.0)))
      :ribbon (RenderCommand$Ribbon. material count)
      :arc (RenderCommand$Ribbon. material count)
      :camera (RenderCommand$CameraContribution.
               (float (or (:fov-delta params) 0.0))
               (float (or (:shake-x params) 0.0))
               (float (or (:shake-y params) 0.0))
               (float (or (:roll params) 0.0)))
      :post-process (RenderCommand$PostProcess. material (float (or (:intensity params) 1.0)))
      (RenderCommand$Quad. 0.0 0.0 (float (or (:width params) 1.0))
                           (float (or (:height params) 1.0)) rgba*))))

(defn extract-passes [runtime]
  (->> @(:instances runtime)
       vals
       (keep (fn [instance]
               (when-let [template (get @(:templates runtime) (:template-id instance))]
                 [(get stage-map (:stage template)) (command template instance)])))
       (group-by first)
       (mapv (fn [[stage entries]]
               (RenderPass. stage (mapv second entries))))))
