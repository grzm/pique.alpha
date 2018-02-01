(ns com.grzm.pique.alpha.env.environment-test
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :as ct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [com.grzm.pique.alpha.env.environment :as env]
   [com.grzm.pique.alpha.env.service :as service]
   [com.grzm.tespresso.tools-logging.alpha :refer [with-logging]])
  (:import
   (java.io BufferedReader StringReader)))

(defn make-string-reader [s]
  (BufferedReader. (StringReader. s)))

(deftest parse-system-env
  (let [sys-env {"PGUSER"        "some-user"
                 "PGDATABASE"    "some-db"
                 "PGPASSWORD"    "some-pass"
                 "PGPORT"        "5466"
                 "PGSERVICEFILE" "/user/myuser/some_service_file.conf"
                 "PGSERVICE"     "some-service"}]
    (is (= {:pguser        "some-user"
            :pgdatabase    "some-db"
            :pgpassword    "some-pass"
            :pgport        "5466"
            :pgservicefile "/user/myuser/some_service_file.conf"
            :pgservice     "some-service"}
           (env/parse-system-env sys-env)))))

(defn read-service-file [er servicefile]
  (when servicefile
    (service/find-service (:pgservice (env/read-system-env er))
                          (make-string-reader servicefile))))

(defrecord TestEnv [system-env
                    env-passwords
                    user-passwords
                    env-service
                    user-service
                    env-sysconfdir-service
                    sysconfdir-service]
  env/EnvironmentReader
  (-system-env [er] system-env)
  (-env-passwords [er] env-passwords)
  (-user-passwords [er] user-passwords)

  (-env-service [er] (read-service-file er env-service))
  (-user-service [er] (read-service-file er user-service))
  (-env-sysconfdir-service [er] (read-service-file er env-sysconfdir-service))
  (-sysconfdir-service [er] (read-service-file er sysconfdir-service)))

(deftest test-env-reads-environment
  (let [pgpassword "env-password"
        env        (map->TestEnv {:system-env {"PGPASSWORD" pgpassword}})]
    (is (= {:pgpassword pgpassword}
           (env/read-system-env env)))))

