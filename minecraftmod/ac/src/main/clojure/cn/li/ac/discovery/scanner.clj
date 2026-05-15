(ns cn.li.ac.discovery.scanner
  "Classpath-based namespace scanner for AC ability content.

  Supports both file-system classpath entries and jar-packaged resources."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.ac.discovery.core :as core]
            [cn.li.mcmod.util.log :as log])
  (:import [java.io File]
           [java.net JarURLConnection URLDecoder]))

(def ^:private ability-resource-prefix "cn/li/ac/content/ability")

(def ^:private family-priority
  {:generic 10
   :electromaster 20
   :meltdowner 30
   :teleporter 40
   :vecmanip 50})

(defn- normalize-path->ns
  [path]
  (-> path
      (str/replace #"\\\\" "/")
      (str/replace #"\.clj$" "")
      (str/replace #"_" "-")
      (str/replace #"/" ".")
      symbol))

(defn- file-clj-paths
  [^File root]
  (when (and root (.exists root))
    (->> (file-seq root)
         (filter #(.isFile ^File %))
         (map #(.getPath ^File %))
         (map #(str/replace % #"\\\\" "/"))
         (filter #(str/ends-with? % ".clj")))))

(defn- classpath-file-paths
  []
  (let [root (io/resource ability-resource-prefix)]
    (when (and root (= "file" (.getProtocol root)))
      (let [root-file (io/file (.toURI root))
            root-path (str/replace (.getPath root-file) #"\\\\" "/")
            prefix (str root-path "/")]
        (->> (file-clj-paths root-file)
             (map #(subs % (count prefix)))
             (map #(str ability-resource-prefix "/" %)))))))

(defn- classpath-jar-paths
  []
  (let [root (io/resource ability-resource-prefix)]
    (when (and root (= "jar" (.getProtocol root)))
      (let [^JarURLConnection conn (.openConnection root)
            jar-file (.getJarFile conn)]
        (->> (enumeration-seq (.entries jar-file))
             (map #(.getName %))
             (filter #(str/starts-with? % (str ability-resource-prefix "/")))
             (filter #(str/ends-with? % ".clj")))))))

(defn- fallback-workspace-paths
  []
  ;; Fallback for local dev where classpath resource may not yet be available.
  (let [user-dir (System/getProperty "user.dir")
        ac-root (io/file user-dir "ac" "src" "main" "clojure" "cn" "li" "ac" "content" "ability")
        prefix (str/replace (.getPath ac-root) #"\\\\" "/")]
    (if (.exists ac-root)
      (->> (file-clj-paths ac-root)
           (map #(subs % (inc (count prefix))))
         (map #(str ability-resource-prefix "/" %)))
      [])))

(defn discover-ability-namespaces
  "Return discovered namespaces under cn.li.ac.content.ability.*.

  Output:
    {:all [...], :skill [...], :fx [...]}"
  []
  (let [paths (->> [(classpath-file-paths)
                    (classpath-jar-paths)
                    (fallback-workspace-paths)]
                   (mapcat identity)
                   distinct
                   sort)
        all-ns (->> paths
                    (map normalize-path->ns)
                    (remove #(= 'cn.li.ac.content.ability %))
                    vec)
        fx (->> all-ns (filter core/fx-namespace?) vec)
        skill (->> all-ns (remove core/fx-namespace?) vec)]
    (when (seq all-ns)
      (log/info (str "Discovered ability namespaces: " (count all-ns)
                     " (skill=" (count skill) ", fx=" (count fx) ")")))
    {:all all-ns :skill skill :fx fx}))

(defn discover-ability-providers
  "Group discovered namespaces by ability family into provider records."
  []
  (let [{:keys [skill fx]} (discover-ability-namespaces)
        grouped-skills (group-by core/base-family skill)
        grouped-fx (group-by core/base-family fx)
        families (->> (concat (keys grouped-skills) (keys grouped-fx))
                      (remove nil?)
                      distinct
                      sort)]
    (->> families
         (map (fn [family]
                {:id family
                 :priority (long (get family-priority family 100))
                 :skill-namespaces (vec (sort (get grouped-skills family [])))
                 :fx-namespaces (vec (sort (get grouped-fx family [])))}))
         vec)))
