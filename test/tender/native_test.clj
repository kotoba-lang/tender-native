(ns tender.native-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.native.executor]))

;; Load gate: the split must not break namespace resolution. Each extracted
;; namespace must load standalone from this repo's own dependency closure.
(deftest every-extracted-namespace-loads
  (is (some? (find-ns 'kototama.native.executor)) "kototama.native.executor must load"))
