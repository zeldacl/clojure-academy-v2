(ns cn.li.vfx.final-engine
  "Headless VFX execution engine for the final typed effect ABI.

   It owns effect instances and lifecycle/state transitions. Rendering and
   network delivery are ports: replication produces packets, while the
   returned render batches are consumed by a client renderer later."
  (:require [cn.li.vfx.effect-schema :as schema]
            [cn.li.vfx.replication :as replication]
            [cn.li.vfx.expr :as expr]))

(expr/register-op! :vfx/ring-point
                   (fn [[center radius index points] _]
                     (let [[x y z] (expr/vec3-components center)
                           angle (* 2.0 Math/PI (/ (double index) (max 1.0 (double points))))
                           r (double radius)]
                       {:vec3 [(+ (double x) (* r (Math/cos angle)))
                               (double y)
                               (+ (double z) (* r (Math/sin angle)))]})))

(def ^:const max-instances 4096)
(def ^:const max-ticks 72000)

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- descriptor [runtime effect-id]
  (schema/descriptor (:catalog @runtime) effect-id))

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

(defn- graph-ref [reference context]
  (let [[scope key & path] reference
        root (case scope :input (:params context) :state (:state context)
               :local (:locals context) :frame (:frame context) nil)]
    (if (= :local scope)
      (if (seq path) (get-in (get root key) path) (get root key))
      (get-in root (into [key] path)))))

(defn- graph-value [value context]
  (cond
    (and (map? value) (vector? (:ref value))) (graph-ref (:ref value) context)
    (and (map? value) (keyword? (:expr value)))
    (expr/evaluate (:expr value)
                   (mapv #(graph-value % context) (:args value))
                   (long (:seed context)))
    (and (map? value) (contains? value :from) (contains? value :to))
    (let [t (double (or (:progress context) 0.0))
          from (double (or (graph-value (:from value) context) 0.0))
          to (double (or (graph-value (:to value) context) 0.0))]
      (+ from (* t (- to from))))
    (map? value) (into {} (map (fn [[k v]] [k (graph-value v context)]) value))
    (vector? value) (mapv #(graph-value % context) value)
    :else value))

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
      :vfx/ring [{:operation :draw-batch :stage :world :primitive :line
                  :geometry {:kind :ring :center (graph-value (:center node) context)
                             :radius (graph-value (or (:radius node) 0.0) context)
                             :segments (long (or (graph-value (:segments node) context) 16))}
                  :material {:color (graph-value (:color node) context)
                             :alpha (double (or (:alpha context) 1.0))}}]
      :vfx/beam [{:operation :draw-batch :stage :world :primitive :line
                  :geometry {:kind :beam :start (graph-value (:start node) context)
                             :end (graph-value (:end node) context)
                             :radius (graph-value (:radius node) context)}
                  :material (graph-value (:material node) context)}]
      :vfx/ray-beam [{:operation :draw-batch :stage :world :primitive :line
                      :geometry {:kind :beam :start (graph-value (:start node) context)
                                 :end (graph-value (:end node) context)
                                 :radius (double (or (graph-value (:radius node) context) 0.02))}
                      :material (graph-value (:style node) context)}]
      :vfx/line [{:operation :draw-batch :stage :world :primitive :line
                  :geometry (graph-value (or (:geometry node) node) context)
                  :material (graph-value (:material node) context)}]
      :vfx/quad [{:operation :draw-batch :stage :world :primitive :quad
                  :geometry (graph-value (or (:geometry node) node) context)
                  :material (graph-value (:material node) context)}]
      :vfx/emitter [{:operation :draw-batch :stage :particles :primitive :quad
                    :geometry {:kind :emitter :anchor (graph-value (:anchor node) context)
                               :rate-per-tick (graph-value (:rate-per-tick node) context)
                               :limit (graph-value (:limit node) context)}
                    :material {:particle (graph-value (:particle node) context)}}]
      :vfx/audio [{:operation :audio :stage :audio
                   :sound-id (graph-value (:sound-id node) context)
                   :volume (graph-value (:volume node) context)
                   :pitch (graph-value (:pitch node) context)}]
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
        [{:operation :draw-batch :stage :world :primitive :quad
          :geometry {:kind :typed-vfx :component component
                     :fields (graph-value (dissoc node :component) context)}
          :material {:variant :typed-vfx}}]
        []))))

