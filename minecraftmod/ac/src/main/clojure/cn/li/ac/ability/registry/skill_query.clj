(ns cn.li.ac.ability.registry.skill-query
	"Query helpers for effective AC skill specs."
	(:require [cn.li.ac.ability.registry.skill :as skill]
					[cn.li.ac.ability.skill-config :as skill-config]
					[cn.li.ac.config.modid :as modid]
                    [cn.li.mcmod.i18n :as i18n]
					[clojure.string :as str]))

(defn- as-kw
  "NBT/network may rehydrate keywords as strings; normalize for comparisons."
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn list-skills
	"Return all effective skill specs as a realized vector."
	[]
	(mapv skill-config/apply-skill-overrides (skill/raw-skills)))

(defn get-skills-for-category
	[cat-id]
  (let [cat-id (as-kw cat-id)]
	  (into []
			  (filter #(= (as-kw (:category-id %)) cat-id))
			  (list-skills))))

(defn get-controllable-skills-for-category
	[cat-id]
  (let [cat-id (as-kw cat-id)]
	  (into []
			  (filter #(and (= (as-kw (:category-id %)) cat-id) (:controllable? %)))
			  (list-skills))))

(defn get-controllable-skills-at-level
	[cat-id level]
  (let [cat-id (as-kw cat-id)]
	  (into []
			  (filter #(and (= (as-kw (:category-id %)) cat-id)
									   (:controllable? %)
									   (= (:level %) level)))
			  (list-skills))))

(defn can-control?
	[skill-id]
	(when-let [s (skill/get-skill skill-id)]
		(and (:enabled s) (:controllable? s))))

(defn get-skill-full-id
	[skill-id]
	(when-let [s (skill/get-skill skill-id)]
		(str (name (:category-id s)) "/" (name skill-id))))

(def ^:private icon-stem-overrides
  "AcademyCraft texture stems that diverge from skill-id kebab→snake."
  {:blood-retrograde "blood_retro"
   :directed-blastwave "dir_blast"
   :directed-shock "dir_shock"
   :groundshock "ground_shock"
   :shift-teleport "shift_tp"
   :current-charging "charging"
   :mine-ray "mine_ray_basic"})

(defn- default-skill-icon-path
  "Convention used by shipped textures when a skill EDN omits :icon:
   textures/abilities/<category>/skills/<stem>.png"
  [skill-id]
  (when-let [cat (or (some-> (skill/raw-skill skill-id) :category-id)
                     (get-in skill-config/skill-definitions-by-id [skill-id :category-id]))]
    (let [stem (or (get icon-stem-overrides skill-id)
                   (str/replace (name skill-id) "-" "_"))]
      (str "textures/abilities/" (name cat) "/skills/" stem ".png"))))

(defn get-skill-icon-path
	[skill-id]
	(let [explicit (get-in (skill/raw-skill skill-id) [:icon] "")
        icon (if (seq explicit)
               explicit
               (or (default-skill-icon-path skill-id) ""))]
		;; Content skill :icon values are bare paths ("textures/abilities/...");
		;; a namespace-less ResourceLocation resolves against "minecraft:" and
		;; 404s into the checkerboard texture. Normalize here — the single query
		;; point every consumer (HUD slots, preset editor, selector) goes through.
		(if (and (seq icon) (not (str/includes? icon ":")))
			(str modid/MOD-ID ":" icon)
			icon)))

(defn skill-display-name
  "Return the localized display name for a skill, with stable raw fallbacks."
  [skill-id]
  (let [spec (skill/get-skill skill-id)
        nk (:name-key spec)]
    (if nk
      (let [translated (i18n/translate nk)]
        (if (not= translated nk)
          translated
          (or (:name spec) (some-> skill-id name))))
      (or (:name spec) (some-> skill-id name)))))

(defn controllable-key
	[skill-id]
	(when-let [s (skill/get-skill skill-id)]
		[(:category-id s) (or (:ctrl-id s) skill-id)]))

(defn get-skill-by-controllable
	"Resolve skill id from a [category ctrl] pair.

  Identity match only (category + ctrl-id). Do not gate on live Forge
  config enabled/controllable — those flags empty the preset slot paint
  and HUD after a successful bind while the selector (structural canControl)
  still works."
	[category-id ctrl-id]
  (let [category-id (as-kw category-id)
        ctrl-id (as-kw ctrl-id)]
    (some (fn [[sid base]]
            (when (and (= (as-kw (:category-id base)) category-id)
                       (= (as-kw (or (:ctrl-id base) sid)) ctrl-id))
              sid))
          (skill/raw-skill-entries))))
