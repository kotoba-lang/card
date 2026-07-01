(ns kotoba.card.export-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.card :as card]
            [kotoba.card.export :as ex]))
(deftest csv-export
  (let [csv (ex/pans->csv ["4111111111111111" "bad"])]
    (is (re-find #"pan_masked,valid,network,error" csv))
    (is (re-find #"•••• 1111,yes,visa" csv))))
(deftest json-export
  (let [j (ex/pans->json ["4111111111111111"])]
    (is (re-find #"\"pan_masked\":\"•••• 1111\"" j))
    (is (re-find #"\"valid\":true" j))))
