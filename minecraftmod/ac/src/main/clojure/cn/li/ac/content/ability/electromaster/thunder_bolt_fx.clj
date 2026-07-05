(ns cn.li.ac.content.ability.electromaster.thunder-bolt-fx
	"Client FX for Thunder Bolt: zigzag electric arc effects."
	(:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
						[cn.li.ac.ability.client.fx-spec :as fx-spec]
						[cn.li.ac.ability.client.level-effects :as level-effects]
						[cn.li.ac.ability.client.render-util :as ru]
						[cn.li.ac.ability.client.arc-patterns :as arc]))

(def ^:private thunder-bolt-effect-id :thunder-bolt-strike)
(def ^:private main-arc-life 20)

(defn default-thunder-bolt-fx-runtime-state
	[]
	{:arcs {}})

(defn thunder-bolt-fx-snapshot
	[]
	(or (level-effects/effect-state-snapshot thunder-bolt-effect-id)
			(default-thunder-bolt-fx-runtime-state)))

(defn reset-thunder-bolt-fx-for-test!
	[]
	(level-effects/reset-level-effect-state-for-test!
		thunder-bolt-effect-id
		(default-thunder-bolt-fx-runtime-state))
	nil)

(defn clear-thunder-bolt-owner!
	[owner-key]
	(level-effects/update-effect-state!
		thunder-bolt-effect-id
		(fn [store]
			(update (or store (default-thunder-bolt-fx-runtime-state)) :arcs dissoc owner-key)))
	nil)

(defn- all-arcs
	[]
	(mapcat val (:arcs (thunder-bolt-fx-snapshot))))

(defn- enqueue-state!
	[store ctx-id channel owner-key payload]
	(let [store* (or store (default-thunder-bolt-fx-runtime-state))
						{:keys [start end aoe-points source-player-id world-id]} (or payload {})
				owner-key* (or owner-key [:ctx ctx-id])]
		(when (and start end)
			(let [base-meta {:owner-key owner-key*
											 :ctx-id ctx-id
											 :channel channel
											 :source-player-id source-player-id
											 :world-id world-id}
						main-arcs (vec
												(repeat 3
																(merge base-meta
																			 {:start start :end end
																				:ttl main-arc-life :max-ttl main-arc-life
																				:is-aoe? false})))
						aoe-arcs (->> aoe-points
													(keep (fn [pt]
																	(when (map? pt)
																		(let [life (+ 15 (rand-int 11))]
																			(merge base-meta
																						 {:start end :end pt
																							:ttl life :max-ttl life
																							:is-aoe? true})))))
													vec)
						store* (update store* :arcs update owner-key* (fnil into []) (into main-arcs aoe-arcs))]
				(client-sounds/queue-current-sound-effect!
					{:type :sound :sound-id "my_mod:em.arc_strong" :volume 0.6 :pitch 1.0})
				store*))))

(defn- tick-state!
	[store]
	(update (or store (default-thunder-bolt-fx-runtime-state)) :arcs
		(fn [owners]
			(->> owners
					 (keep (fn [[owner-key xs]]
									 (let [live-arcs (->> xs
																				(map #(update % :ttl dec))
																				(filter #(pos? (long (:ttl %))))
																				vec)]
										 (when (seq live-arcs)
											 [owner-key live-arcs]))))
					 (into {})))))

(defn- arc-ops [cam-pos {:keys [start end ttl max-ttl is-aoe?]}]
  (ru/zigzag-arc-ops cam-pos start end
    {:arc-pattern (if is-aoe? :aoe :strong)
     :life-ratio (/ (double ttl) (double (max 1 max-ttl)))}))

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (arc/tick-wiggle-phase!)
  (let [current-arcs (all-arcs)
        plan (mapcat #(arc-ops camera-pos %) current-arcs)]
    (when (seq plan)
      {:ops (vec plan)})))

(defn init!
	[]
	(fx-spec/register!
		{:id thunder-bolt-effect-id
		 :level {:initial-state (default-thunder-bolt-fx-runtime-state)
						:enqueue-state-fn enqueue-state!
						:tick-state-fn tick-state!
						:build-plan-fn build-plan}
		 :channels {:perform {:topic :thunder-bolt/fx-perform}}})
	nil)
