(ns cn.li.mcmod.client.render.script-render-registry
  "Shared ScriptRender profile registry.

  Registry semantics:
  - first registration for a profile id wins
  - duplicate registrations are ignored
  - duplicate warning is emitted at most once per profile id
  - registry can be frozen after initialization"
  (:require [cn.li.mcmod.client.render.script-render-abi :as abi]
            [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.util.log :as log]))

(defonce profile-registry (registry-core/atom-registry {}))
(defonce ^:private registry-frozen? (atom false))
(defonce ^:private duplicate-warned-ids (atom #{}))

(defn frozen?
  []
  @registry-frozen?)

(defn freeze!
  []
  (reset! registry-frozen? true)
  nil)

(defn unfreeze!
  []
  (reset! registry-frozen? false)
  nil)

(defn clear!
  []
  (registry-core/reset-state! profile-registry {})
  (reset! duplicate-warned-ids #{})
  nil)

(defn register-profile!
  [profile]
  (when (frozen?)
    (throw (ex-info "ScriptRender profile registry is frozen"
                    {:id (:id profile)})))
  (let [validated (abi/validate-profile! profile)
        profile-id (:id validated)
        existing (registry-core/lookup profile-registry profile-id)]
    (if existing
      (do
        (when-not (contains? @duplicate-warned-ids profile-id)
          (swap! duplicate-warned-ids conj profile-id)
          (log/warn "Duplicate ScriptRender profile ignored: " profile-id))
        existing)
      (do
        (registry-core/swap-state! profile-registry #(assoc % profile-id validated))
        validated))))

(defn register-profiles!
  [profiles]
  (doseq [profile profiles]
    (register-profile! profile))
  nil)

(defn get-profile
  [profile-id]
  (registry-core/lookup profile-registry profile-id))

(defn list-profile-ids
  []
  (keys (registry-core/snapshot profile-registry)))

(defn snapshot
  []
  (registry-core/snapshot profile-registry))
