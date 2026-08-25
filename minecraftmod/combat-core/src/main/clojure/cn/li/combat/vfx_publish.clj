(ns cn.li.combat.vfx-publish
  "Psi-style VFX/result delivery for a finalized combat result: the single
   place where a result's :vfx-signals get routed to their declared
   audience (self-only vs. nearby-broadcast) and the caster's own
   status/feedback gets pushed, for every dispatch path (session tick,
   one-shot event, RPC reply) alike.

   The caller (AC) owns the network transport and the compiled VFX
   catalog's lifespan; this namespace only needs the catalog data itself
   the final typed VFX catalog passed in explicitly per call -- it never
   loads, caches, or knows where the catalog comes from."
  (:import [java.util UUID]
           [cn.li.mcmod.runtime.vfx VfxLifecyclePacket VfxPacketKind]))
(defn- typed-packet [signal]
  (let [kind (case (:op signal)
               :spawn VfxPacketKind/SPAWN
               :snapshot VfxPacketKind/SNAPSHOT
               :update VfxPacketKind/DELTA
               :trigger VfxPacketKind/EVENT
               :destroy VfxPacketKind/DESTROY
               :release VfxPacketKind/RELEASE
               :clear-owner VfxPacketKind/OWNER_RESET
               (throw (ex-info "unknown VFX operation" {:op (:op signal)})))
        owner (:owner signal)
        owner (if (instance? UUID owner) owner (UUID/nameUUIDFromBytes (.getBytes (str owner) "UTF-8")))]
    (VfxLifecyclePacket. kind (long (or (:world-epoch signal) 0))
                          (long (or (:instance-id signal) 0))
                          (int (or (:asset-id signal) (bit-and 0x7fffffff (hash (:effect-id signal)))))
                          (long (or (:state-seq signal) 0))
                          (long (or (:event-seq signal) 0)) owner
                          (long (or (:start-server-tick signal) 0))
                          (long (or (:seed signal) 0)))))

(defonce ^:private result-sink* (atom nil))
;; :self signals go to the caster alone (same per-owner transport as
;; result-sink*, just a different message id so the client can register one
;; push handler per concern); :tracking/:world signals broadcast to every
;; nearby client, caster included.
(defonce ^:private vfx-self-sink* (atom nil))
(defonce ^:private vfx-broadcast-sink* (atom nil))
(defonce ^:private vfx-typed-self-sink* (atom nil))
(defonce ^:private vfx-typed-broadcast-sink* (atom nil))
(defonce ^:private active-persistent-signals* (atom {}))
(defonce ^:private latest-signals* (atom {}))

(def ^:private default-vfx-audience
  "A VFX effect document with no declared :audience broadcasts to every
   nearby client (default server view-distance-ish radius) -- Psi-style
   networking assumes visible-to-everyone unless a skill's own effect opts
   into :self (camera/screen-post-process effects that only make sense to
   the caster's own client)."
  {:scope :tracking :radius 96.0})

(defn- effect-audience [vfx-catalog effect-id]
  (get-in vfx-catalog [:effects effect-id :audience]))

(defn- effect-lifecycle [vfx-catalog effect-id]
  (get-in vfx-catalog [:effects effect-id :lifecycle]))

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

(defn install-vfx-typed-self-sink!
  "Install typed S2C sink receiving [owner VfxPacket]."
  [sink]
  (when-not (ifn? sink) (throw (ex-info "typed VFX self sink must be callable" {:value sink})))
  (reset! vfx-typed-self-sink* sink)
  sink)

(defn install-vfx-typed-broadcast-sink!
  "Install typed S2C tracking sink receiving [owner VfxPacket radius]."
  [sink]
  (when-not (ifn? sink) (throw (ex-info "typed VFX broadcast sink must be callable" {:value sink})))
  (reset! vfx-typed-broadcast-sink* sink)
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
      :else (or (effect-audience vfx-catalog (:effect-id signal))
                default-vfx-audience))))

