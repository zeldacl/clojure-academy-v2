(ns cn.li.ac.ability.server.handlers.preset-handler
	"Preset request network handlers."
	(:require 
            [clojure.string :as str]
            [cn.li.ac.ability.server.handlers.common :as common]
[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.model.ability :as ability-data]
						[cn.li.ac.ability.service.command-runtime :as command-rt]
						[cn.li.ac.ability.registry.skill-query :as skill-query]))

(defn- as-kw
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- skill-id-aliases
  [skill-id]
  (when-let [sid (as-kw skill-id)]
    (let [n (name sid)
          ns (namespace sid)
          flipped (str/replace n #"[_-]" (fn [ch] (if (= ch "_") "-" "_")))
          kw (fn [stem] (if ns (keyword ns stem) (keyword stem)))]
      (cond-> #{sid}
        (not= n flipped) (conj (kw flipped))))))

(defn- skill-learned?
  [ability-data skill-id]
  (let [learned (:learned-skills ability-data #{})
        aliases (skill-id-aliases skill-id)]
    (or (some (fn [id] (contains? learned id)) aliases)
        (some (fn [id] (contains? learned (some-> id name))) aliases)
        (boolean (some (fn [learned-id]
                         (some aliases (skill-id-aliases learned-id)))
                       learned)))))

(defn- learned-controllable-slot
	[player-uuid cat-id ctrl-id]
  (let [cat-id (as-kw cat-id)
        ctrl-id (as-kw ctrl-id)]
    (when-let [skill-id (skill-query/get-skill-by-controllable cat-id ctrl-id)]
      (when (skill-learned? (:ability-data (common/get-state player-uuid)) skill-id)
        [cat-id ctrl-id]))))

(defn handle-set-preset-request
	[{:keys [preset-idx key-idx cat-id ctrl-id]} player]
	(let [uuid (uuid/player-uuid player)
				session-id (common/current-server-session-id)
        cat-id (as-kw cat-id)
        ctrl-id (as-kw ctrl-id)]
		(when uuid
			(cond
				(and (nil? cat-id) (nil? ctrl-id))
				(command-rt/run-command-in-session! session-id uuid {:command :set-preset-slot
															 :preset-idx preset-idx
															 :key-idx key-idx
															 :controllable nil})

				(and cat-id ctrl-id)
				(when-let [slot (learned-controllable-slot uuid cat-id ctrl-id)]
					(command-rt/run-command-in-session! session-id uuid {:command :set-preset-slot
															 :preset-idx preset-idx
															 :key-idx key-idx
															 :controllable slot}))))))

(defn handle-switch-preset-request
	[{:keys [preset-idx]} player]
	(let [uuid (uuid/player-uuid player)
				session-id (common/current-server-session-id)]
		(when uuid
			(command-rt/run-command-in-session! session-id uuid {:command :switch-preset
														 :preset-idx preset-idx}))))
