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
   textures/abilities/<category>/skills/<stem>.png

   Generic course passives (brain/mind) keep one shared icon bank under
   textures/abilities/generic/skills/, matching main course-chain."
  [skill-id]
  (let [stem (or (get icon-stem-overrides skill-id)
                 (when skill-id (str/replace (name skill-id) "-" "_")))]
    (if (#{"brain_course" "brain_course_advanced" "mind_course"} stem)
      (str "textures/abilities/generic/skills/" stem ".png")
      (when-let [cat (or (some-> (skill/raw-skill skill-id) :category-id)
                         (get-in skill-config/skill-definitions-by-id [skill-id :category-id]))]
        (str "textures/abilities/" (name cat) "/skills/" stem ".png")))))

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

(defn- skill-id-aliases
  "Accept kebab/snake and qualified/unqualified spellings for the same skill."
  [skill-id]
  (when-let [sid (as-kw skill-id)]
    (let [n (name sid)
          ns (namespace sid)
          flipped (str/replace n #"[_-]" (fn [ch] (if (= ch "_") "-" "_")))
          kw (fn [stem] (if ns (keyword ns stem) (keyword stem)))]
      (cond-> #{sid}
        (not= sid (kw n)) (conj (kw n))
        (not= n flipped) (conj (kw flipped))
        (and ns (not= sid (keyword n))) (conj (keyword n))))))

(defn- definition-for
  [skill-id]
  (some skill-config/skill-definitions-by-id (skill-id-aliases skill-id)))

(defn- canonical-category
  "Prefer skill-definitions category over registry/EDN (:migrated) drift."
  [skill-id registry-category]
  (or (some-> (definition-for skill-id) :category-id as-kw)
      (as-kw registry-category)))

(defn controllable-key
  "Return canonical [category-id ctrl-id] for a skill.

  Category comes from skill-definitions when present so preset bind requests
  match server resolution even if the live registry still carries :migrated
  from skills-v4 EDN."
  [skill-id]
  (let [sid (as-kw skill-id)
        aliases (or (skill-id-aliases sid) #{})
        s (or (skill/get-skill sid)
              (some skill/get-skill (disj aliases sid)))
        defn (definition-for sid)]
    (when (or s defn)
      (let [id (or (:id s) (:id defn) sid)]
        [(canonical-category id (:category-id s))
         (as-kw (or (:ctrl-id s) id))]))))

(defn get-skill-by-controllable
  "Resolve skill id from a [category ctrl] pair.

  Identity match only (category + ctrl-id). Do not gate on live Forge
  config enabled/controllable — those flags empty the preset slot paint
  and HUD after a successful bind while the selector (structural canControl)
  still works.

  Category comparison uses skill-definitions when available so
  electromaster/arc-gen still resolves when the registry entry was projected
  with EDN :skill {:category :migrated}. Falls back to skill-definitions
  alone when the live registry is empty (client picker synthesizes the same
  table)."
  [category-id ctrl-id]
  (let [category-id (as-kw category-id)
        ctrl-id (as-kw ctrl-id)
        ctrl-aliases (or (skill-id-aliases ctrl-id) #{})
        from-registry
        (some (fn [[sid base]]
                (let [effective-cat (canonical-category sid (:category-id base))
                      base-ctrl (as-kw (or (:ctrl-id base) sid))]
                  (when (and (= effective-cat category-id)
                             (or (= base-ctrl ctrl-id)
                                 (contains? ctrl-aliases base-ctrl)
                                 (contains? ctrl-aliases sid)))
                    sid)))
              (skill/raw-skill-entries))]
    (or from-registry
        (when-let [defn (definition-for ctrl-id)]
          (when (= category-id (as-kw (:category-id defn)))
            (:id defn))))))

