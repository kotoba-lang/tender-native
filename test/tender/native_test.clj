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
            :heap {:capacity 4096 :used 32}}
        string-ok {:status :ok :result 3 :result-type :string
                   :result-utf8-hex "6869f09f9880"
                   :fuel {:initial 512 :remaining 499}
                   :heap {:capacity 4096 :used 33}}]
    (is (true? (valid? ok 0)))
    (is (true? (valid? string-ok 0)))
    (is (false? (valid? (assoc string-ok :result-utf8-hex "ff") 0)))
    (is (false? (valid? (assoc string-ok :result-utf8-hex "6869F0") 0)))
    (is (false? (valid? (assoc-in ok [:fuel :remaining] 513) 0)))
    (is (false? (valid? (assoc ok :ambient "forbidden") 0)))
    (is (false? (valid? ok 1)))))

(deftest entryless-export-contract-is-read-from-the-selected-function
  (let [contract @#'executor/entry-contract
        artifact {:program {:functions [{:name 'choose
                                         :params ['enabled 'value]
                                         :param-types [:bool :i64]
                                         :result :i64}
                                        {:name 'negate
                                         :params ['value]
                                         :param-types [:bool]
                                         :result :bool}]}}]
    (is (= {:param-types [:bool :i64] :result :i64}
           (contract artifact 'choose)))
    (is (= {:param-types [:bool] :result :bool}
           (contract artifact 'negate)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no unique typed function"
                          (contract artifact 'missing)))))

(deftest bool-i64-and-string-host-values-remain-distinct
  (let [marshal @#'executor/marshal-entry-arguments]
    (is (= [1 41] (marshal 'choose [:bool :i64] [true 41])))
    (is (= [0 Long/MIN_VALUE]
           (marshal 'choose [:bool :i64] [false Long/MIN_VALUE])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry arguments"
                          (marshal 'choose [:bool :i64] [1 41])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry arguments"
                          (marshal 'choose [:bool :i64] [true true])))
    (is (= ["s:6869f09f9880"] (marshal 'echo [:string] ["hi😀"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry arguments"
                          (marshal 'echo [:string] [41])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds native host arena"
                          (marshal 'echo [:string] [(apply str (repeat 65537 "a"))])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inputs exceed native host arena"
                          (marshal 'join [:string :string]
                                   (vec (repeat 2 (apply str (repeat 40000 "a")))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parameter type"
                          (marshal 'echo [:f64] [1.0])))))

(deftest selected-export-result-is-boxed-or-refused-by-type
  (let [admit! @#'executor/admit-entry-result!
        host-result @#'executor/host-result]
    (is (= 7 (host-result :i64 {:result 7})))
    (is (true? (host-result :bool {:result 1})))
    (is (false? (host-result :bool {:result 0})))
    (is (= "hi😀" (host-result :string {:result-utf8-hex "6869f09f9880"})))
    (is (nil? (admit! 'choose :i64)))
    (is (nil? (admit! 'negate :bool)))
    (is (nil? (admit! 'echo :string)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"result type"
                          (admit! 'measure :f64)))))
