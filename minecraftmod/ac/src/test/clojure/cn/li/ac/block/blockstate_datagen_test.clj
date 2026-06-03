(ns cn.li.ac.block.blockstate-datagen-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.blockstate-datagen :as datagen]
            [cn.li.ac.block.blockstate-definition :as blockstate-def]
            [cn.li.mcmod.config :as mcmod-config]))

(deftest properties-from-schema-match-runtime-shape-test
  (with-redefs [mcmod-config/*mod-id* "my_mod"]
    (let [fusor-def (get (datagen/complex-definitions-map) :imag-fusor)
          former-def (get (datagen/complex-definitions-map) :metal-former)
          fusor (:properties fusor-def)
          former (:properties former-def)]
      (is (contains? fusor :facing))
      (is (contains? fusor :frame))
      (is (= :horizontal-facing (:type (:facing fusor))))
      (is (= 20 (count (:parts fusor-def))))
      (is (contains? former :facing))
      (is (not (contains? former :frame)))
      (is (= 4 (count (:parts former-def)))))))

(deftest ability-interferer-on-parts-test
  (with-redefs [mcmod-config/*mod-id* "my_mod"]
    (let [def (get (datagen/complex-definitions-map) :ability-interferer)]
      (is (= #{true false}
             (set (map #(get-in % [:condition :on]) (:parts def)))))
      (is (= ["my_mod:block/ability_interferer"
              "my_mod:block/ability_interf_off"]
             (map first (map :models (:parts def))))))))

(deftest blockstate-definition-uses-schema-datagen-test
  (with-redefs [mcmod-config/*mod-id* "my_mod"
                cn.li.mcmod.protocol.metadata/get-all-block-ids (fn [] [])
                cn.li.mcmod.protocol.metadata/get-block-registry-name identity]
    (let [def (blockstate-def/get-block-state-definition :metal-former)]
      (is (= "metal_former" (:registry-name def)))
      (is (= 4 (count (:parts def))))
      (is (= "my_mod:block/metal_former_north"
             (first (:models (first (:parts def)))))))))
