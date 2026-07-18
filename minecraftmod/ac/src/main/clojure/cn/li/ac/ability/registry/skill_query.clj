(ns cn.li.ac.ability.registry.skill-query
	"Query helpers for effective AC skill specs."
	(:require [cn.li.ac.ability.registry.skill :as skill]
					[cn.li.ac.ability.skill-config :as skill-config]
					[cn.li.ac.config.modid :as modid]
					[clojure.string :as str]))

(defn list-skills
	"Return all effective skill specs as a realized vector."
	[]
	(mapv skill-config/apply-skill-overrides (skill/raw-skills)))

(defn get-skills-for-category
	[cat-id]
	(into []
			(filter #(= (:category-id %) cat-id))
			(list-skills)))

(defn get-controllable-skills-for-category
	[cat-id]
	(into []
			(filter #(and (= (:category-id %) cat-id) (:controllable? %)))
			(list-skills)))

(defn get-controllable-skills-at-level
	[cat-id level]
	(into []
			(filter #(and (= (:category-id %) cat-id)
									 (:controllable? %)
									 (= (:level %) level)))
			(list-skills)))

(defn can-control?
	[skill-id]
	(when-let [s (skill/get-skill skill-id)]
		(and (:enabled s) (:controllable? s))))

(defn get-skill-full-id
	[skill-id]
	(when-let [s (skill/get-skill skill-id)]
		(str (name (:category-id s)) "/" (name skill-id))))

(defn get-skill-icon-path
	[skill-id]
	(let [icon (get-in (skill/raw-skill skill-id) [:icon] "")]
		;; Content skill :icon values are bare paths ("textures/abilities/...");
		;; a namespace-less ResourceLocation resolves against "minecraft:" and
		;; 404s into the checkerboard texture. Normalize here — the single query
		;; point every consumer (HUD slots, preset editor, selector) goes through.
		(if (and (seq icon) (not (str/includes? icon ":")))
			(str modid/MOD-ID ":" icon)
			icon)))

(defn controllable-key
	[skill-id]
	(when-let [s (skill/get-skill skill-id)]
		[(:category-id s) (or (:ctrl-id s) skill-id)]))

(defn get-skill-by-controllable
	[category-id ctrl-id]
	(some (fn [[sid base]]
				(let [s (skill-config/apply-skill-overrides base)]
					(when (and (= (:category-id s) category-id)
									 (:enabled s)
									 (:controllable? s)
									 (= (or (:ctrl-id s) sid) ctrl-id))
						sid)))
				(skill/raw-skill-entries)))