(defn- persistent-lifecycle? [vfx-catalog effect-id]
  (contains? #{:session :persistent} (effect-lifecycle vfx-catalog effect-id)))

(defn- signal-key [signal]
  [(:effect-id signal) (:instance-key signal)])

(defn- parameter-names [vfx-catalog effect-id]
  (mapv :name (get-in vfx-catalog [:effects effect-id :parameters] [])))

(defn- dirty-mask [names before after]
  (let [indices (vec (keep-indexed (fn [index name]
                                    (when (not= (get before name) (get after name))
                                      index)) names))
        word-count (long (Math/ceil (/ (double (count names)) 64.0)))
        words (reduce (fn [result index]
                        (let [word (quot index 64)
                              bit (mod index 64)]
                          (update result word #(long (bit-or (long %)
                                                              (bit-shift-left 1 bit))))))
                      (vec (repeat word-count 0)) indices)]
    {:word-count word-count :words words :indices indices}))

(defn- prepare-signal!
  "Apply server-side VFX idempotency and dirty-parameter projection.

   The client still receives the same typed signal shape, but an update only
   carries fields whose values changed since the last accepted signal. This
   is the Psi-style bandwidth reduction point; replay uses the stored full
   spawn baseline below."
  [vfx-catalog owner signal]
  (let [key (signal-key signal)
        old (get-in @latest-signals* [owner key])
        incoming-seq (long (or (:event-seq signal) 0))]
    (when (or (nil? old) (> incoming-seq (long (or (:event-seq old) -1)))
              (= :spawn (:op signal)))
      (case (:op signal)
        :spawn
        (do (swap! latest-signals* assoc-in [owner key] signal)
            signal)
        :update
        (let [before (or (:params old) {})
              after (merge before (or (:params signal) {}))
              names (parameter-names vfx-catalog (:effect-id signal))
              mask (dirty-mask names before after)
              changed (select-keys after (map #(nth names %) (:indices mask)))
              projected (assoc signal :params changed :mask mask)]
          (swap! latest-signals* assoc-in [owner key]
                 (assoc signal :params after))
          projected)
        :destroy
        ;; Retain the tombstone sequence so a delayed update cannot be
        ;; re-emitted after the instance was destroyed.
        (do (swap! latest-signals* assoc-in [owner key] signal)
            signal)
        :clear-owner
        (do (swap! latest-signals* dissoc owner)
            signal)
        signal))))

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
      :update (swap! active-persistent-signals* update-in
                      [owner (:instance-key signal) :params]
                      merge (or (:params signal) {}))
      :destroy (swap! active-persistent-signals* update owner
                       dissoc (:instance-key signal))
      nil)))

(defn- forget-owner-persistent-signals! [owner]
  (swap! active-persistent-signals* dissoc owner)
  nil)

(defn- publish-vfx-signal!
  [vfx-catalog owner signal]
  (when-let [signal (prepare-signal! vfx-catalog owner signal)]
    (let [{:keys [scope radius]} (normalize-signal-audience vfx-catalog signal)]
      (let [typed-signal (assoc signal :asset-id (or (:asset-id signal)
                                                   (bit-and 0x7fffffff (hash (:effect-id signal)))))]
        (case scope
          :self (if-let [sink @vfx-typed-self-sink*]
                  (sink owner (typed-packet typed-signal))
                  (when-let [sink @vfx-self-sink*] (sink owner signal)))
          (do (track-persistent-signal! vfx-catalog owner signal)
              (if-let [sink @vfx-typed-broadcast-sink*]
                (sink owner (typed-packet typed-signal)
                      (when (not= :world scope) radius))
                (when-let [sink @vfx-broadcast-sink*]
                  (sink owner signal (when (not= :world scope) radius))))))))

))
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
  (swap! latest-signals* dissoc owner)
  (when-let [sink @vfx-broadcast-sink*]
    (sink owner {:op :clear-owner :owner owner :event-seq 0} nil))
  nil)

(defn reset-for-test! []
  (reset! result-sink* nil)
  (reset! vfx-self-sink* nil)
  (reset! vfx-broadcast-sink* nil)
  (reset! active-persistent-signals* {})
  (reset! latest-signals* {})
  nil)
