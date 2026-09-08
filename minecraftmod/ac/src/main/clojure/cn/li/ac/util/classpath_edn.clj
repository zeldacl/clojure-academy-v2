(ns cn.li.ac.util.classpath-edn
  "Enumerate `*.edn` documents under a classpath directory.

   `ClassLoader.getResources(directory)` is empty on Loom runClient (`union:`)
   and on packaged jars that omit directory entries. Enumeration therefore
   also probes a known file under the root and walks siblings via file / jar /
   union / NIO, then falls back to `getResource` on caller-supplied filenames."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net JarURLConnection URL URLDecoder]
           [java.nio.file FileVisitOption Files LinkOption Path Paths]
           [java.util.jar JarEntry JarFile]))

(defn- classloaders []
  (->> [(try (.getContextClassLoader (Thread/currentThread)) (catch Throwable _ nil))
        (try (clojure.lang.RT/baseLoader) (catch Throwable _ nil))
        (try (.getClassLoader (Class/forName "clojure.lang.RT")) (catch Throwable _ nil))
        (try (ClassLoader/getSystemClassLoader) (catch Throwable _ nil))]
       (remove nil?)
       distinct
       vec))

(defn- catalog-edn? [resource-path]
  (let [path (str/replace (str resource-path) "\\" "/")]
    (and (str/ends-with? path ".edn")
         (not (str/ends-with? path "/index.edn"))
         (not (str/includes? path "/layout/")))))

(defn- resources-on [^ClassLoader loader path]
  (try (enumeration-seq (.getResources loader path))
       (catch Throwable _ nil)))

(defn- resource-on [^ClassLoader loader path]
  (try (.getResource loader path)
       (catch Throwable _ nil)))

