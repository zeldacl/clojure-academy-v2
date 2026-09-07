(ns cn.li.ac.vfx.fx-catalog-v3
  "Directory-backed V3 VFX catalog. One document is one public effect;
   there is no manifest or source indirection. Runtime compilation is lazy,
   but every document is validated at assembly so editor errors are surfaced
   before a client accepts a signal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.node.api :as node-api]))

(defn- classloader []
  (or (.getContextClassLoader (Thread/currentThread))
      (clojure.lang.RT/baseLoader)))

(defn- names-from-url [root ^java.net.URL url]
  (case (.getProtocol url)
    "file"
    (let [dir (io/file url) base (.toPath ^java.io.File dir)]
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

(defn resource-names [root]
  (let [loader ^java.lang.ClassLoader (classloader)]
    (->> (enumeration-seq (.getResources loader root))
         (mapcat #(names-from-url root %))
         distinct sort vec)))

(defn- read-resource [resource]
  (let [url (io/resource resource)]
    (when-not url (throw (ex-info "V3 VFX resource not found" {:resource resource})))
    (binding [*read-eval* false] (read-string (slurp url)))))

(defn- user-types [document]
  (into {} (map (fn [[key spec]] [key (:type spec)])) (:inputs document)))

(defn- read-effect! [resource]
  (let [document (read-resource resource)]
    (node-api/validate-document! document)
    (when-not (= :vfx (node-api/document-kind document))
      (throw (ex-info "V3 VFX catalog contains non-VFX document"
                      {:resource resource :schema (:schema document)})))
    {:resource resource
     :id (:id document)
     :document document
     :user-types (user-types document)
     :emitters (:emitters document)
     :lifecycle (get-in document [:lifecycle :mode])}))

(defn assemble
  ([] (assemble {}))
  ([{:keys [resource-root] :or {resource-root "ac/vfx-v3"}}]
   (let [resources (resource-names resource-root)
         effects (mapv read-effect! resources)
         ids (map :id effects)]
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "V3 VFX ids must be unique" {:ids ids})))
     {:effects effects
      :by-id (into {} (map (juxt :id identity)) effects)
      :resource-count (count resources)})))
