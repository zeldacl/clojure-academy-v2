(ns cn.li.presentation.compiler.core
  (:require [clojure.edn :as edn]
            [clojure.string :as string])
  (:import [cn.li.presentation.compiler CompiledTemplate TemplateNode TemplateCompileException]
           [cn.li.presentation.core ActionId TemplateId]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def containers #{:stack :flex :grid :scroll :portal})
(def leaves #{:progress :text :textbox :image :skill-wheel :quad :mesh :billboard :modal :button :virtual-list})
(def semantic-roles #{:generic :progress :button :textbox :dialog :list :list-item
                      :image :slot :heading})

(declare ref-name)

(defn- capability-set [value]
  (set (keep #(when (or (keyword? %) (string? %))
                (keyword (ref-name %))) (or value []))))

(defn- get* [m k]
  (or (get m k) (get m (keyword k)) (get m (name k))))
(defn- fail [path message]
  (throw (TemplateCompileException. (str path ": " message))))
(defn- required [m k path]
  (let [value (get* m k)]
    (if (and (or (string? value) (keyword? value))
             (not (string/blank? (if (keyword? value) (name value) value)))) value
        (fail (str path "." (name k)) "required non-empty string"))))

(defn- validate-size! [value path]
  (when (some? value)
    (when-not (or (number? value)
                  (#{:fixed :content :fill} value)
                  (and (vector? value) (= :fraction (first value))
                       (number? (second value))
                       (<= 0.0 (double (second value)) 1.0))
                  (and (map? value) (= :fraction (:kind value))
                       (number? (:value value))
                       (<= 0.0 (double (:value value)) 1.0)))
      (fail path "must be a number, fixed/content/fill, or fraction"))))

(defn- validate-node-properties! [m path]
  (doseq [axis [:width :height :min-width :min-height :max-width :max-height]]
    (validate-size! (get* m axis) (str path "." (name axis))))
  (when (contains? m :aspect-ratio)
    (when-not (and (number? (:aspect-ratio m)) (pos? (:aspect-ratio m)))
      (fail (str path ".aspect-ratio") "must be positive")))
  (when-let [semantics (get* m :semantics)]
    (let [role (get* semantics :role)]
      (when (and role (not (contains? semantic-roles role)))
        (fail (str path ".semantics.role") (str "unsupported role " role))))))
(defn- ref-name [value]
  (cond (keyword? value) (if-let [ns (namespace value)] (str ns "/" (name value)) (name value))
        (string? value) (if (.startsWith ^String value ":") (subs value 1) value)
        :else (str value)))
(defn- compile-node [m path symbols seen bindings actions]
  (let [type (keyword (ref-name (required m :type path)))
        _ (when-not (or (containers type) (leaves type)) (fail (str path ".type") (str "unsupported node type '" type "'")))
        key (ref-name (required m :key path))
        _ (validate-node-properties! m path)
        _ (when (contains? @seen key) (fail (str path ".key") (str "duplicate key '" key "'")))
        _ (swap! seen conj key)
        binding-attrs [:value :items :selected :query]
        binding-refs (keep (fn [attr]
                             (let [value (get* m attr)]
                               (when (and (vector? value) (= :bind (first value)))
                                 [attr (ref-name (second value))]))) binding-attrs)
        _ (doseq [[attr name] binding-refs]
            (let [id (or ((:binding symbols) name)
                         ((:binding symbols) (keyword name)))]
              (when (nil? id) (fail (str path "." (clojure.core/name attr)) (str "unknown binding '" name "'")))
              (swap! bindings assoc
                     (clojure.core/name attr) (int id)
                     name (int id))))
        action-attrs [:on-select :on-activate :on-submit :on-close :on-click]
        action-entry (some (fn [attr]
                            (let [value (get* m attr)]
                              (when (and (vector? value) (= :action (first value)))
                                [attr value]))) action-attrs)
        node-actions (when action-entry
                       (let [[attr value] action-entry
                             action-name (ref-name (second value)) id (or ((:action symbols) action-name)
                                                                          ((:action symbols) (keyword action-name)))]
                         (when (nil? id) (fail (str path "." (clojure.core/name attr)) (str "unknown action '" action-name "'")))
                         (swap! actions assoc action-name (int id))
                         {(clojure.core/name attr) (ActionId. (int id))}))
        semantics (get* m :semantics)
        _ (when (and semantics (not (map? semantics)))
            (fail (str path ".semantics") "must be a map"))
        _ (when (and (true? (get* m :focusable))
                     (not (get* semantics :role)))
            (fail (str path ".semantics.role") "focusable nodes require a semantics role"))
        children-value (get* m :children)
        children (cond
                   (some? children-value)
                   (do (when-not (coll? children-value) (fail (str path ".children") "must be a collection"))
                       (mapv #(do (when-not (map? %) (fail path "child must be a map"))
                                  (compile-node % (str path ".children") symbols seen bindings actions)) children-value))
                   (containers type) (fail (str path ".children") "container requires children")
                   :else [])]
    (TemplateNode. (name type) key children
                   (into {} (map (fn [[attr binding-name]]
                                   [(clojure.core/name attr)
                                    (int (get @bindings (clojure.core/name attr)))]) binding-refs))
                   (or node-actions {})
                   (into {} (remove (comp nil? val)
                                    (select-keys m [:label :rgba :x :y :w :h]))))))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn compile-template [template-id source symbols]
  (let [bindings (atom {}) actions (atom {}) root (compile-node source "root" symbols (atom #{}) bindings actions)]
    (CompiledTemplate. template-id (sha256 source) 1 root @bindings @actions)))

(defn validate-capabilities!
  "Validate content requirements against every selected backend.

   The compiler, not a version renderer, owns this check so a missing feature
   fails at build/reload time instead of silently disappearing on one target.
   `backend-capabilities` is a map of backend id to a collection of keywords."
  [source backend-capabilities]
  (let [required (capability-set (get* source :requires-capabilities))]
    (doseq [[backend capabilities] backend-capabilities]
      (let [missing (sort (seq (remove (capability-set capabilities) required)))]
        (when (seq missing)
          (fail (str "backend." (ref-name backend))
                (str "missing capabilities " (pr-str missing))))))
    true))

(defn compile-template-for
  "Compile a template only when all selected Minecraft backends satisfy its
   declared capability requirements."
  [template-id source symbols backend-capabilities]
  (validate-capabilities! source backend-capabilities)
  (compile-template template-id source symbols))

(defn compile-edn [template-id edn symbols]
  (compile-template template-id (edn/read-string edn) symbols))
