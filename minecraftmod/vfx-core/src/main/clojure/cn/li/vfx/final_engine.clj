(ns cn.li.vfx.final-engine
  "Headless VFX graph sampling for the final typed effect ABI.

   Sampling is a pure function of (descriptor, params, state, age, seed):
   it owns no effect instances and performs no network delivery. Instance
   lifecycle and replication belong to the client-side runtime
   (cn.li.vfx.final-client), the only production consumer of sample-graph."
  (:require [cn.li.node.expr :as expr]
            [cn.li.node.kernel :as kernel]))

(def ^:private expression-ops
  {:vfx/ring-point
   (fn [[center radius index points] _]
     (let [[x y z] (expr/vec3-components center)
           angle (* 2.0 Math/PI (/ (double index) (max 1.0 (double points))))
           r (double radius)]
       {:vec3 [(+ (double x) (* r (Math/cos angle)))
               (double y)
               (+ (double z) (* r (Math/sin angle)))]}))})

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- validate-params [descriptor params]
  (let [declared (into {} (map (juxt :name identity) (:parameters descriptor)))
        params (or params {})
        unknown (seq (remove #(contains? declared %) (keys params)))]
    (when unknown
      (fail "unknown VFX parameter" {:effect-id (:id descriptor) :parameters unknown}))
    (doseq [{:keys [name default]} (:parameters descriptor)]
      (when (and (not (contains? params name)) (nil? default))
        (fail "missing VFX parameter" {:effect-id (:id descriptor) :parameter name})))
    (merge (into {} (keep (fn [{:keys [name default]}]
                            (when (some? default) [name default]))
                          (:parameters descriptor)))
           params)))

(kernel/defresolver graph-value context
  {:scopes {:input (:params context) :state (:state context)
            :local (:locals context) :frame (:frame context)}
   :local :local
   :seed (long (:seed context))
   :extras (:expression-ops context)
   :coll #{:map :vector}
   :lerp? true})

(defn- fade-factor [node age]
  (let [from (double (or (:from-tick node) 0))
        to (double (or (:to-tick node) (inc from)))
        p (max 0.0 (min 1.0 (/ (- (double age) from) (max 1.0 (- to from)))))]
    (+ (double (or (:from-alpha node) 1.0))
       (* p (- (double (or (:to-alpha node) 0.0))
               (double (or (:from-alpha node) 1.0)))))))

(declare sample-node)
(defn- sample-node [node context]
  (let [component (:component node)
        context (assoc context :progress
                       (max 0.0 (min 1.0 (/ (double (:age context))
                                            (max 1.0 (double (or (:duration context) 1)))))))]
    (case component
      :vfx/let (let [locals (reduce-kv (fn [m k v] (assoc m k (graph-value v (assoc context :locals m))))
                                       (:locals context) (:bindings node))]
                 (sample-node (:child node) (assoc context :locals locals)))
      :vfx/repeat (vec (mapcat (fn [index]
                                 (sample-node (:body node)
                                              (assoc context :locals (assoc (:locals context)
                                                                            (:index-as node) index))))
                               (range (long (or (graph-value (:count node) context) 0)))))
      :vfx/timeline (vec (mapcat #(when (<= (long (or (:at %) 0)) (long (:age context)))
                                   (sample-node (:node %) context)) (:children node)))
      :vfx/group (vec (mapcat #(sample-node % context) (:children node)))
      :vfx/branch (sample-node (if (graph-value (:when node) context) (:then node) (:else node)) context)
      :vfx/fade (let [alpha (fade-factor node (:age context))]
                  (mapv #(assoc-in % [:material :alpha]
                                   (* alpha (double (or (get-in % [:material :alpha]) 1.0))))
                        (sample-node (:child node) context)))
      :vfx/ring [{:operation :draw-batch :stage :world-after-translucent :primitive :line
                  :geometry {:kind :ring :center (graph-value (:center node) context)
                             :radius (graph-value (or (:radius node) 0.0) context)
                             :segments (long (or (graph-value (:segments node) context) 16))}
                  :material {:color (graph-value (:color node) context)
                             :alpha (double (or (:alpha context) 1.0))}}]
      :vfx/beam [{:operation :draw-batch :stage :world-after-translucent :primitive :line
                  :geometry {:kind :beam :start (graph-value (:start node) context)
                             :end (graph-value (:end node) context)
                             :radius (graph-value (:radius node) context)}
                  :material (graph-value (:material node) context)}]
      :vfx/ray-beam [{:operation :draw-batch :stage :world-after-translucent :primitive :line
                      :geometry {:kind :beam :start (graph-value (:start node) context)
                                 :end (graph-value (:end node) context)
                                 :radius (double (or (graph-value (:radius node) context) 0.02))}
                      :material (graph-value (:style node) context)}]
      :vfx/line [{:operation :draw-batch :stage :world-after-translucent :primitive :line
                  :geometry (graph-value (or (:geometry node) node) context)
                  :material (graph-value (:material node) context)}]
      :vfx/quad [{:operation :draw-batch :stage :world-after-translucent :primitive :quad
                  :geometry (graph-value (or (:geometry node) node) context)
                  :material (graph-value (:material node) context)}]
      :vfx/emitter [{:operation :draw-batch :stage :world-translucent :primitive :quad
                    :geometry {:kind :emitter :anchor (graph-value (:anchor node) context)
                               :rate-per-tick (graph-value (:rate-per-tick node) context)
                               :limit (graph-value (:limit node) context)
                               :particle (graph-value (:particle node) context)}
                    :material {:particle (graph-value (:particle node) context)}}]
      :vfx/audio [{:operation :audio :stage :audio
                   :sound-id (graph-value (:sound-id node) context)
                   :volume (graph-value (:volume node) context)
                   :pitch (graph-value (:pitch node) context)}]
      :vfx/audio-one-shot [{:operation :audio :stage :audio
                            :sound-id (graph-value (:sound-id node) context)
                            :volume (graph-value (:volume node) context)
                            :pitch (graph-value (:pitch node) context)
                            :position (graph-value (:position node) context)}]
      :vfx/audio-loop [{:operation :audio :stage :audio
                        :sound-id (graph-value (:sound-id node) context)
                        :volume (graph-value (:volume node) context)
                        :pitch (graph-value (:pitch node) context)
                        :position (graph-value (:position node) context)}]
      :vfx/camera [{:operation :camera-fov :stage :camera
                   :value (graph-value (:value node) context)
                   :camera-operation (:operation node)}]
      :vfx/camera-fov [{:operation :camera-fov :stage :camera
                       :value (graph-value (:value node) context)}]
      :vfx/camera-shake [{:operation :camera-shake :stage :camera
                         :amplitude (graph-value (:amplitude node) context)
                         :duration (graph-value (:duration node) context)}]
      :vfx/post-process [{:operation :post-process :stage :post
                         :effect (graph-value (:effect node) context)}]
      ;; Every final VFX component must remain observable.  A newly introduced
      ;; typed component is represented as a quad draw op with its evaluated
      ;; fields; this is an explicit neutral ABI payload, not a silent drop or
      ;; a compatibility interpreter.  Presentation may specialize it later.
      (if (and (keyword? component) (= "vfx" (namespace component)))
        [{:operation :draw-batch :stage :world-after-translucent :primitive :quad
          :geometry {:kind :typed-vfx :component component
                     :fields (graph-value (dissoc node :component) context)}
          :material {:variant :typed-vfx}}]
        []))))

(defn sample-graph [descriptor params state age seed]
  (sample-node (:control-graph descriptor)
               {:params params :state state :age (long age)
                :duration (or (get params :duration-ticks)
                              (get-in descriptor [:control-graph :duration-ticks]) 1)
                :seed (long seed) :locals {}
                :expression-ops expression-ops}))

