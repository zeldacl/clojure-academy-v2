(ns cn.li.ac.ability.server.handlers.preset-handler
	"Preset request network handlers."
	(:require 
            [cn.li.ac.ability.server.handlers.common :as common]
[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.model.ability :as ability-data]
						[cn.li.ac.ability.service.command-runtime :as command-rt]
						[cn.li.ac.ability.registry.skill-query :as skill-query]))

(defn- as-kw
  "NBT / some transports rehydrate keywords as strings."
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- skill-learned?
  [ability-data skill-id]
  (let [learned (:learned-skills ability-data #{})
        sid (as-kw skill-id)]
    (or (contains? learned sid)
        (contains? learned skill-id)
        (contains? learned (some-> sid name)))))

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
