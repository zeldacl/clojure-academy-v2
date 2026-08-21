(ns cn.li.combat.beam
  "Generic beam host implementation.

  Beam policy comes from EDN.  This namespace owns only neutral geometry,
  bounded batching and capability orchestration; platform queries/actions are
  reached through the injected HostTable relay."
  (:require [cn.li.combat.host :as host]
            [cn.li.mcmod.runtime.effect-contract :as effect-contract])
  (:import [cn.li.mcmod.runtime.effect ExecutionFrame HostTable]))

(set! *warn-on-reflection* true)

(defn- point [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (mapv double (:vec3 value))
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (and (vector? value) (= 3 (count value))) (mapv double value)
    :else nil))

(defn- blocks-for-sample
  [query! owner world-id [sx sy sz] [dx dy dz] length step]
  (or (query! {:capability :block/select
               :owner owner :world-id world-id
               :shape {:type :line :start [sx sy sz]
                       :direction [dx dy dz] :length length :step step}
               :projection [:position :hardness :block-id]
               :limit 4096}) []))

(defn trace!
  [^HostTable host capability-order ^ExecutionFrame frame request]
  (let [{:keys [owner world-id origin trace-origin direction length visual-length radius
                query-radius entity-limit damage damage-type block-policy
                reflection-policy step]} request
        origin (point origin)
        trace-origin (or (point trace-origin) origin)
        direction (point direction)
        length (double (or length 0.0))
        visual-length (double (or visual-length length))
        radius (double (or radius 0.0))
        query-radius (double (or query-radius length))
        step (double (or step 0.9))
        entity-limit (max 0 (min 256 (long (or entity-limit 256))))
        query! (fn [query]
                 (host/invoke-query-capability!
                  host capability-order (:capability query)
                  (effect-contract/query-request query) frame))]
    (when (and origin direction world-id (pos? length) (pos? entity-limit))
      (let [[ox oy oz] origin
            [tx ty tz] trace-origin
            [dx dy dz] direction
            dlen (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
            [dx dy dz] (if (> dlen 1.0e-8)
                         [(/ dx dlen) (/ dy dlen) (/ dz dlen)]
                         [0.0 0.0 1.0])
            raw-entities (query! {:capability :entity/select
                                  :owner owner :world-id world-id
                                  :shape {:type :sphere :center trace-origin
                                          :radius query-radius}
                                  :projection [:id :type :position :eye-height]
                                  :limit entity-limit})
            entities (->> (or raw-entities [])
                          (filter map?)
                          (remove #(= (str owner) (str (:id %))))
                          (keep (fn [entity]
                                  (let [id (:id entity)
                                        p (point (:position entity))
                                        [px py pz] (or p [ox oy oz])
                                        py (+ py (double (or (:eye-height entity) 0.0)))
                                        vx (- px tx) vy (- py ty) vz (- pz tz)
                                        forward (+ (* vx dx) (* vy dy) (* vz dz))
                                        rx (- vx (* forward dx))
                                        ry (- vy (* forward dy))
                                        rz (- vz (* forward dz))
                                        radial (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))]
                                    (when (and (<= 0.0 forward length)
                                               (<= radial (* radius 1.2)))
                                      (let [falloff (+ 0.2 (* 0.8
                                                                    (- 1.0
                                                                       (max 0.0
                                                                            (min 1.0
                                                                                 (/ radial (max length 1.0e-6)))))))
                                            reflection (when reflection-policy
                                                         (query! {:capability :interaction/resolve
                                                                  :owner owner :world-id world-id
                                                                  :target id
                                                                  :visual-origin origin
                                                                  :visual-direction [dx dy dz]
                                                                  :kind :vector-reflection
                                                                  :policy reflection-policy}))]
                                        (merge {:id (str id)
                                                :type (:type entity)
                                                :position {:x px :y py :z pz}
                                                :forward-distance forward
                                                :radial-distance radial
                                                :damage (* (double (or damage 0.0)) falloff)
                                                :damage-type (or damage-type :generic)}
                                               (or reflection {})))))))
                          (sort-by :forward-distance)
                          (take entity-limit)
                          vec)
            horizontal (Math/sqrt (+ (* dx dx) (* dz dz)))
            ux (if (> horizontal 1.0e-8) (/ (- dz) horizontal) 1.0)
            uz (if (> horizontal 1.0e-8) (/ dx horizontal) 0.0)
            vx (* dy uz)
            vy (- (* dz ux) (* dx uz))
            vz (- (* dy ux))
            offsets [-1.0 0.0 1.0]
            samples (for [a offsets b offsets]
                      [(+ ox (* radius 0.5 a ux) (* radius 0.5 b vx))
                       (+ oy (* radius 0.5 b vy))
                       (+ oz (* radius 0.5 a uz) (* radius 0.5 b vz))])
            samples (mapv (fn [[sx sy sz]]
                            [(+ tx (- sx ox))
                             (+ ty (- sy oy))
                             (+ tz (- sz oz))])
                          samples)
            blocks (->> samples
                        (mapcat #(blocks-for-sample query! owner world-id %
                                                     [dx dy dz] length step))
                        (filter map?)
                        (take (long (or (:limit block-policy) 4096)))
                        vec)]
        {:performed? true
         :start {:x ox :y oy :z oz}
         :end {:x (+ ox (* dx (min length visual-length)))
               :y (+ oy (* dy (min length visual-length)))
               :z (+ oz (* dz (min length visual-length)))}
         :entities entities
         :blocks blocks}))))
