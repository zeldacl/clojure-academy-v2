(ns cn.li.ac.ability.skill-destroy-gate-test
  "Skill-level destroy gate — upstream Skill.shouldDestroyBlocks()
   (getOptionalBool(\"destroy_blocks\", true)): a skill whose config disables
   block destruction must not break blocks even when the global Settings
   \"Destroy blocks\" toggle is on."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.registry.skill :as skill-reg]))

;; Reset through reset-skill-registry-for-test!, not through installing a fresh
;; empty runtime: install-skill-registry-runtime! deliberately refuses to let an
;; empty seed clobber a populated registry (runtime-hooks install an empty
;; container before content load, and a second install would wipe skills), so it
;; is no longer a reset. Without an explicit reset the first deftest's
;; :test-skill survives into the second and the destroy gate reads stale config.
(defn- with-skill [skill-id spec f]
  (skill-reg/reset-skill-registry-for-test!)
  (skill-reg/register-skill! spec)
  (try
    (f)
    (finally
      (skill-reg/reset-skill-registry-for-test!))))

(deftest skill-destroy-allowed-defaults-to-true-test
  (with-skill :test-skill {:id :test-skill :category-id :test-cat :level 1 :pattern :instant :actions {:perform! (fn [& _] nil)}}
    (fn []
      (is (true? (skill-config/destroy-blocks-enabled? :test-skill))
          "missing :destroy-blocks? defaults to true like upstream"))))

(deftest skill-destroy-allowed-honors-config-test
  (with-skill :test-skill {:id :test-skill :category-id :test-cat :level 1 :pattern :instant
                           :actions {:perform! (fn [& _] nil)}
                           :destroy-blocks? false}
    (fn []
      (is (false? (skill-config/destroy-blocks-enabled? :test-skill))
          "config-disabled skill may not destroy blocks"))))

(deftest skill-destroy-allowed-unknown-skill-safe-test
  (is (true? (skill-config/destroy-blocks-enabled? :no-such-skill))
      "unknown skill stays permissive (no registry entry)"))
