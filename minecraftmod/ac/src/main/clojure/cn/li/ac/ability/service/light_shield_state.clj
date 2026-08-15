(ns cn.li.ac.ability.service.light-shield-state
  "Pure Light Shield reducer used by the Combat DamageRequest adapter.

   Resource mutation and spatial queries remain host ports; this namespace
   only preserves the authoritative timing/absorption rules." )

(defn start
  [overload-floor]
  {:ticks 0
   :last-absorb-tick -1
   :overload-floor (double (or overload-floor 0.0))})

(defn tick
  [state]
  (update (or state (start 0.0)) :ticks (fnil inc 0)))

(defn eligible-absorb?
  [{:keys [ticks last-absorb-tick interval front? damage]}]
  (and (pos? (double (or damage 0.0)))
       (boolean front?)
       (or (= -1 (long (or last-absorb-tick -1)))
           (> (- (long (or ticks 0))
                 (long (or last-absorb-tick -1)))
              (long (or interval 0))))))

(defn absorb
  [state damage cap tick]
  (let [absorbed (min (double (or damage 0.0)) (double (or cap 0.0)))]
    [(assoc (or state (start 0.0)) :last-absorb-tick (long tick))
     (- (double (or damage 0.0)) absorbed)
     absorbed]))

(defn end-cooldown-ticks
  [ticks exp]
  (long (* (double (or ticks 0)) (- 2.0 (double (or exp 0.0))))))