(defn sample-graph [descriptor params state age seed]
  (sample-node (:control-graph descriptor)
               {:params params :state state :age (long age)
                :duration (or (get params :duration-ticks)
                              (get-in descriptor [:control-graph :duration-ticks]) 1)
                :seed (long seed) :locals {}}))

(defn create-runtime
  [{:keys [catalog replication-service seed-source]
    :or {seed-source (fn [_] 0)}}]
  (when-not (map? catalog) (fail "VFX runtime requires catalog" {}))
  (atom {:catalog catalog
         :replication replication-service
         :seed-source seed-source
         :next-handle 1
         :instances {}
         :outbox []}))

(defn- allocate-handle [runtime]
  (let [handle (:next-handle @runtime)]
    (swap! runtime update :next-handle inc)
    handle))

(defn- render-batches [instance]
  (let [{:keys [descriptor params state age seed handle]} instance]
    (let [ops (if (:control-graph descriptor)
                (sample-graph descriptor params state age seed)
                (mapv (fn [primitive]
                        {:operation :draw-batch :stage :world :primitive primitive
                         :handle handle :effect-id (:id descriptor)
                         :age-ticks age :params params})
                      (:primitives descriptor)))]
      (mapv #(assoc % :handle handle :effect-id (:id descriptor) :age-ticks age) ops))))

(defn- mapvcat [f coll]
  (vec (mapcat f coll)))

(defn spawn!
  [runtime effect-id {:keys [owner world-id anchor params] :as request}]
  (let [descriptor (descriptor runtime effect-id)
        _ (when-not descriptor
            (fail "unknown VFX effect" {:effect-id effect-id}))
        local-handle (allocate-handle runtime)
        params (validate-params descriptor params)
        replication-result (when-let [service (:replication @runtime)]
                             (replication/spawn! service effect-id
                                                  (assoc request :seed local-handle
                                                         :params params)))
        handle (or (:handle replication-result) local-handle)
        instance {:handle handle :owner owner :world-id world-id :anchor anchor
                  :descriptor descriptor :params params :age 0
                  :state (or (:state-slots descriptor) {})
                  :seed ((:seed-source @runtime) local-handle)}]
    (when (>= (count (:instances @runtime)) max-instances)
      (fail "VFX instance budget exceeded" {:limit max-instances}))
    (swap! runtime assoc-in [:instances handle] instance)
    {:handle handle :packet (:packet replication-result)
     :render (render-batches instance)}))

(defn update!
  [runtime handle params]
  (let [instance (or (get-in @runtime [:instances handle])
                     (fail "unknown VFX handle" {:handle handle}))
        descriptor (:descriptor instance)
        next-params (validate-params descriptor (merge (:params instance) params))
        result (assoc instance :params next-params)]
    (swap! runtime assoc-in [:instances handle] result)
    {:handle handle
     :packet (when-let [service (:replication @runtime)]
               (:packet (replication/update! service handle next-params)))
     :render (render-batches result)}))

(defn destroy!
  [runtime handle]
  (let [instance (get-in @runtime [:instances handle])]
    (when instance
      (swap! runtime update :instances dissoc handle)
      (when-let [service (:replication @runtime)]
        (replication/destroy! service handle))
      {:handle handle :destroyed? true})))

(defn tick!
  [runtime]
  (let [expired (atom [])]
    (swap! runtime update :instances
           (fn [instances]
             (into {}
                   (keep (fn [[handle instance]]
                           (let [age (inc (long (:age instance)))
                                 duration (get (:params instance) :duration-ticks)
                                 expired? (and (= :transient (get-in instance [:descriptor :lifecycle]))
                                               duration (>= age (long duration)))]
                             (if expired?
                               (do (swap! expired conj handle) nil)
                               [handle (assoc instance :age (min max-ticks age))]))))
                   instances)))
    (doseq [handle @expired] (destroy! runtime handle))
    {:expired (vec @expired)
     :render (mapvcat render-batches (vals (:instances @runtime)))}))

(defn snapshot [runtime]
  (select-keys @runtime [:instances :outbox]))