(deftest chooses-env-password-over-password-file
  (let [pgpassword           "pgpassword-password"
        pgpassfile-password  "pgpassfile-password"
        user-pgpass-password "pgpass-password"
        pgpassfile           "some/path/we/ignore"
        match-anything       "*:*:*:*:"
        env-map              {:env-passwords  (str match-anything pgpassfile-password)
                              :user-passwords (str match-anything user-pgpass-password)}]

    (testing "use PGPASSWORD if set"
      (let [env (map->TestEnv (assoc env-map
                                     :system-env {"PGPASSWORD" pgpassword
                                                  "PGPASSFILE" pgpassfile
                                                  "PGUSER"     "some-user"
                                                  "PGDATABASE" "some-dbname"}))]
        (is (= pgpassword (env/password env))))

      (let [env (map->TestEnv (assoc env-map
                                     :system-env {"PGPASSWORD" pgpassword
                                                  "PGUSER"     "some-user"
                                                  "PGDATABASE" "some-dbname"}))]
        (is (= pgpassword (env/password env)))))

    (testing "use PGPASSWORDFILE over user password file"
      (let [env (map->TestEnv (assoc env-map
                                     :system-env {"PGPASSFILE" pgpassfile
                                                  "PGUSER"     "some-user"
                                                  "PGDATABASE" "some-dbname"}))]
        (is (= pgpassfile-password (env/password env)))))

    (testing "use user password file if neither PGPASSFILE or PGPASSWORD is set"
      (let [env (map->TestEnv (assoc env-map
                                     :system-env {"PGUSER"     "some-user"
                                                  "PGDATABASE" "some-dbname"}))]
        (is (= user-pgpass-password (env/password env)))))

    (testing "warn if no password is found in password file"
      (with-logging [#{:trace} log-entry]
        (let [env (map->TestEnv {:user-passwords "some-host:some-dbname:some-port:some-user:some-pass"
                                 :system-env     {"PGUSER"     "failing-user"
                                                  "PGDATABASE" "some-dbname"}})]
          (env/password env)
          (is (= [["com.grzm.pique.alpha.env.environment" :trace nil
                   "No matching entry found: {:dbname \"some-dbname\", :user \"failing-user\"}"]]
                 @log-entry)))))

    (testing "don't look up password if dbname and user aren't provided:"
      (with-logging [#{:trace} log-entry]
        (testing "missing both PGDATABASE and PGUSER"
          (is (nil? (env/password (map->TestEnv env-map))))
          (is (empty? @log-entry)))
        (testing "have only PGUSER"
          (is (nil? (env/password (map->TestEnv (assoc env-map :system-env {"PGUSER" "some-user"})))))
          (is (empty? @log-entry)))
        (testing "have only PGDATABASE"
          (is (nil? (env/password (map->TestEnv (assoc env-map :system-env {"PGDATABASE" "some-dbname"})))))
          (is (empty? @log-entry)))))))

(deftest test-services
  (let [service       "foo"
        pgservicefile "/some/path/to/pgservicefile"
        pgsysconfdir  "/some/path/to/pgsysconfdif"
        env-map       {:env-service            (str/join "\n" ["[foo]"
                                                               "host=pgservicefile-host"])
                       :user-service           (str/join "\n" ["[foo]"
                                                               "host=user-host"])
                       :env-sysconfdir-service (str/join "\n" ["[foo]"
                                                               "host=pgsysconfdir-host"])
                       :sysconfdir-service     (str/join "\n" ["[foo]"
                                                               "host=sysconfdir-host"])}]
    (testing "no pgservice, no service options"
      (let [env (map->TestEnv env-map)]
        (is (nil? (env/service env)))))

    (testing "use pgservicefile if specified"
      (let [env (map->TestEnv
                  (assoc env-map
                         :system-env {"PGSERVICE"     service
                                      "PGSERVICEFILE" pgservicefile}))]
        (is (= {:host "pgservicefile-host"} (env/service env)))))

    (testing "prefer user service file if available"
      (let [env (map->TestEnv (assoc env-map
                                     :system-env {"PGSERVICE" service}))]
        (is (= {:host "user-host"} (env/service env)))))

    (testing "prefer pgsysconf service file if available and no user file"
      (let [env (map->TestEnv (-> env-map
                                  (assoc :system-env {"PGSERVICE"    service
                                                      "PGSYSCONFDIR" pgsysconfdir})
                                  (dissoc :user-service)))]
        (is (= {:host "pgsysconfdir-host"} (env/service env)))))

    (testing "prefer sysconf service file if available and no user file"
      (let [env (map->TestEnv (-> env-map
                                  (assoc :system-env {"PGSERVICE" service})
                                  (dissoc :user-service)))]
        (is (= {:host "sysconfdir-host"} (env/service env)))))

    (testing "nil if no conf available"
      (let [env (map->TestEnv {:system-env {"PGSERVICE" service}})]
        (is (nil? (env/service env)))))))


(deftest test-services-with-env
  (let [service  "foo"
        env-map  {:user-passwords "*:*:*:*:pgpass-password"
                  :user-service   (str/join "\n"
                                            ["[foo]"
                                             "application_name=service-application-name"
                                             "client_encoding=service-encoding"
                                             "connect_timeout=0"
                                             "dbname=service-dbname"
                                             "gsslib=service-gsslib"
                                             "host=service-host"
                                             "hostaddr=service-hostaddr"
                                             "keepalives=0"
                                             "krbsrvname=service-krbsrvname"
                                             "options=service-options"
                                             "port=6543"
                                             "requirepeer=service-requirepeer"
                                             "requiressl=0"
                                             "sslcert=service-sslcert"
                                             "sslcompression=0"
                                             "sslcrl=service-sslcrl"
                                             "sslkey=service-sslkey"
                                             "sslmode=verify-ca"
                                             "sslrootcert=service-rootcert"
                                             "user=service-user"])}
        env-vars {"PGAPPNAME"         "env-appname"
                  "PGCLIENTENCODING"  "env-encoding"
                  "PGCONNECT_TIMEOUT" "10"
                  "PGDATABASE"        "env-dbname"
                  "PGGSSLIB"          "env-gsslib"
                  "PGHOST"            "env-host"
                  "PGHOSTADDR"        "env-hostaddr"
                  "PGKRBSRVNAME"      "env-krbsrvname"
                  "PGOPTIONS"         "env-options"
                  "PGPASSWORD"        "env-password"
                  "PGPORT"            "2345"
                  "PGREQUIREPEER"     "env-requirepeer"
                  "PGREQUIRESSL"      "1"
                  "PGSERVICE"         "foo"
                  "PGSSLCERT"         "env-sslcert"
                  "PGSSLCOMPRESSION"  "1"
                  "PGSSLCRL"          "env-sslcrl"
                  "PGSSLKEY"          "env-sslkey"
                  "PGSSLMODE"         "verify-all"
                  "PGSSLROOTCERT"     "env-rootcert"
                  "PGUSER"            "env-user"}]
    (testing "env shadows user service"
      (let [env                 (map->TestEnv (assoc env-map :system-env env-vars))
            expected-conn-info* {:application-name "env-appname"
                                 :client-encoding  "env-encoding"
                                 :connect-timeout  "10"
                                 :dbname           "env-dbname"
                                 :gsslib           "env-gsslib"
                                 :host             "env-host"
                                 :hostaddr         "env-hostaddr"
                                 :krbsrvname       "env-krbsrvname"
                                 :keepalives       "0"
                                 :options          "env-options"
                                 :password         "env-password",
                                 :port             "2345"
                                 :requirepeer      "env-requirepeer"
                                 :requiressl       "1"
                                 :sslcert          "env-sslcert"
                                 :sslcompression   "1"
                                 :sslcrl           "env-sslcrl"
                                 :sslkey           "env-sslkey"
                                 :sslmode          "verify-all"
                                 :sslrootcert      "env-rootcert"
                                 :user             "env-user"}
            expected-conn-info  {:application-name "env-appname"
                                 :client-encoding  "env-encoding"
                                 :connect-timeout  10
                                 :dbname           "env-dbname"
                                 :gsslib           "env-gsslib"
                                 :host             "env-host"
                                 :hostaddr         "env-hostaddr"
                                 :krbsrvname       "env-krbsrvname"
                                 :keepalives       0
                                 :options          "env-options"
                                 :password         "env-password",
                                 :port             2345
                                 :requirepeer      "env-requirepeer"
                                 :requiressl       1
                                 :sslcert          "env-sslcert"
                                 :sslcompression   1
                                 :sslcrl           "env-sslcrl"
                                 :sslkey           "env-sslkey"
                                 :sslmode          "verify-all"
                                 :sslrootcert      "env-rootcert"
                                 :user             "env-user"}
            expected-spec       {:ApplicationName    "env-appname",
                                 :connectTimeout     10,
                                 :dbname             "env-dbname",
                                 :dbtype             "postgresql"
                                 :gsslib             "env-gsslib",
                                 :host               "env-host",
                                 :kerberosServerName "env-krbsrvname",
                                 :password           "env-password",
                                 :port               2345,
                                 :ssl                true,
                                 :sslcert            "env-sslcert",
                                 :sslkey             "env-sslkey",
                                 :sslmode            "verify-all",
                                 :sslrootcert        "env-rootcert",
                                 :tcpKeepAlive       false,
                                 :user               "env-user"}]
        (is (= expected-conn-info* (env/conn-info* env)) "conn-info*")
        (is (= expected-conn-info (env/conn-info env)) "conn-info")
        (is (= expected-spec (env/jdbc-spec env)) "spec")))

    (testing "limited-env user service"
      (let [env                 (map->TestEnv (assoc env-map :system-env {"PGSERVICE" "foo"}))
            expected-conn-info* {:application-name "service-application-name"
                                 :client-encoding  "service-encoding"
                                 :connect-timeout  "0"
                                 :dbname           "service-dbname"
                                 :gsslib           "service-gsslib"
                                 :host             "service-host"
                                 :hostaddr         "service-hostaddr"
                                 :keepalives       "0"
                                 :krbsrvname       "service-krbsrvname"
                                 :options          "service-options"
                                 :port             "6543"
                                 :requirepeer      "service-requirepeer"
                                 :requiressl       "0"
                                 :sslcert          "service-sslcert"
                                 :sslcompression   "0"
                                 :sslcrl           "service-sslcrl"
                                 :sslkey           "service-sslkey"
                                 :sslmode          "verify-ca"
                                 :sslrootcert      "service-rootcert"
                                 :user             "service-user"}
            expected-conn-info  {:application-name "service-application-name"
                                 :client-encoding  "service-encoding"
                                 :connect-timeout  0
                                 :dbname           "service-dbname"
                                 :gsslib           "service-gsslib"
                                 :host             "service-host"
                                 :hostaddr         "service-hostaddr"
                                 :keepalives       0
                                 :krbsrvname       "service-krbsrvname"
                                 :options          "service-options"
                                 :password         "pgpass-password"
                                 :port             6543
                                 :requirepeer      "service-requirepeer"
                                 :requiressl       0
                                 :sslcert          "service-sslcert"
                                 :sslcompression   0
                                 :sslcrl           "service-sslcrl"
                                 :sslkey           "service-sslkey"
                                 :sslmode          "verify-ca"
                                 :sslrootcert      "service-rootcert"
                                 :user             "service-user"}
            expected-spec       {:ApplicationName    "service-application-name",
                                 :connectTimeout     0,
                                 :dbname             "service-dbname",
                                 :dbtype             "postgresql"
                                 :gsslib             "service-gsslib",
                                 :host               "service-host",
                                 :kerberosServerName "service-krbsrvname",
                                 :password           "pgpass-password",
                                 :port               6543,
                                 :ssl                false,
                                 :sslcert            "service-sslcert",
                                 :sslkey             "service-sslkey",
                                 :sslmode            "verify-ca",
                                 :sslrootcert        "service-rootcert",
                                 :tcpKeepAlive       false,
                                 :user               "service-user"}]
        (is (= expected-conn-info* (env/conn-info* env)) "conn-info*")
        (is (= expected-conn-info (env/conn-info env)) "conn-info")
        (is (= expected-spec (env/jdbc-spec env)) "spec")))))

(deftest no-password-in-spec-if-no-password-found
  (let [dbname "pajamas-db"
        env (map->TestEnv {:system-env {"PGDATABASE" dbname}})
        expected-spec {:dbtype "postgresql"
                       :dbname dbname}]
    (is (= expected-spec (env/jdbc-spec env)))))
