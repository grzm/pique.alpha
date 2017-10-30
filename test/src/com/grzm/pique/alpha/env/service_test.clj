(ns com.grzm.pique.alpha.env.service-test
  (:require
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :as ct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [com.grzm.pique.alpha.env.service :as service])
  (:import
   (java.io BufferedReader StringReader)))

(def group-gen
  (gen/not-empty gen/string-ascii))

(def expected-group-line-gen
  (gen/fmap (fn [group]
              [group (str \[ group \])])
            group-gen))

(def group-line-gen (gen/fmap second expected-group-line-gen))

(ct/defspec parses-group-lines
  (prop/for-all [[group group-line] expected-group-line-gen]
                (= group (service/parse-group-line group-line))))

(def option-gen
  (gen/tuple
    (gen/such-that #(not= service/service-keyword %)
                   (gen/elements service/keywords))
    (gen/not-empty gen/string-ascii)))

(def expected-option-line-gen
  (gen/fmap (fn [[k v]]
              [[k v] (str k "=" v)])
            option-gen))

(def option-line-gen
  (gen/fmap second expected-option-line-gen))

(ct/defspec parses-option-lines
  (prop/for-all [[kv option-line] expected-option-line-gen]
                (= kv (service/parse-option-line option-line))))

(def comment-line-gen
  (gen/fmap #(str "#" %) gen/string-ascii))

(ct/defspec parses-comment-lines
  (prop/for-all [comment-line comment-line-gen]
                (= ::service/comment (service/parse-comment-line comment-line))))

(def empty-line-gen
  (gen/return ""))

(def space-gen
  (gen/fmap #(str/join (repeat % \space))
            (gen/choose 0 5)))

(def line-gen
  (gen/fmap (fn [[ws [expected line]]]
              [expected (str ws line)])
            (gen/tuple
              space-gen
              (gen/one-of [expected-group-line-gen
                           expected-option-line-gen
                           (gen/fmap (fn [l] [::service/empty l])
                                     empty-line-gen)
                           (gen/fmap (fn [c] [::service/comment c])
                                     comment-line-gen)]))))
(ct/defspec parses-lines
  (prop/for-all [[expected line] line-gen]
                (= expected (service/parse-line line))))

(def invalid-line-gen
  (gen/not-empty gen/string-alphanumeric))

(ct/defspec throws-on-invalid-lines
  (prop/for-all [line invalid-line-gen]
                (try
                  (service/parse-line line)
                  false
                  (catch clojure.lang.ExceptionInfo e
                    (= ::service/syntax-error (:cause (ex-data e)))))))

(def service-group-gen
  (gen/fmap (fn [[group options]]
              (into [group] options))
            (gen/tuple group-gen
                       (gen/vector option-gen))))

(defn make-string-reader [s]
  (BufferedReader. (StringReader. s)))

(deftest find-service
  (testing "service is first"
    (let [service "foo"
          lines   ["[baz]"
                   "[foo]"
                   ""
                   "host=bar-val"
                   "#some stuff"
                   "port=baz-val"
                   "host=bax-val"
                   "[bar]"
                   "port=bax-val"]
          rdr     (make-string-reader (str/join "\n" lines))]
      (is (= {:host "bar-val"
              :port "baz-val"} (service/find-service service rdr)))))

  (testing "empty file"
    (let [service "foo"
          rdr     (make-string-reader "")]
      (is (nil? (service/find-service service rdr)))))

  (testing "service but no options"
    (let [service "foo"
          lines   ["[quux]"
                   "dbname=quux-val"
                   "[foo]"
                   "[bar]"
                   "port=bar-val"]
          rdr     (make-string-reader (str/join "\n" lines))]
      (is (= {} (service/find-service service rdr)))))  )
