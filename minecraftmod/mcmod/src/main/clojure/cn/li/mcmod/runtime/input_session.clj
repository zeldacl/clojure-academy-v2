(ns cn.li.mcmod.runtime.input-session
  "Headless multiplayer input session over the fixed byte protocol.

   The session owns only transport invariants: monotonic sequence numbers,
   bounded rate, and lifecycle aborts. It does not know skills, entities, or
   Minecraft classes; AC supplies the neutral intent callback.")
(def ^:const max-inputs-per-window 40)
(def ^:const window-ticks 20)

(defn- decode-intent [packet]
  ((requiring-resolve 'cn.li.mcmod.runtime.fixed-channel/decode-intent) packet))

(defn create-session
  [{:keys [owner send! on-intent! on-abort!]
    :or {send! (fn [_] nil)
         on-intent! (fn [_] nil)
         on-abort! (fn [_] nil)}}]
  (atom {:owner owner
         :active? true
         :last-seq -1
         :window-start 0
         :window-count 0
         :send! send!
         :on-intent! on-intent!
         :on-abort! on-abort!}))

(defn state [session] (dissoc @session :send! :on-intent! :on-abort!))

(defn- rate-ok? [snapshot tick]
  (let [start (long (:window-start snapshot))
        count (long (:window-count snapshot))]
    (if (>= (- (long tick) start) window-ticks)
      {:window-start (long tick) :window-count 0}
      (when (< count max-inputs-per-window)
        {:window-start start :window-count count}))))

(defn abort!
  "Abort exactly once; the client must send no further edges afterwards."
  [session reason]
  (let [callback (atom nil)]
    (swap! session (fn [snapshot]
                     (if (:active? snapshot)
                       (do (reset! callback (:on-abort! snapshot))
                           (assoc snapshot :active? false :abort-reason reason))
                       snapshot)))
    (when @callback (@callback reason))
    {:status :aborted :reason reason}))

(defn receive!
  "Decode, validate and dispatch one fixed input packet.

   Duplicate/out-of-order packets are acknowledged as ignored and never reach
   the ability executor. A malformed packet or rate violation aborts the
   session, which is the safe multiplayer failure mode."
  [session packet server-tick]
  (let [decoded (try (decode-intent packet)
                     (catch Exception error
                       (abort! session :malformed-packet)
                       {:error error}))]
    (if (:error decoded)
      {:status :rejected :reason :malformed-packet}
      (let [result (atom nil)]
        (swap! session
               (fn [snapshot]
                 (cond
                   (not (:active? snapshot))
                   (do (reset! result {:status :ignored :reason :inactive}) snapshot)

                   (<= (long (:seq decoded)) (long (:last-seq snapshot)))
                   (do (reset! result {:status :ignored :reason :duplicate}) snapshot)

                   :else
                   (if-let [window (rate-ok? snapshot server-tick)]
                     (do (reset! result {:status :accepted :intent decoded})
                         (assoc snapshot
                                :last-seq (long (:seq decoded))
                                :window-start (:window-start window)
                                :window-count (inc (long (:window-count window)))))
                     (do (reset! result {:status :rejected :reason :rate-limit})
                         (assoc snapshot :active? false :abort-reason :rate-limit))))))
        (let [accepted @result]
          (when (= :accepted (:status accepted))
            ((:on-intent! @session) (:intent accepted)))
          (when (= :rejected (:status accepted))
            ((:on-abort! @session) (:reason accepted)))
          accepted)))))

(defn lifecycle-abort!
  "Shared abort path for disconnect, death, dimension change and GUI close."
  [session lifecycle]
  (abort! session lifecycle))
