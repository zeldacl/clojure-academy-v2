(ns cn.li.vfx.final-engine-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.vfx.effect-schema :as schema]
            [cn.li.vfx.replication :as replication]
            [cn.li.vfx.final-engine :as engine]
            [cn.li.vfx.final-client :as client])
  (:import [cn.li.mcmod.runtime.vfx ParticleBuffer ParticleKernel]))

(def catalog
  (schema/catalog [{:id :ring
                    :lifecycle :transient
                    :parameters {:radius {:type :float :default 1.0 :mutability :per-tick}
                                 :duration-ticks {:type :int :default 2}}
                    :primitives #{:line}}]))

(deftest lifecycle-and-render-batch-test
  (let [service (replication/create-service
                  {:catalog catalog
                   :nearby (fn [_ _] #{:alice})})
        runtime (engine/create-runtime {:catalog catalog
                                        :replication-service service})
        spawned (engine/spawn! runtime :ring {:owner :caster :world-id "w"
                                              :anchor :a :params {:radius 2.0}})
        updated (engine/update! runtime (:handle spawned) {:radius 3.0})
        tick1 (engine/tick! runtime)
        tick2 (engine/tick! runtime)]
    (is (= :spawn (:op (:packet spawned))))
    (is (= {:radius 3.0} (:params (:packet updated))))
    (is (= [1] (get-in updated [:packet :mask :indices])))
    (is (= :line (:primitive (first (:render spawned)))))
    (is (= 3.0 (get-in updated [:render 0 :params :radius])))
    (is (empty? (:expired tick1)))
    (is (= [(:handle spawned)] (:expired tick2)))
    (is (empty? (:instances (engine/snapshot runtime))))))

(deftest unknown-parameter-is-rejected-test
  (let [runtime (engine/create-runtime {:catalog catalog})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (engine/spawn! runtime :ring {:params {:unknown 1.0}})))))

(deftest server-rejects-unknown-effect-with-structured-error-test
  (let [runtime (engine/create-runtime {:catalog catalog})
        error (try
                (engine/spawn! runtime :missing {:params {}})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is error)
    (is (= :missing (:effect-id (ex-data error))))))

(deftest client-rejects-unknown-network-effect-test
  (let [runtime (client/create-runtime {})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/dispatch-signal! runtime
                                          {:op :spawn :effect-id :missing
                                           :owner :caster :world-id "w"})))))

(deftest client-keeps-concurrent-instance-keys-and-drops-stale-updates-test
  (let [runtime (client/create-runtime {})
        descriptor {:id :ring :lifecycle :session
                    :parameters []
                    :control-graph {:component :vfx/ring
                                   :center [0.0 0.0 0.0]
                                   :radius {:ref [:input :radius]}
                                   :segments 8
                                   :color [1 1 1 1]}}]
    (client/register-effect! runtime descriptor)
    (client/dispatch-signal! runtime {:op :spawn :effect-id :ring
                                      :owner :caster :world-id "w"
                                      :instance-key [:cast 1] :event-seq 1
                                      :params {:radius 1.0}})
    (client/dispatch-signal! runtime {:op :spawn :effect-id :ring
                                      :owner :caster :world-id "w"
                                      :instance-key [:cast 2] :event-seq 1
                                      :params {:radius 2.0}})
    (is (= 2 (count @(:instances runtime))))
    (client/dispatch-signal! runtime {:op :update :effect-id :ring
                                      :owner :caster :world-id "w"
                                      :instance-key [:cast 1] :event-seq 2
                                      :params {:radius 4.0}})
    (client/dispatch-signal! runtime {:op :update :effect-id :ring
                                      :owner :caster :world-id "w"
                                      :instance-key [:cast 1] :event-seq 1
                                      :params {:radius 9.0}})
    (is (= 4.0 (get-in (first (filter #(= [:cast 1] (:instance-key (val %)))
                                     @(:instances runtime))) [1 :params :radius])))))

(deftest graph-sampling-produces-neutral-draw-operation-test
  (let [catalog (schema/catalog
                 [{:id :graph-ring :lifecycle :session
                   :parameters {:center {:type :vec3} :radius {:type :float}}
                   :primitives #{:line}
                   :control-graph {:component :vfx/timeline
                                  :duration-ticks 20
                                  :children [{:at 0 :node {:component :vfx/ring
                                                           :center {:ref [:input :center]}
                                                           :radius {:ref [:input :radius]}
                                                           :segments 8
                                                           :color [1 0 0 1]}}]}}])
        runtime (engine/create-runtime {:catalog catalog})
        result (engine/spawn! runtime :graph-ring
                              {:owner :caster :world-id "w"
                               :params {:center {:vec3 [1 2 3]} :radius 2.0}})]
    (is (= :draw-batch (-> result :render first :operation)))
    (is (= :line (-> result :render first :primitive)))
    (is (= {:vec3 [1 2 3]} (-> result :render first :geometry :center)))))

(deftest client-rejects-remote-instance-identity-cross-write-test
  (let [runtime (client/create-runtime {})
        descriptor {:id :ring :lifecycle :session :parameters []}
        other {:id :other :lifecycle :session :parameters []}]
    (client/register-effect! runtime descriptor)
    (client/register-effect! runtime other)
    (client/dispatch-signal! runtime {:op :spawn :effect-id :ring
                                      :owner :caster :world-id "w"
                                      :instance-id 41 :event-seq 1
                                      :params {:radius 1.0}})
    (client/dispatch-signal! runtime {:op :update :effect-id :ring
                                      :owner :caster :world-id "w"
                                      :instance-id 41 :event-seq 2
                                      :params {:radius 2.0}})
    (client/dispatch-signal! runtime {:op :update :effect-id :other
                                      :owner :caster :world-id "w"
                                      :instance-id 41 :event-seq 3
                                      :params {:radius 9.0}})
    (client/dispatch-signal! runtime {:op :update :effect-id :ring
                                      :owner :other :world-id "w"
                                      :instance-id 41 :event-seq 4
                                      :params {:radius 10.0}})
    (client/dispatch-signal! runtime {:op :update :effect-id :ring
                                      :owner :caster :world-id "other"
                                      :instance-id 41 :event-seq 5
                                      :params {:radius 11.0}})
    (let [instances (vals @(:instances runtime))]
      (is (= 1 (count instances)))
      (is (= 2.0 (get-in (first instances) [:params :radius])))
      (is (= 41 (:instance-id (first instances)))))))


(deftest java-particle-kernel-compacts-expired-particles-test
  (let [^ParticleBuffer particles (ParticleBuffer. 4)
        start (.reserve particles 2)
        ages (.age particles)
        lifetimes (.lifetime particles)
        velocities (.velocityX particles)]
    (aset ages start 0.0)
    (aset lifetimes start 0.01)
    (aset ages (inc start) 0.0)
    (aset lifetimes (inc start) 1.0)
    (aset velocities (inc start) 2.0)
    (is (= 1 (ParticleKernel/integrate particles start (.reservedEnd particles) 0.05)))
    (is (= 1 (.size particles)))
    (is (< (Math/abs (- 0.05 (double (aget (.age particles) 0)))) 1.0e-5))))

