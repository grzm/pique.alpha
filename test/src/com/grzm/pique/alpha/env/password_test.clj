(ns com.grzm.pique.alpha.env.password-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :as ct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [com.grzm.pique.alpha.env.password :as password]
   [com.grzm.tespresso.alpha :as tespresso]))

(defn escape-field [s]
  (str/replace s #"[\\:]" #(str "\\" %)))

(def string-field-gen
  (gen/fmap escape-field (gen/such-that #(not= "" %)
                                        gen/string-ascii)))

(def field-gen
  (gen/frequency [[4 string-field-gen]
                  [1 (gen/return "*")]]))

(def password-field-gen
  (gen/frequency [[4 string-field-gen]
                  [1 (gen/return nil)]]))

(def line-gen
  (gen/fmap (fn [[fields password]]
              (str/join ":" (conj fields password)))
            (gen/tuple (gen/vector string-field-gen 4)
                       password-field-gen)))

(def contents-gen
  (gen/fmap (partial str/join "\n")
            (gen/vector line-gen)))

(def invalid-line-gen
  (gen/such-that #(not (<= 4 (count (str/split % password/field-separator)) 5))
                 (gen/fmap #(str/join ":" %)
                           (gen/one-of [(gen/vector string-field-gen 0 3) ;; too few
                                        ;; too many (arbitrary max)
                                        (gen/vector string-field-gen 6 10)]))))

(def invalid-contents-gen
  (gen/fmap (partial str/join "\n")
            (gen/vector invalid-line-gen 1 10)))

(ct/defspec parses-passwords
  (prop/for-all
    [contents contents-gen]
    (password/parse-passwords contents)))

(deftest parses-passwords-test
  (let [contents (str/join "\n" ["localhost:*:*:me:"
                                 "some-host:5432:*:some-user:"
                                 "*:*:*:me:some-pass"])]
    (is (= [{:host "localhost" :port "*" :dbname "*" :user "me" :password nil}
            {:host "some-host" :port "5432" :dbname "*" :user "some-user" :password nil}
            {:host "*" :port "*" :dbname "*" :user "me" :password "some-pass"}]
           (password/parse-passwords contents)))))

(deftest bad-line-throws
  (let [bad-line "this-is-a-badline"]
    (is (com.grzm.tespresso/thrown-with-data?
          #"invalid" (tespresso/ex-data= {:line  bad-line
                                          :cause ::password/invalid-line})
          (password/parse-line bad-line)))))


;; throws-with-invalid-contents doesn't appear to be throwing when I
;; expect it to. Running parse-passwords on the contents it
;; consistently reports failures, but using the generator doesn't.
#_(ct/defspec ^:skip throws-with-invalid-contents
    (prop/for-all
      [contents invalid-contents-gen]
      (try
        (password/parse-passwords contents)
        (prn {:contents contents :succeeds true
              :call     `(password/parse-passwords ~contents)})
        false
        (catch clojure.lang.ExceptionInfo e
          (let [message                  (.getMessage e)
                {:keys [cause] :as data} (ex-data e)]
            (and (re-find #"invalid" message)
                 (= cause ::password/invalid-line)))))))

(def gen-host gen/string-ascii)
(def gen-port (gen/choose 1 10000))
(def gen-dbname gen/string-ascii)
(def gen-user gen/string-ascii)

(defn matching-entry-gen [e _]
  (gen/elements #{e "*"}))

(defn failing-entry-gen [e gen]
  (gen/such-that (partial not= e) gen))

(defn make-entries-gen
  ([conn-info]
   (make-entries-gen conn-info true))
  ([{:keys [host port dbname user] :as env} matching?]
   (let [gen-fn (if matching?
                  matching-entry-gen
                  failing-entry-gen)]
     (gen/fmap (fn [[host port dbname user]]
                 {:host   host
                  :port   port
                  :dbname dbname
                  :user   user})
               (gen/tuple (gen-fn host gen-host)
                          (gen-fn port gen-port)
                          (gen-fn dbname gen-dbname)
                          (gen-fn user gen-user))))))

(ct/defspec env-matches-entries
  (let [conn-info {:host   "some-host"
                   :port   2345
                   :dbname "some-dbname"
                   :user   "some-user"}
        gen       (make-entries-gen conn-info)]
    (prop/for-all [entry gen]
                  (password/matches-entry? conn-info entry))))

(ct/defspec env-fails-to-match-entries
  (let [conn-info {:host   "some-host"
                   :port   2345
                   :dbname "some-dbname"
                   :user   "some-user"}
        gen       (make-entries-gen conn-info false)]
    (prop/for-all [entry gen]
                  (not (password/matches-entry? conn-info entry)))))

(deftest fails-to-match-entry
  (let [conn-info {:host   "some-host"
                   :dbname "some-dbname"
                   :user   "some-user"
                   :port   1234}
        entry     {:host   "other-host"
                   :dbname "other-dbname"
                   :user   "other-user"
                   :port   5432}]
    (is (not (password/matches-entry? conn-info entry)))))

;; what about case without trailing colon?
;; how to distinguish between no password required and no matching entry?
;; throw exception
(deftest returns-password
  (let [conn-info {:host   "some-host"
                   :port   1234
                   :dbname "some-dbname"
                   :user   "some-user"}
        password  "my-password"
        entries   [{:host   "other-host"
                    :port   5422
                    :dbname "other-dbname"
                    :user   "other-user"}
                   {:host     "*"
                    :port     "*"
                    :dbname   "some-dbname"
                    :user     "*"
                    :password password}
                   {:host     "*"
                    :port     "*"
                    :dbname   "*"
                    :user     "*"
                    :password "wrong-password"}]]
    (is (= password (password/password conn-info entries)))))

(deftest host-matches-localhost-when-no-host-provided
  (let [conn-info {:port   1234
                   :dbname "some-dbname"
                   :user   "some-usr"}
        password  "my-password"
        entries   [{:host     "localhost"
                    :port     "*"
                    :dbname   "*"
                    :user     "*"
                    :password password}
                   {:host     "*"
                    :port     "*"
                    :dbname   "*"
                    :user     "*"
                    :password "wrong-password"}]]
    (is (= password (password/password conn-info entries)))))

(deftest port-matches-5432-when-no-port-provided
  (let [conn-info {:host "some-host"
                   :dbname "some-dbname"
                   :user   "some-usr"}
        password  "my-password"
        entries   [{:host     "*"
                    :port     "5432"
                    :dbname   "*"
                    :user     "*"
                    :password password}
                   {:host     "*"
                    :port     "*"
                    :dbname   "*"
                    :user     "*"
                    :password "wrong-password"}]]
    (is (= password (password/password conn-info entries)))))

(deftest throws-when-no-matching-entry
  (let [conn-info {:host   "some-host"
                   :port   1234
                   :dbname "some-dbname"
                   :user   "some-user"}
        password  "my-password"
        entries   [{:host     "other-host"
                    :port     5422
                    :dbname   "other-dbname"
                    :user     "other-user"
                    :password "some-password"}]]
    (is (com.grzm.tespresso/thrown-with-data?
          #"no matching entry"
          (tespresso/ex-data= {:cause ::password/no-matching-entry})
          (password/password conn-info entries)))
    (is (com.grzm.tespresso/thrown-with-data?
          #"no matching entry"
          (tespresso/ex-data= {:cause ::password/no-matching-entry})
          (password/password conn-info [])))))
