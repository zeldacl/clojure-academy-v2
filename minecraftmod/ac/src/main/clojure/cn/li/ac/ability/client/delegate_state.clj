(ns cn.li.ac.ability.client.delegate-state
  "DelegateState — visual state for each skill slot derived from active context.

  States:
    :idle    — no active context for this slot (alpha=0.7, no glow)
    :charge  — context is in charge phase (alpha=1.0, golden glow #FFAD37, sine)
    :active  — context is alive and active (alpha=1.0, blue glow #46B3FF, sine)"
  (:require [cn.li.ac.ability.state.context :as ctx]))

(def ^:private state-visual-params
  {:idle   {:alpha 0.7  :glow-color nil                    :sin-effect? false}
   :charge {:alpha 1.0  :glow-color [0xFF 0xAD 0x37 0xFF] :sin-effect? true}
   :active {:alpha 1.0  :glow-color [0x46 0xB3 0xFF 0xFF] :sin-effect? true}})

(defn- context-to-delegate-state
  "Derive delegate state keyword from a context's input-state."
  [ctx]
  (case (:input-state ctx)
    :idle    :idle
    :active  :active
    :charge  :charge
    ;; fallback
    :idle))

(defn delegate-state-for-context
  "Given a context map (or nil), return the full visual state map."
  [ctx]
  (let [state-kw (if ctx (context-to-delegate-state ctx) :idle)]
    (assoc (get state-visual-params state-kw)
           :state state-kw)))

(defn delegate-state-for-slot
  "Look up the active context for a slot (by skill-id binding) and derive visual state.
  active-contexts: seq of context maps for the player
  slot-skill-id:   the skill-id bound to this slot (keyword or nil)"
  [active-contexts slot-skill-id]
  (if (nil? slot-skill-id)
    (delegate-state-for-context nil)
    (let [matched (first (filter #(and (= (:skill-id %) slot-skill-id)
                                       (not= (:status %) :terminated))
                                 active-contexts))]
      (delegate-state-for-context matched))))
