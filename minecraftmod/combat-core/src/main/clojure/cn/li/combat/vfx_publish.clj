(ns cn.li.combat.vfx-publish
  "Psi-style VFX/result delivery for a finalized combat result: the single
   place where a result's :vfx-signals get routed to their declared
   audience (self-only vs. nearby-broadcast) and the caster's own
   status/feedback gets pushed, for every dispatch path (session tick,
   one-shot event, RPC reply) alike.

   The caller (AC) owns the network transport and the compiled VFX
   catalog's lifespan; this namespace only needs the catalog data itself
   (cn.li.vfx.recipe/load-catalog!'s return value) passed in explicitly per
   call -- it never loads, caches, or knows where the catalog comes from."
  (:require [cn.li.vfx.recipe :as vfx-recipe]))

(defonce ^:private result-sink* (atom nil))
;; :self signals go to the caster alone (same per-owner transport as
;; result-sink*, just a different message id so the client can register one
;; push handler per concern); :tracking/:world signals broadcast to every
;; nearby client, caster included.
(defonce ^:private vfx-self-sink* (atom nil))
(defonce ^:private vfx-broadcast-sink* (atom nil))
(defonce ^:private active-persistent-signals* (atom {}))

(def ^:private default-vfx-audience
  "A VFX effect document with no declared :audience broadcasts to every
   nearby client (default server view-distance-ish radius) -- Psi-style
   networking assumes visible-to-everyone unless a skill's own effect opts
   into :self (camera/screen-post-process effects that only make sense to
   the caster's own client)."
  {:scope :tracking :radius 96.0})

(defn install-result-sink!
  "Install the network sink for server-driven session results.

   The sink receives `[owner result]`. The payload never carries
   :vfx-signals -- those are published separately, per-signal, by audience
   (see install-vfx-self-sink!/install-vfx-broadcast-sink!)."
  [sink]
  (when-not (ifn? sink)
    (throw (ex-info "combat result sink must be callable" {:value sink})))
  (reset! result-sink* sink)
  sink)

(defn install-vfx-self-sink!
  "Install the network sink for VFX signals whose audience is :self
   (camera/screen-post-process effects, or an ability's own :audience
   {:type :owner} declaration). The sink receives `[owner signal]`."
  [sink]
  (when-not (ifn? sink)
    (throw (ex-info "vfx self-sink must be callable" {:value sink})))
  (reset! vfx-self-sink* sink)
  sink)

(defn install-vfx-broadcast-sink!
  "Install the network sink for VFX signals whose audience is :tracking or
   :world -- broadcast to every nearby client, the caster included
   (Psi-style: server executes, everyone who can see it happen gets the
   visual). The sink receives `[owner signal radius]`; radius nil means no
   distance cap."
  [sink]
  (when-not (ifn? sink)
    (throw (ex-info "vfx broadcast sink must be callable" {:value sink})))
  (reset! vfx-broadcast-sink* sink)
  sink)

(defn- normalize-signal-audience
  "Resolve one VFX signal's effective audience as {:scope :radius}.

   Precedence: (1) the ability's own {:type :owner | :nearby :radius N}
   declaration on the :effect/vfx node that emitted this signal -- authored
   per-activation-site, so the same effect can be :self in one ability's use
   and broadcast in another's; (2) the VFX effect document's own :audience
   default (vfx-recipe/effect-audience, e.g. camera/screen-post-process
   effects that are never meaningfully broadcastable); (3) the global
   tracking default."
  [vfx-catalog signal]
  (let [declared (:audience signal)]
    (cond
      (= :owner (:type declared)) {:scope :self}
      (= :nearby (:type declared)) {:scope :tracking :radius (:radius declared)}
      :else (or (vfx-recipe/effect-audience vfx-catalog (:effect-id signal))
                default-vfx-audience))))

(defn- persistent-lifecycle? [vfx-catalog effect-id]
  (contains? #{:session :persistent} (vfx-recipe/effect-lifecycle vfx-catalog effect-id)))

(defn- track-persistent-signal!
  "Remember the last :spawn signal for a broadcast :session/:persistent VFX
   instance so it can be resent (see replay-persistent-signals!) to a player
   who enters tracking range after the effect started. :transient effects
   are never tracked -- by the time anyone could join late they are already
   over."
  [vfx-catalog owner signal]
  (when (persistent-lifecycle? vfx-catalog (:effect-id signal))
    (case (:op signal)
      :spawn (swap! active-persistent-signals* assoc-in
                    [owner (:instance-key signal)] signal)
      :destroy (swap! active-persistent-signals* update owner
                       dissoc (:instance-key signal))
      nil)))

(defn- forget-owner-persistent-signals! [owner]
  (swap! active-persistent-signals* dissoc owner)
  nil)

(defn- publish-vfx-signal!
  [vfx-catalog owner signal]
  (let [{:keys [scope radius]} (normalize-signal-audience vfx-catalog signal)]
    (case scope
      :self (when-let [sink @vfx-self-sink*] (sink owner signal))
      (do (track-persistent-signal! vfx-catalog owner signal)
          (when-let [sink @vfx-broadcast-sink*]
            (sink owner signal (when (not= :world scope) radius)))))))

(defn replay-persistent-signals!
  "Every persistent-replay-interval-ticks (the caller decides the cadence),
   resend each owner's active :session/:persistent VFX :spawn signals
   through the broadcast sink. The client runtime's dispatch-signal! is
   idempotent per instance-key (vfx-core/runtime.clj) -- a client that
   already has the instance treats this as a harmless no-op re-signal,
   while a client that just entered tracking range creates the instance
   fresh. No per-viewer bookkeeping needed."
  [vfx-catalog]
  (when-let [sink @vfx-broadcast-sink*]
    (doseq [[owner signals] @active-persistent-signals*
            signal (vals signals)]
      (let [{:keys [scope radius]} (normalize-signal-audience vfx-catalog signal)]
        (sink owner signal (when (not= :world scope) radius)))))
  nil)

(defn publish-combat-result!
  "The single entry point for delivering a finalized combat result: routes
   every :vfx-signals entry to its audience-appropriate sink, pushes
   status/feedback to the caster alone through the result sink, and returns
   the result with :vfx-signals stripped -- matching what the sinks
   actually received, so a caller that also relays this same return value
   (e.g. an RPC reply) never re-delivers VFX a push already sent."
  [vfx-catalog result]
  (doseq [signal (:vfx-signals result)]
    (publish-vfx-signal! vfx-catalog (:owner result) signal))
  (when-let [sink @result-sink*]
    (when-let [owner (:owner result)]
      (sink owner (dissoc result :vfx-signals))))
  (dissoc result :vfx-signals))

(defn broadcast-clear-owner!
  "Tell every nearby client to drop its VFX instances for `owner` (logout,
   death, dimension change). Broadcast, not self-only: a player who
   disconnects mid-cast left :tracking-scope VFX running on OTHER clients
   too, and only they can tear it down -- the owner's own client is about to
   lose its whole session anyway."
  [owner]
  (forget-owner-persistent-signals! owner)
  (when-let [sink @vfx-broadcast-sink*]
    (sink owner {:op :clear-owner :owner owner :event-seq 0} nil))
  nil)

(defn reset-for-test! []
  (reset! result-sink* nil)
  (reset! vfx-self-sink* nil)
  (reset! vfx-broadcast-sink* nil)
  (reset! active-persistent-signals* {})
  nil)
