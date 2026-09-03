(ns cn.li.platform.optional.ic2-energy-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.platform.optional.ic2-energy :as ic2])
  (:import [java.net URLClassLoader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [javax.tools ToolProvider]))

(def ^:private ic2-state
  @(ns-resolve 'cn.li.platform.optional.ic2-energy 'ic2-state))
(def ^:private ic2-interfaces
  @(ns-resolve 'cn.li.platform.optional.ic2-energy 'ic2-interfaces))

(defn- reset-detection!
  []
  (reset! ic2-state :unknown)
  (reset! ic2-interfaces nil)
  (ic2/clear-proxy-cache!))

(defn- compile-fixture-loader
  [sources]
  (let [root (Files/createTempDirectory "academy-ic2-fixture" (make-array java.nio.file.attribute.FileAttribute 0))
        source-files (mapv (fn [[class-name source]]
                             (let [file (.resolve ^Path root (str class-name ".java"))]
                               (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
                               (Files/write file (.getBytes ^String source StandardCharsets/UTF_8)
                                             (make-array java.nio.file.OpenOption 0))
                               (.toString file)))
                           sources)
        compiler (ToolProvider/getSystemJavaCompiler)
        args (into-array String (concat ["-d" (.toString root)] source-files))]
    (when-not compiler
      (throw (ex-info "JDK compiler is required for IC2 fixture tests" {})))
    (when-not (zero? (.run compiler nil nil nil args))
      (throw (ex-info "IC2 fixture compilation failed" {:sources source-files})))
    (URLClassLoader. (into-array java.net.URL [(.toURL (.toUri root))]) nil)))

(defn- with-context-loader
  [^ClassLoader loader f]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      (f)
      (finally
        (.setContextClassLoader thread previous)
        (.close ^URLClassLoader loader)))))

(def ^:private source-interface
  "package ic2.api.energy.tile; public interface IEnergySource {
     double getOfferedEnergy(); void drawEnergy(double amount);
     int getSourceTier(); boolean emitsEnergyTo(Object emitter, Object direction);
   }")

(def ^:private sink-interface
  "package ic2.api.energy.tile; public interface IEnergySink {
     double getDemandedEnergy(); int getSinkTier();
     double injectEnergy(Object direction, double amount, int tier);
     boolean acceptsEnergyFrom(Object emitter, Object direction);
   }")

(def ^:private incompatible-source
  "package ic2.api.energy.tile; public class IEnergySource {}")

(def ^:private incompatible-sink
  "package ic2.api.energy.tile; public class IEnergySink {}")

(deftest ic2-missing-is-cached-as-absent
  (testing "an isolated loader without IC2 disables the integration"
    (reset-detection!)
    (with-context-loader
      (URLClassLoader. (make-array java.net.URL 0) nil)
      #(is (false? (ic2/ic2-available?))))
    (is (= :absent @ic2-state))
    (is (false? (ic2/ic2-available?)))))

(deftest ic2-wrong-shapes-are-incompatible
  (testing "same-name ordinary classes do not count as IC2 interfaces"
    (reset-detection!)
    (with-context-loader
      (compile-fixture-loader
        [["ic2/api/energy/tile/IEnergySource" incompatible-source]
         ["ic2/api/energy/tile/IEnergySink" incompatible-sink]])
      #(is (false? (ic2/ic2-available?))))
    (is (= :incompatible @ic2-state))
    (is (false? (ic2/ic2-available?)))))

(deftest ic2-compatible-interfaces-are-present
  (testing "minimal typed IC2 interfaces are detected once"
    (reset-detection!)
    (with-context-loader
      (compile-fixture-loader
        [["ic2/api/energy/tile/IEnergySource" source-interface]
         ["ic2/api/energy/tile/IEnergySink" sink-interface]])
      #(is (true? (ic2/ic2-available?))))
    (is (= :present @ic2-state))
    (is (true? (ic2/ic2-available?)))))
