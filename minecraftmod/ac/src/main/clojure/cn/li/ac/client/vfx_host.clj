(ns cn.li.ac.client.vfx-host
  "AC composition root for the client-side, platform-neutral VFX runtime."
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.vfx.host :as vfx-host]
            [cn.li.ac.client.vfx-runtime :as ac-vfx]))

(defonce ^:private runtime* (atom nil))

(defn runtime
  "Return the single client VFX runtime, creating it lazily on client init."
  []
  (or @runtime*
      (let [created (ac-vfx/runtime)]
        (if (compare-and-set! runtime* nil created)
          created
          @runtime*))))

(defn host-api
  "Return the opaque host function table exposed through mcmod."
  []
  (vfx-host/create-host-api
   (runtime)
   {:required-anchors (fn [] (ac-vfx/required-anchors))
    :resource-snapshot (fn [] (ac-vfx/resource-snapshot))
    :tick! ac-vfx/tick!
    :sample-frame! ac-vfx/sample-frame!
    :frame-stage ac-vfx/frame-stage
    :release-frame! ac-vfx/release-frame!
    :clear-world! ac-vfx/clear-world!
    :reload-resources! ac-vfx/reload-resources!
    :active? ac-vfx/active?
    :fov-offset ac-vfx/current-fov-offset
    :hand-transform ac-vfx/current-hand-transform
    :drain-camera-pitch-deltas! ac-vfx/drain-camera-pitch-deltas!}))

(defn install!
  "Install the VFX host after client content initialization has completed."
  []
  (bridge/merge-client-bridge! {:vfx-host-api (host-api)})
  (host-api))

(defn reset-for-test!
  "Reset the composition root; intended for isolated lifecycle tests."
  []
  (ac-vfx/reset-for-test!)
  (reset! runtime* nil)
  nil)