(defn- all-urls [path]
  (->> (classloaders)
       (mapcat #(or (resources-on % path) []))
       (remove nil?)
       distinct
       vec))

(defn find-resource
  "Locate a classpath resource across the loaders Loom / Clojure actually use."
  [path]
  (or (io/resource path)
      (some #(resource-on % path) (classloaders))))

(defn- file-edn-names [root ^File start]
  (let [dir (if (.isDirectory start) start (.getParentFile start))]
    (when (and dir (.isDirectory dir))
      (let [base (.toPath dir)]
        (->> (file-seq dir)
             (filter (fn [^File f]
                       (and (.isFile f)
                            (str/ends-with? (.getName f) ".edn"))))
             (map (fn [^File f]
                    (str root "/" (str/replace (str (.relativize base (.toPath f)))
                                               File/separator "/"))))
             (filter catalog-edn?)
             set)))))

(defn- jar-entries-edn-names [root ^JarFile jar]
  (let [prefix (if (str/ends-with? root "/") root (str root "/"))]
    (->> (enumeration-seq (.entries jar))
         (map (fn [^JarEntry e] (.getName e)))
         (filter #(and (str/starts-with? ^String % prefix)
                       (catalog-edn? %)))
         set)))

(defn- jar-file-edn-names [root ^File jar-file]
  (try
    (with-open [jar (JarFile. jar-file)]
      (into #{} (jar-entries-edn-names root jar)))
    (catch Throwable _ nil)))

(defn- jar-url-edn-names [root ^URL url]
  (try
    (let [conn (.openConnection url)]
      (when (instance? JarURLConnection conn)
        (jar-entries-edn-names root (.getJarFile ^JarURLConnection conn))))
    (catch Throwable _ nil)))

(defn- nio-edn-names [root ^URL url]
  (try
    (let [path (Paths/get (.toURI url))
          no-link (make-array LinkOption 0)
          dir (cond
                (Files/isDirectory path no-link) path
                (Files/isRegularFile path no-link) (.getParent path)
                :else nil)]
      (when dir
        (with-open [stream (Files/walk dir (make-array FileVisitOption 0))]
          (into #{}
                (keep (fn [^Path p]
                        (when (Files/isRegularFile p no-link)
                          (let [rel (str/replace (str (.relativize dir p)) "\\" "/")]
                            (when (catalog-edn? rel)
                              (str root "/" rel))))))
                (iterator-seq (.iterator stream))))))
    (catch Throwable _ nil)))

(defn- local-file [part]
  (try
    (cond
      (str/starts-with? part "file:")
      (io/as-file (URL. ^String part))
      (str/includes? part "%")
      (io/file (URLDecoder/decode ^String part "UTF-8"))
      :else (io/file part))
    (catch Throwable _ nil)))

(defn- union-edn-names [root ^URL url]
  (when (= "union" (.getProtocol url))
    (try
      (let [s (.toString url)
            bang (.lastIndexOf s "!/")
            head (cond
                   (>= bang (count "union:")) (subs s (count "union:") bang)
                   (str/starts-with? s "union:") (subs s (count "union:"))
                   :else s)
            parts (->> (str/split (or head "") #"\u0000")
                       (mapcat #(str/split % #"&"))
                       (map str/trim)
                       (remove str/blank?))]
        (into #{}
              (mapcat (fn [part]
                        (when-let [^File f (local-file part)]
                          (cond
                            (.isDirectory f)
                            (file-edn-names root (io/file f root))
                            (and (.isFile f)
                                 (or (str/ends-with? (.getName f) ".jar")
                                     (str/ends-with? (.getName f) ".zip")))
                            (jar-file-edn-names root f)
                            :else nil))))
              parts))
      (catch Throwable _ nil))))

(defn edn-names-from-url
  "List catalog EDN resource paths under `root` given any URL for that root
   or for a file inside it (`file:`, `jar:`, `union:`, or an NIO provider)."
  [root ^URL url]
  (or (not-empty (nio-edn-names root url))
      (when (= "file" (.getProtocol url))
        (not-empty (file-edn-names root (io/file url))))
      (not-empty (jar-url-edn-names root url))
      (not-empty (union-edn-names root url))
      #{}))

(defn- probe-candidates [root names]
  (into []
        (keep (fn [name]
                (let [path (str root "/" name)]
                  (when (find-resource path) path))))
        names))

(defn- source-tree-edn-names
  "Dev fallback: walk up from user.dir to ac/src/main/resources/<root>.

   Loom runClient cwd is typically platform/run/<target>/client, several
   levels below the Gradle root that holds the real skill/VFX sources."
  [root]
  (let [rel (-> (str "ac/src/main/resources/" root)
                (str/replace "/" File/separator)
                (str/replace "\\" File/separator))]
    (loop [^File dir (io/file (System/getProperty "user.dir"))
           depth 0]
      (when (and dir (< depth 12))
        (let [cand (io/file dir rel)]
          (if (.isDirectory cand)
            (file-edn-names root cand)
            (recur (.getParentFile dir) (inc depth))))))))

(defn edn-resource-names
  "Sorted classpath paths of catalog EDN files below `root`.

   Options:
   * `:sentinels` relative filenames used to obtain a URL when directory
     `getResources` is empty (Loom / jars without directory entries)
   * `:candidates` relative filenames probed one-by-one via `getResource`
     when walking still yields nothing"
  ([root] (edn-resource-names root {}))
  ([root {:keys [sentinels candidates]}]
   (let [root (str/replace (str root) #"^/" "")
         from-dirs (into #{} (mapcat #(edn-names-from-url root %) (all-urls root)))
         from-sentinel (when (empty? from-dirs)
                         (some (fn [name]
                                 (when-let [url (find-resource (str root "/" name))]
                                   (not-empty (edn-names-from-url root url))))
                               (or sentinels [])))
         listed (or (not-empty from-dirs) from-sentinel #{})
         from-candidates (when (empty? listed)
                           (not-empty (probe-candidates root (or candidates []))))
         from-source (when (and (empty? listed) (empty? from-candidates))
                       (not-empty (source-tree-edn-names root)))]
     (->> (concat listed from-candidates from-source)
          (filter catalog-edn?)
          distinct
          sort
          vec))))
