(ns cn.li.ac.tutorial.markdown-renderer
  "Convert parsed tutorial markdown text into CGUI text-box component specs.

  Aligns with original AcademyCraft ACMarkdownRenderer behavior:
    - Headings (\"# ...\") → bold, larger font
    - Bold (\"__...__\") → bold font variant
    - Custom tags:
        ![key id=\"...\"] → keybinding display name (from lookup map)
        ![misakaname]    → \"Misaka No.<id>\"
    - Plain text → standard text-box specs

  Returns a vector of pure data specs.  Caller creates CGUI widgets from them."
  (:require [clojure.string :as str]))

;; --- Constants ---

(def default-font-size 8.0)
(def heading-font-size 10.0)
(def default-color 0xFFFFFFFF)
(def line-height 13.0)
(def indent-x 2.0)

;; --- Keybinding lookup ---

(def key-name-lookup
  "Mapping from ![key id=\"...\"] values to display names."
  {"open_data_terminal" "Open Data Terminal"
   "ability_activation"  "Ability Activation"})

;; --- Tag resolution ---

(def ^:private key-tag-re
  #"!\[key\s+id=\"([^\"]+)\"\]")

(def ^:private misaka-tag-re
  #"!\[misakaname\]")

(defn- resolve-inline-tags
  [line misaka-id]
  (-> line
      (str/replace key-tag-re
                   (fn [[_ key-id]]
                     (get key-name-lookup key-id (str "[" key-id "]"))))
      (str/replace misaka-tag-re
                   (if misaka-id
                     (str "Misaka No." misaka-id)
                     "Misaka No.????"))))

;; --- Segment helpers ---

(defn- is-heading-line? [line]
  (str/starts-with? (str/trim line) "#"))

(defn- strip-heading-marker [line]
  (str/trim (str/replace (str/trim line) #"^#+\s*" "")))

(defn- strip-bold-markers [line]
  (str/replace line #"__([^_]+)__" "$1"))

(defn- is-bold-line? [line]
  (boolean (re-find #"__[^_]+__" line)))

(defn- is-empty-line? [line]
  (str/blank? (str/trim line)))

;; --- Main API ---

(defn render-segments
  "Parse raw content text into a vector of segment spec maps.

  Each segment:
    {:text      string
     :font-size float
     :color     int (ARGB)
     :bold?     boolean}

  Args:
    raw-content — parsed markdown content string
    misaka-id   — int or nil, for ![misakaname] resolution"
  ([raw-content] (render-segments raw-content nil))
  ([raw-content misaka-id]
   (let [lines (str/split-lines (or raw-content ""))]
     (loop [remaining lines
            segments  []]
       (if (empty? remaining)
         segments
         (let [line (first remaining)
               rest-lines (rest remaining)]
           (cond
             (is-empty-line? line)
             (recur rest-lines
                    (conj segments
                          {:text "" :font-size default-font-size
                           :color default-color :bold? false}))

             (is-heading-line? line)
             (let [text (-> line strip-heading-marker
                            (resolve-inline-tags misaka-id))]
               (recur rest-lines
                      (conj segments
                            {:text text :font-size heading-font-size
                             :color default-color :bold? true})))

             :else
             (let [text (resolve-inline-tags line misaka-id)
                   bold? (is-bold-line? line)
                   clean-text (if bold? (strip-bold-markers text) text)]
               (recur rest-lines
                      (conj segments
                            {:text clean-text :font-size default-font-size
                             :color default-color :bold? bold?}))))))))))
