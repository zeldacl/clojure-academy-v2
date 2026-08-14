(ns cn.li.presentation.core.backend
  (:import [cn.li.presentation.core BackendCapabilities RenderBackend ResourceGeneration]))

(defn capabilities [profile]
  (case profile
    :mc-1-20-1 (BackendCapabilities. false false false true 8)
    :mc-1-21-1 (BackendCapabilities. false false false true 8)
    :mc-26-2 (BackendCapabilities. true true true true 16)
    (BackendCapabilities/conservative)))

(defn recording-backend [profile]
  (let [submissions (atom []) generation (atom (ResourceGeneration. 0))]
    {:submissions submissions
     :generation generation
     :backend (reify RenderBackend
                (capabilities [_] (capabilities profile))
                (submit [_ packet stage] (swap! submissions conj [stage packet]))
                (reloadResources [_ next] (reset! generation next)))}))
