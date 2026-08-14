(ns cn.li.vfx.host
  "Opaque Host API installed by AC and consumed through mcmod."
  (:require [cn.li.mcmod.runtime.vfx-contract :as contract]
            [cn.li.vfx.runtime :as runtime]))

(defn create-host-api [vfx-runtime operations]
  (contract/validate-host-api
   (merge {:schema-version contract/schema-version
           :required-anchors (fn [] [])
           :tick! (fn [context] (runtime/tick! vfx-runtime context))
           :sample-frame! (fn [context] (runtime/sample-frame! vfx-runtime context))
           :frame-stage (fn [frame-id stage] (runtime/frame-stage vfx-runtime frame-id stage))
           :release-frame! (fn [frame-id] (runtime/release-frame! vfx-runtime frame-id))
           :clear-world! (fn [world-id] (runtime/clear-world! vfx-runtime world-id))
           :resource-snapshot (fn [] {})
           :reload-resources! (fn [generation] (runtime/reload-resources! vfx-runtime generation))
           :active? (fn [] false)
           :fov-offset (fn [_] 0.0)
           :hand-transform (fn [] nil)
           :drain-camera-pitch-deltas! (fn [_] [])}
          operations)))
