(ns cn.li.ac.ability.skills-catalog-v3
  "Directory-backed AC V3 skill catalog.

   Every public skill is one EDN document. The loader deliberately has no
   manifest/source-id layer: it enumerates `resource-root` and uses the
   document's own `:id` as the stable registration key. Shared behavior is
   represented by module references in the document, not by duplicated
   registration metadata.

   Enumeration works from both an exploded development classpath and a jar,
   which keeps the editor-facing resource layout identical in dev and in the
   packaged mod."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.node.api :as node-api]))

(defn- classloader []
  (or (.getContextClassLoader (Thread/currentThread))
      (clojure.lang.RT/baseLoader)))

(defn- resource-names-from-url [root ^java.net.URL url]
  (case (.getProtocol url)
    "file"
    (let [dir (io/file url)
          base (.toPath ^java.io.File dir)]
      (if (.isDirectory dir)
        (->> (file-seq dir)
             (filter #(and (.isFile ^java.io.File %)
                           (.endsWith (.getName ^java.io.File %) ".edn")))
             (map #(.relativize base (.toPath ^java.io.File %)))
             (map #(str root "/" (str/replace (str %) java.io.File/separator "/")))
             set)
        #{}))

    "jar"
    (let [connection ^java.net.URLConnection (.openConnection url)
          jar ^java.util.jar.JarFile (.getJarFile ^java.net.JarURLConnection connection)
          prefix (if (.endsWith ^String root "/") root (str root "/"))]
      (->> (enumeration-seq (.entries jar))
           (map #(.getName ^java.util.jar.JarEntry %))
           (filter #(and (.startsWith ^String % prefix)
                         (.endsWith ^String % ".edn")
                         (not (.endsWith ^String % "/"))))
           set))

    #{}))

(defn resource-names
  "Return sorted EDN resource paths below `root`.

   `root` is a classpath path such as `ac/skills-v3`; callers should not pass
   a leading slash. Duplicate classpath roots are harmless because names are
   deduplicated before sorting."
  [root]
  (let [loader ^java.lang.ClassLoader (classloader)
        urls (enumeration-seq (.getResources loader root))]
    (->> urls
         (mapcat #(resource-names-from-url root %))
         (filter #(and (.endsWith ^String % ".edn")
                       (not (.endsWith ^String % "/index.edn"))))
         distinct
         sort
         vec)))

(defn- read-resource [resource]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info "AC V3 resource not found" {:resource resource})))
    (binding [*read-eval* false]
      (read-string (slurp url)))))

(defn- read-skill! [resource]
  (let [document (read-resource resource)]
    (node-api/validate-document! document)
    (when-not (= :skill (node-api/document-kind document))
      (throw (ex-info "skill catalog contains a non-skill document"
                      {:resource resource :schema (:schema document)})))
    {:resource resource
     :id (:id document)
     :document document}))

(defn assemble
  "Load and compile every skill under a resource directory.

   Options:
   * `:resource-root` (default `ac/skills-v3`)
   * `:compile-opts` compiler vocabulary/capability/function tables
   * `:mode` `:throw` (default) or `:collect`

   Returns `{:skills [...] :by-id {...} :resource-count n}`. In collect mode
   each item also carries `:diagnostics`, while throw mode fails during
   assembly so an invalid shipped document cannot silently register."
  ([] (assemble {}))
  ([{:keys [resource-root compile-opts mode]
     :or {resource-root "ac/skills-v3"
          compile-opts {}
          mode :throw}}]
   (let [resources (resource-names resource-root)
         skills (mapv (fn [resource]
                        (let [{:keys [id document] :as skill} (read-skill! resource)
                              {:keys [ir diagnostics]}
                              (node-api/compile-skill-document!
                               document compile-opts mode)]
                          (assoc skill
                                 :semantic-digest (node-api/document-semantic-digest document)
                                 :ir ir
                                 :diagnostics diagnostics)))
                      resources)
         ids (map :id skills)]
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "AC V3 skill ids must be unique" {:ids ids})))
     {:skills skills
      :by-id (into {} (map (juxt :id identity)) skills)
      :resource-count (count resources)})))
