(ns cn.li.vfx.effect-schema
  "Pure VFX effect catalog and parameter ABI."
  (:require [cn.li.node.digest :as digest]))
(def ^:const schema-version 1)
(def mutabilities #{:immutable :per-tick :event})
(def render-primitives #{:line :quad :plasma-body})
(def presentation-ports #{:audio-one-shot :audio-loop :camera-fov :camera-shake :post-process})
(defn parameter [name {:keys [type mutability quantization default] :as spec}]
  (when-not (keyword? name) (throw (ex-info "VFX parameter name must be a keyword" {:name name})))
  (when-not type (throw (ex-info "VFX parameter requires :type" {:name name})))
  (when-not (contains? mutabilities (or mutability :immutable)) (throw (ex-info "unknown VFX parameter mutability" {:name name :mutability mutability})))
  {:name name :type type :mutability (or mutability :immutable) :quantization quantization :default default})
(defn effect-descriptor [{:keys [id lifecycle parameters primitives] :as descriptor}]
  (when-not (keyword? id) (throw (ex-info "VFX effect id must be a keyword" {:id id})))
  (when-not (contains? #{:transient :session :persistent} lifecycle) (throw (ex-info "invalid VFX lifecycle" {:id id :lifecycle lifecycle})))
  (when (> (count parameters) 256) (throw (ex-info "VFX effects support at most 256 parameters" {:id id})))
  (when-not (every? render-primitives (or primitives #{})) (throw (ex-info "unsupported VFX render primitive" {:id id :primitives primitives})))
  (assoc descriptor :schema-version schema-version :parameters (mapv (fn [[name spec]] (parameter name spec)) (sort-by first (or parameters {}))) :primitives (set primitives)))
(defn catalog [descriptors]
  (let [effects (into (sorted-map) (map (fn [descriptor] (let [d (effect-descriptor descriptor)] [(:id d) d])) descriptors))]
    {:schema-version schema-version :effects effects :hash (digest/content-hash effects)}))
(defn descriptor [catalog effect-id]
  (or (get-in catalog [:effects effect-id]) (throw (ex-info "unknown VFX effect" {:effect-id effect-id}))))
(defn diff-mask [parameters before after]
  (let [parameters (vec parameters) changed (keep-indexed (fn [index {:keys [name]}] (when (not= (get before name) (get after name)) index)) parameters) word-count (long (Math/ceil (/ (double (count parameters)) 64.0))) words (reduce (fn [result index] (let [word (quot index 64) bit (mod index 64)] (update result word #(long (bit-or (long %) (long (bit-shift-left 1 bit))))))) (vec (repeat word-count 0)) changed)]
    {:word-count word-count :words words :indices (vec changed)}))
