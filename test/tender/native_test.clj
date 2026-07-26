(ns tender.native-test
  (:require [clojure.test :refer [deftest is]]
            [kototama.native.executor :as executor]))

;; Load gate: the split must not break namespace resolution. Each extracted
;; namespace must load standalone from this repo's own dependency closure.
(deftest every-extracted-namespace-loads
  (is (some? (find-ns 'kototama.native.executor)) "kototama.native.executor must load"))

(deftest supervisor-report-validation-is-closed-and-bounded
  (let [valid? @#'executor/valid-supervisor-report?
        ok {:status :ok :result 7
            :fuel {:initial 512 :remaining 500}
            :heap {:capacity 4096 :used 32}}]
    (is (true? (valid? ok 0)))
    (is (false? (valid? (assoc-in ok [:fuel :remaining] 513) 0)))
    (is (false? (valid? (assoc ok :ambient "forbidden") 0)))
    (is (false? (valid? ok 1)))))
