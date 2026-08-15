(ns cn.li.combat.compiler
  "Deterministic compiler for the Clojure combat graph data model."
  (:require [cn.li.combat.registry :as registry])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private built-in-ops
  #{:sequence :repeat :branch :require :require-session :query :damage :vfx
    :world-effect :domain-event :patch :phase :session-patch})

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)])) value)
    (vector? value) (mapv canonical value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (seq? value) (mapv canonical value)
    (ifn? value) (str (class value))
    :else value))

(defn content-hash [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (canonical value)) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- walk! [node path nodes seen]
  (when-not (map? node) (fail "combat program node must be a map" {:path path :node node}))
  ;; Use identity along the current recursion stack.  Equality is not a
  ;; cycle: two independent phase branches may legitimately contain the same
  ;; immutable node map.
  (when (some #(identical? % node) @seen)
    (fail "combat program contains a cycle" {:path path}))
  (swap! seen conj node)
  (let [op (:op node)]
    (when-not (or (contains? built-in-ops op) (= op :node))
      (fail "unknown combat program op" {:path path :op op}))
    (case op
      :sequence (do
                  (when-not (vector? (:steps node))
                    (fail "sequence steps must be a vector" {:path path}))
                  (doseq [[idx child] (map-indexed vector (:steps node))]
                    (walk! child (conj path :steps idx) nodes seen)))
      :repeat (do
                (when-not (and (integer? (:count node))
                               (<= 0 (long (:count node)) 128))
                  (fail "repeat count must be a bounded integer <= 128"
                        {:path path :count (:count node)}))
                (when-not (vector? (:steps node))
                  (fail "repeat steps must be a vector" {:path path}))
                (doseq [[idx child] (map-indexed vector (:steps node))]
                  (walk! child (conj path :steps idx) nodes seen)))
      :phase (doseq [[phase child] (select-keys node [:start :pulse :release :abort :passive])
                     :when child]
               (walk! child (conj path phase) nodes seen))
      :session-patch (when-not (vector? (:entries node))
                       (fail "session-patch entries must be a vector"
                             {:path path :entries (:entries node)}))
      :branch (do (walk! (:then node) (conj path :then) nodes seen)
                  (when (:else node) (walk! (:else node) (conj path :else) nodes seen)))
      :node (do
              (when-not (keyword? (:node-id node))
                (fail "custom combat node requires :node-id" {:path path}))
              (when-not (contains? nodes (:node-id node))
                (fail "combat program references an unregistered custom node"
                      {:path path :node-id (:node-id node)})))
      nil)
    (swap! seen pop)
    node))

(defn- expand-activation [ability]
  (let [activation (:activation ability)]
    (when-not (#{:instant :session :toggle :passive} activation)
      (fail "invalid combat activation" {:ability (:id ability) :activation activation}))
    (assoc ability :activation activation)))

(defn compile-ability [{:keys [id program] :as ability}]
  (when-not (keyword? id) (fail "ability id must be a keyword" {:ability ability}))
  (walk! program [:program] (registry/nodes) (atom []))
  (let [ability (expand-activation ability)
        hash (content-hash (dissoc ability :compiled?))
        nodes (tree-seq map? #(concat (:steps %) [(:then %) (:else %)]) program)
        nodes (filter map? nodes)]
    (assoc ability :compiled? true :program-hash hash
           :slots {:double 0 :long 0 :boolean 0 :object 0}
           :budget {:nodes (count nodes)
                    :queries (count (filter #(= :query (:op %)) nodes))
                    :outputs (count (filter #(contains? #{:world-effect :damage :vfx :domain-event}
                                                        (:op %)) nodes))
                    :max-execution (reduce + 0 (map #(if (= :repeat (:op %))
                                                        (long (:count %)) 1) nodes))})))

(defn compile-all! []
  (let [compiled (into {} (map (fn [[id ability]] [id (compile-ability ability)])
                               (registry/abilities)))
    catalog {:schema-version 1
                 :nodes (registry/nodes)
                 :abilities compiled
                 :providers (registry/providers)}]
    (assoc catalog :content-hash (content-hash catalog))))

(defn compiled-catalog [catalog] (:abilities catalog))
