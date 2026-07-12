(ns cn.li.ac.ability.skill-config.common
  "Common skill-config data shared by the public facade and category data namespaces.

  skill-definitions is the single source of truth for :level and
  :controllable? — defskill (dsl.clj) rejects declaring either, and
  registry/skill.clj register-skill! fills them in from here at registration
  time. Edit the entry here, not in a skill's content namespace.")

(def category-ids
  [:electromaster :meltdowner :teleporter :vecmanip])

(def skill-definitions
  ;; electromaster :level values verified against AcademyCraft original source
  ;; (Skill(id, level) constructor calls) — see cn.academy.ability.vanilla.electromaster.skill.*.
  [{:id :arc-gen :category-id :electromaster :level 1 :controllable? true}
   {:id :body-intensify :category-id :electromaster :level 3 :controllable? true}
   {:id :current-charging :category-id :electromaster :level 1 :controllable? true}
   {:id :mag-manip :category-id :electromaster :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mag-movement :category-id :electromaster :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
  {:id :mine-detect :category-id :electromaster :level 3 :controllable? false}
   {:id :railgun :category-id :electromaster :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :thunder-bolt :category-id :electromaster :level 4 :controllable? false}
   {:id :thunder-clap :category-id :electromaster :level 5 :controllable? true}

   {:id :electron-bomb :category-id :meltdowner :level 1 :controllable? false}
   {:id :electron-missile :category-id :meltdowner :level 5 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :jet-engine :category-id :meltdowner :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :light-shield :category-id :meltdowner :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :meltdowner :category-id :meltdowner :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-ray-basic :category-id :meltdowner :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-ray-expert :category-id :meltdowner :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-ray-luck :category-id :meltdowner :level 5 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :rad-intensify :category-id :meltdowner :level 1 :controllable? false}
   {:id :ray-barrage :category-id :meltdowner :level 4 :controllable? false}
   {:id :scatter-bomb :category-id :meltdowner :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}

   {:id :dim-folding-theorem :category-id :teleporter :level 1 :controllable? false}
   {:id :flashing :category-id :teleporter :level 5 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :flesh-ripping :category-id :teleporter :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :location-teleport :category-id :teleporter :level 3 :controllable? false :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mark-teleport :category-id :teleporter :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :penetrate-teleport :category-id :teleporter :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :shift-teleport :category-id :teleporter :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :space-fluct :category-id :teleporter :level 4 :controllable? false}
   {:id :threatening-teleport :category-id :teleporter :level 1 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}

   {:id :blood-retrograde :category-id :vecmanip :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :directed-blastwave :category-id :vecmanip :level 3 :controllable? false}
   {:id :directed-shock :category-id :vecmanip :level 1 :controllable? false}
   {:id :groundshock :category-id :vecmanip :level 1 :controllable? false :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :plasma-cannon :category-id :vecmanip :level 5 :controllable? false :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :storm-wing :category-id :vecmanip :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :vec-accel :category-id :vecmanip :level 2 :controllable? false}
   {:id :vec-deviation :category-id :vecmanip :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :vec-reflection :category-id :vecmanip :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}])

(def all-skill-ids
  (mapv #(get % :id) skill-definitions))

(def field-definitions
  [{:id :enabled
    :path "general.enabled"
    :section-suffix "general"
    :type :boolean
    :default true
    :comment "Whether this skill is enabled."}
   {:id :controllable
    :path "general.controllable"
    :section-suffix "general"
    :type :boolean
    :default true
    :spec-key :controllable?
    :comment "Whether this skill may be bound/controlled directly."}
   {:id :level
    :path "general.level"
    :section-suffix "general"
    :type :int
    :min 1
    :max 5
    :default 1
    :comment "Ability level required by this skill."}
   {:id :destroy-blocks
    :path "general.destroy-blocks"
    :section-suffix "general"
    :type :boolean
    :default true
    :spec-key :destroy-blocks?
    :comment "Whether this skill may destroy blocks when its implementation checks the flag."}
   {:id :damage-scale
    :path "combat.damage-scale"
    :section-suffix "combat"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill damage multiplier, applied on top of the global ability damage multiplier."}
   {:id :cp-consume-speed
    :path "consume.cp-speed"
    :section-suffix "consume"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill CP consumption multiplier used by runtime cost helpers."}
   {:id :overload-consume-speed
    :path "consume.overload-speed"
    :section-suffix "consume"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill overload consumption multiplier used by runtime cost helpers."}
   {:id :exp-incr-speed
    :path "progression.exp-incr-speed"
    :section-suffix "progression"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill experience gain multiplier."}
   {:id :cooldown-scale
    :path "cooldown.scale"
    :section-suffix "cooldown"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Multiplier applied to this skill's existing cooldown calculation."}
   {:id :cost-cp-scale
    :path "cost.cp-scale"
    :section-suffix "cost"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Multiplier applied to this skill's existing CP cost calculation."}
   {:id :cost-overload-scale
    :path "cost.overload-scale"
    :section-suffix "cost"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Multiplier applied to this skill's existing overload cost calculation."}])
