(ns cn.li.ac.tutorial.content
  "Runtime markdown loader for tutorial text.

  Reads `assets/my_mod/tutorials/<lang>/<id>.md` from the classpath using
  `clojure.java.io/resource` + `slurp` — the same pattern used by
  `cn.li.mcmod.client.obj/read-obj-data`.

  Supports ![title]/![brief]/![content] section markers and falls back to
  `en_US` when the requested language is unavailable."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; --- Path construction ---

(def ^:private resource-root "assets/my_mod/tutorials")

(def ^:private supported-langs
  "Languages that have tutorial translations available.
  Must match the directory names under tutorials/."
  #{"en_US" "zh_CN"})

(defn- resource-path
  [lang tut-id]
  (str resource-root "/" lang "/" (name tut-id) ".md"))

;; --- Section parsing ---

(def ^:private section-markers
  {"![title]"   :title
   "![brief]"   :brief
   "![content]" :content})

(defn- take-section
  "Extract one section body from lines, starting after the marker line.
  Returns [section-text remaining-lines] or [nil '()]."
  [marker-keyword lines]
  (let [[header & body] lines]
    (if-let [section-kw (section-markers (str/trim header))]
      (let [[sec-lines rest-lines]
            (split-with (fn [line]
                          (not (contains? section-markers (str/trim line))))
                        body)
            text (->> sec-lines
                      (str/join "\n")
                      str/trim)]
        (if (= section-kw marker-keyword)
          [text rest-lines]
          ;; wrong section — recurse into rest
          (take-section marker-keyword (cons (first body) (rest body)))))
      ;; header didn't match a section marker — skip it
      (take-section marker-keyword (rest lines)))))

(defn parse-sections
  "Parse raw markdown text into {:title :brief :content} map.
  Sections are delimited by lines containing exactly:
    ![title]   → :title
    ![brief]   → :brief
    ![content] → :content

  Missing sections default to empty string."
  [raw-text]
  (let [lines (str/split-lines (or raw-text ""))]
    (reduce (fn [acc [section-name section-kw]]
              (let [[text _] (take-section section-kw lines)]
                (assoc acc section-kw (or text ""))))
            {:title "" :brief "" :content ""}
            section-markers)))

;; --- Language resolution ---

(def ^:private fallback-lang "en_US")

(defn- resolve-lang
  "Case-insensitive match `lang` against supported-langs, returning the canonical
  directory name.  Falls back to en_US when unsupported."
  [lang]
  (if-let [canonical (and lang
                         (some #(when (= (str/lower-case %) (str/lower-case lang)) %)
                               supported-langs))]
    canonical
    (do
      (when lang
        (log/debug "Tutorial content: unsupported lang" lang "falling back to" fallback-lang))
      fallback-lang)))

;; --- Content loading ---

(defn load-tutorial-content
  "Load and parse tutorial markdown for `tut-id` in `lang`.

  Args:
    lang   — language code string (e.g. \"en_US\", \"zh_CN\"), nil → en_US
    tut-id — tutorial id keyword (e.g. :welcome, :ores)

  Returns {:title ... :brief ... :content ...} or nil when the resource
  cannot be found."
  ([tut-id] (load-tutorial-content nil tut-id))
  ([lang tut-id]
   (let [resolved-lang (resolve-lang lang)
         path (resource-path resolved-lang tut-id)]
     (try
       (if-let [res (io/resource path)]
         (let [raw (slurp res)
               parsed (parse-sections raw)]
           (log/debug "Loaded tutorial content:" (name tut-id) "from" path)
           parsed)
         (do
           (log/debug "Tutorial content not found:" path)
           nil))
       (catch Exception e
         (log/warn "Failed to load tutorial content" {:path path :error (ex-message e)})
         nil)))))

;; --- Client-side language helper ---

(def ^:private ^:dynamic *current-lang* nil)

(defn install-current-lang-fn!
  "Install a 0-arg function that returns the current Minecraft language string
  (e.g. \"en_us\").  Called from platform client init."
  [lang-fn]
  (alter-var-root #'*current-lang* (constantly lang-fn)))

(defn current-lang
  "Resolve the current Minecraft language setting.
  Returns \"en_US\" when the lang-fn is not installed (e.g. on server side)."
  []
  (or (when-let [f *current-lang*]
        (try (f) (catch Throwable _ fallback-lang)))
      fallback-lang))
