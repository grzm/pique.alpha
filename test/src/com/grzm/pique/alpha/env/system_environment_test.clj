(ns com.grzm.pique.alpha.env.system-environment-test
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :as ct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [com.grzm.pique.alpha.env.system-environment :as sys-env]
   [com.grzm.tespresso.alpha :as tespresso]
   [com.grzm.tespresso.tools-logging.alpha :refer [with-logging]])
  (:import
   (java.io File)
   (java.nio.file Files Paths)
   (java.nio.file.attribute PosixFilePermission)))

;; will skip when non-posix
(def valid-perms-gen
  (let [req-perms #{PosixFilePermission/OWNER_READ}]
    (gen/fmap (fn [opt-perms]
                (set/union opt-perms req-perms))
              (gen/elements #{#{PosixFilePermission/OWNER_WRITE} #{}}))))

(gen/sample valid-perms-gen)

(def invalid-perms-gen
  (gen/fmap (fn [[invalid-perms valid-perms]]
              (set/union invalid-perms valid-perms))
            (gen/tuple (gen/not-empty
                         (gen/set (gen/elements #{PosixFilePermission/OTHERS_WRITE
                                                  PosixFilePermission/OTHERS_READ
                                                  PosixFilePermission/GROUP_WRITE
                                                  PosixFilePermission/GROUP_READ})))
                       valid-perms-gen)))

(ct/defspec valid-password-file-permissions
  (prop/for-all [perms valid-perms-gen]
                (sys-env/valid-permissions?* perms)))

(ct/defspec invalid-password-file-permissions
  (prop/for-all [perms invalid-perms-gen]
                (not (sys-env/valid-permissions?* perms))))

(defn temp-dir []
  (Paths/get "tmp" (make-array String 0)))

(defn set-perms [path perms]
  (Files/setPosixFilePermissions path perms))

(defn create-temp-password-file [contents perms]
  (let [file (File/createTempFile "pique.env.test-" ".pgpass"
                                  (io/file (str (temp-dir))))]
    (.deleteOnExit file)
    (spit file contents)
    (set-perms (.toPath file) perms)
    file))

(ct/defspec ^:io can-read-password-file-with-correct-perms 10
  (let [contents "some-host:6543:some-dbname:some-user:some-password"]
    (prop/for-all [perms valid-perms-gen]
                  (let [password-file (create-temp-password-file contents perms)
                        read-contents (sys-env/read-password-file password-file)]
                    (= contents read-contents)))))

(ct/defspec ^:io cannot-read-password-file-with-invalid-perms 20
  (let [contents "some-host:6543:some-dbname:some-user:some-password"]
    (prop/for-all [perms invalid-perms-gen]
                  (let [password-file (create-temp-password-file contents perms)]
                    (with-logging [#{:warn} log-entries]
                      (sys-env/read-password-file password-file)
                      (let [[ns level _ msg] (first @log-entries)]
                        (and
                          (= ns "com.grzm.pique.alpha.env.system-environment")
                          (= :warn level)
                          (re-find #"password file .+ has group or world access" msg))))))))

(deftest user-filenames
  (let [user-dir (sys-env/->UserHome "/some/home")]
    (is (= "/some/home/.pgpass" (str (sys-env/password-file user-dir))))
    (is (= "/some/home/.pg_service.conf" (str (sys-env/service-file user-dir)))))
  (let [user-dir (sys-env/->AppData "/some/appdata")]
    (is (= "/some/appdata/postgresql/pgpass.conf" (str (sys-env/password-file user-dir))))
    (is (= "/some/appdata/postgresql/.pg_service.conf" (str (sys-env/service-file user-dir))))))

(defrecord TestSysConfDir [sysconfdir]
  sys-env/SysConfDir
  (-sysconfdir [scd]
    sysconfdir))

(defrecord TestEnv [env]
  sys-env/Env
  (-getenv [e]
    env))

(deftest pgservice-filenames
  (let [sys-env (sys-env/map->SystemEnvironment
                  {:user-dir   (sys-env/->UserHome "/some/home")
                   :sysconfdir (->TestSysConfDir "/etc/pg/sysconfdir")
                   :env        (->TestEnv
                                 {"PGSERVICE"     "foo-service"
                                  "PGSERVICEFILE" "/path/to/custom-pgservice.conf"
                                  "PGSYSCONFDIR"  "/some/pgsysconfdir"})})]
    (is (instance? File (sys-env/user-service-file sys-env)))
    (is (= "/some/home/.pg_service.conf" (str (sys-env/user-service-file sys-env))))
    (is (instance? File (sys-env/env-service-file sys-env)))
    (is (= "/path/to/custom-pgservice.conf" (str (sys-env/env-service-file sys-env))))
    (is (instance? File (sys-env/env-sysconfdir-service-file sys-env)))
    (is (= "/some/pgsysconfdir/pg_service.conf" (str (sys-env/env-sysconfdir-service-file sys-env))))
    (is (instance? File (sys-env/sysconfdir-service-file sys-env)))
    (is (= "/etc/pg/sysconfdir/pg_service.conf" (str (sys-env/sysconfdir-service-file sys-env))))))

(deftest ^:io read-service-file*-throws-when-file-not-found
  (let [file (io/file "no-such-file.conf")]
    (with-logging [#{:trace} log-entries]
      (is (com.grzm.tespresso/thrown-with-data?
            #"service file not found" (tespresso/ex-data-select= {:cause ::sys-env/service-file-not-found})
            (sys-env/read-service-file* "my-service" file)))
      (is (empty? @log-entries)))))

(deftest ^:io read-service-file-without-no-such-service
  (let [sys-env (sys-env/map->SystemEnvironment
                  {:user-dir   (sys-env/->UserHome "/some/home")
                   :sysconfdir (->TestSysConfDir "/etc/pg/sysconfdir")
                   :env        (->TestEnv {"PGSERVICE" "no-such-service"})})]
    (testing "no such service in found service file"
      (with-logging [#{:warn} log-entries]
        (let [file-fn (constantly (io/resource "test-pg_service.conf"))]
          (is (nil? (sys-env/read-service-file sys-env file-fn)))
          (is (= [["com.grzm.pique.alpha.env.system-environment" :warn nil
                    "definition of service \"no-such-service\" not found"]] @log-entries)))))))

(deftest ^:io read-service-file
  (let [sys-env (sys-env/map->SystemEnvironment
                  {:user-dir   (sys-env/->UserHome "/some/home")
                   :sysconfdir (->TestSysConfDir "/etc/pg/sysconfdir")
                   :env        (->TestEnv {"PGSERVICE" "pique"})})]
    (testing "finding service in service file"
      (with-logging [#{:warn} log-entries]
        (let [file-fn (constantly (io/resource "test-pg_service.conf"))]
          (is (= {:dbname "pique-dbname", :port "9876", :user "pique-user"}
                 (sys-env/read-service-file sys-env file-fn)))
          (is (empty? @log-entries) "no log entry when successful"))))
    (testing "no service file"
      (let [file-fn (constantly (io/file "no-such-test-pg_service.conf"))]
        (with-logging [#{:trace} log-entries]
          (is (nil? (sys-env/read-service-file sys-env file-fn)))
          (is (= [["com.grzm.pique.alpha.env.system-environment"
                    :trace nil
                    "service file \"no-such-test-pg_service.conf\" not found"]]
                 @log-entries)))))))

(deftest system-environment-constructor
  (let [sys-env (sys-env/system-environment)]
    (is (= [:user-dir :sysconfdir :env] (keys sys-env)))
    (is (every? identity (vals sys-env)))))
