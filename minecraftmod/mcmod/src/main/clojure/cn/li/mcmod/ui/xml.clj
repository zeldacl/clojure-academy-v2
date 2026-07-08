(ns cn.li.mcmod.ui.xml
  "XML layout loader — parses new-schema XML into node-spec data.
   Element name = node kind; attributes = static props; <template> = reusable.
   Single parse path, schema validation at load time."
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.mcmod.ui.spec :as ui-spec]))

;; ============================================================================
;; Attribute parsers
;; ============================================================================

(defn- parse-int [s default]
  (if s
    (try (Long/parseLong (str/trim s)) (catch Exception _ default))
    default))

(defn- parse-double [s default]
  (if s
    (try (Double/parseDouble (str/trim s)) (catch Exception _ default))
    default))

(defn- parse-color [s]
  "Parse #RRGGBB or #RRGGBBAA hex color string into ARGB long.
   Returns nil if not a valid color."
  (when (and s (str/starts-with? (str/trim s) "#"))
    (try
      (let [hex (str/replace (str/trim s) #"^#" "")
            len (count hex)]
        (Long/parseLong (if (= len 6) (str "FF" hex) hex) 16))
      (catch Exception _ nil))))

(defn- parse-pos [s]
  "Parse 'x,y' into {:x :y}."
  (if s
    (let [parts (str/split (str/trim s) #"\s*,\s*")]
      {:x (parse-double (first parts) 0.0)
       :y (parse-double (second parts) 0.0)})
    {:x 0.0 :y 0.0}))

(defn- parse-size [s]
  "Parse 'w,h' into {:w :h}."
  (if s
    (let [parts (str/split (str/trim s) #"\s*,\s*")]
      {:w (parse-double (first parts) 0.0)
       :h (parse-double (second parts) 0.0)})
    {:w 0.0 :h 0.0}))

(defn- parse-align [s]
  "Parse 'center,middle' into {:align-w :center :align-h :middle}."
  (if s
    (let [parts (str/split (str/trim s) #"\s*,\s*")
          w (keyword (or (first parts) "left"))
          h (keyword (or (second parts) "top"))]
      {:align-w w :align-h h})
    {:align-w :left :align-h :top}))

;; ============================================================================
;; Kind → spec conversion
;; ============================================================================

(def ^:private kind-attrs
  "Attribute mapping for each kind. Maps XML attr name → spec key + parse fn."
  {:group {:pos parse-pos :size parse-size :scale (fn [s] (or (parse-double s nil) 1.0))
           :align parse-align :clip? (fn [s] (= "true" s)) :z (fn [s] (parse-double s 0.0))}
   :box   {:pos parse-pos :size parse-size :fill parse-color :outline parse-color
           :outline-width (fn [s] (parse-double s 0.0)) :tint parse-color
           :hover-tint (fn [s] (parse-double s 0.0)) :z (fn [s] (parse-double s 0.0))}
   :text  {:pos parse-pos :text str :font-size (fn [s] (parse-double s 14.0))
           :color parse-color :font str :z (fn [s] (parse-double s 0.0))
           :editable? (fn [s] (= "true" s))}
   :image {:pos parse-pos :size parse-size :src str :alpha (fn [s] (parse-double s 1.0))
           :z (fn [s] (parse-double s 0.0))}
   :progress {:pos parse-pos :size parse-size :z (fn [s] (parse-double s 0.0))}
   :list   {:pos parse-pos :size parse-size :spacing (fn [s] (parse-double s 4.0))
            :template str :z (fn [s] (parse-double s 0.0))}})

(defn- parse-elem-attrs [kind attrs]
  "Convert XML attributes to spec props based on kind's attr mapping."
  (let [mapping (get kind-attrs kind)]
    (reduce-kv (fn [m k v]
                 (if-let [parse-fn (get mapping (keyword k))]
                   (let [parsed (parse-fn v)]
                     (if (map? parsed)
                       (merge m parsed)
                       (assoc m (keyword k) parsed)))
                   m))
               {} attrs)))

;; ============================================================================
;; Templates (forward declare needed for circular parse-ui-element ref)
;; ============================================================================

(declare parse-ui-element)

(defn- collect-templates [children]
  "Extract <template> elements from children, returning {name spec} map."
  (reduce (fn [acc child]
            (if (= :template (:tag child))
              (assoc acc (get-in child [:attrs :name])
                     (parse-ui-element child {}))
              acc))
          {} children))

(defn- non-template-children [children]
  (remove #(= :template (:tag %)) children))

;; ============================================================================
;; Recursive parser
;; ============================================================================

(defn- parse-ui-children [children templates]
  (when (seq children)
    (mapv (fn [child]
            (if (= :template (:tag child))
              nil  ;; templates handled separately
              (let [spec (parse-ui-element child templates)]
                spec)))
          (non-template-children children))))

(defn- parse-ui-element [elem templates]
  "Parse a single XML element into node-spec {:kind :id :props :children}.
   :list elements with a template=\"name\" attr get the resolved template SPEC
   stored in props :template (consumed by ui.core/list-set! for instantiation)."
  (let [tag (:tag elem)
        attrs (:attrs elem)
        content (:content elem)
        kind (if (or (= :Ui tag) (= :template tag)) :group (keyword (name tag)))]
    (if (and (:template attrs) (not= kind :list))
      ;; Non-list element referencing a template: clone the template spec
      (if-let [tmpl (get templates (:template attrs))]
        (merge tmpl {:id (:id attrs)})
        (throw (ex-info (str "Template not found: " (:template attrs)) {})))
      ;; Regular element (or :list — template resolved into props below)
      (let [props (parse-elem-attrs kind attrs)
            props (if-let [id-attr (:id attrs)]
                    (assoc props :id id-attr)
                    props)
            ;; :list — replace template NAME string with the resolved template SPEC
            props (if (and (= kind :list) (:template attrs))
                    (if-let [tmpl (get templates (:template attrs))]
                      (assoc props :template tmpl)
                      (throw (ex-info (str "List template not found: " (:template attrs)) {})))
                    props)]
        {:kind kind
         :id (:id attrs (str (gensym "node-")))
         :props props
         :children (vec (keep #(parse-ui-element % templates) (non-template-children content)))}))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn parse-spec [^String xml-str]
  "Parse XML string into node-spec map. Top-level <Ui> → spec tree."
  (let [root (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-str "UTF-8")))
        templates (collect-templates (:content root))]
    (parse-ui-element root templates)))

(defn load-spec
  "Load XML from classpath resource and parse into node-spec.
   Schema-validated at load time (never on the render path).
   resource-path: e.g. \"my_mod:guis/rework/page_inv.xml\"
   Falls back to assets/<modid>/ prefix when the bare path is not on classpath."
  [resource-path]
  (let [path (str/replace resource-path #"^[^:]+:" "")
        res (or (io/resource path)
                (io/resource (str "assets/my_mod/" path)))]
    (when-not res
      (throw (ex-info (str "XML resource not found: " resource-path) {:path path})))
    (let [root (xml/parse res)
          templates (collect-templates (:content root))
          spec (parse-ui-element root templates)]
      (ui-spec/validate! spec)
      spec)))
