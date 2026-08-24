(ns cn.li.vfx.final-engine
  "Headless VFX execution engine for the final typed effect ABI.

   It owns effect instances and lifecycle/state transitions. Rendering and
   network delivery are ports: replication produces packets, while the
   returned render batches are consumed by a client renderer later."
  (:require [cn.li.vfx.effect-schema :as schema]
            [cn.li.vfx.replication :as replication]))

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
  (let [{:keys [descriptor params age]} instance]
    (mapv (fn [primitive]
            {:handle (:handle instance)
             :effect-id (:id descriptor)
             :primitive primitive
             :age-ticks age
             :params params})
          (:primitives descriptor))))

(defn- mapvcat [f coll]
  (vec (mapcat f coll)))

(defn spawn!
  [runtime effect-id {:keys [owner world-id anchor params] :as request}]
  (let [descriptor (descriptor runtime effect-id)
        local-handle (allocate-handle runtime)
        params (validate-params descriptor params)
        replication-result (when-let [service (:replication @runtime)]
                             (replication/spawn! service effect-id
                                                  (assoc request :seed local-handle
                                                         :params params)))
        handle (or (:handle replication-result) local-handle)
        instance {:handle handle :owner owner :world-id world-id :anchor anchor
                  :descriptor descriptor :params params :age 0
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
