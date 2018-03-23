(def project 'com.grzm/pique.alpha)
(def version "0.1.6")

(set-env! :resource-paths #{"resources" "src" "test/resources"}
          :source-paths   #{"test/src"}
          :dependencies   '[[adzerk/boot-test "RELEASE" :scope "test"]
                            [com.grzm/tespresso.alpha "0.1.3"]
                            [metosin/boot-alt-test "0.3.2" :scope "test"]
                            [org.clojure/clojure "RELEASE"]
                            [org.clojure/spec.alpha "0.1.123" :scope "test"]
                            [org.clojure/test.check "0.10.0-alpha2" :scope "test"]
                            [org.clojure/tools.logging "0.4.0"]])

(task-options!
 pom {:project     project
      :version     version
      :description "Access libpq parameters via Clojure"
      :url         "http://github.com/grzm/pique.alpha/"
      :scm         {:url "https://github.com/grzm/pique.alpha"}
      :license     {"MIT"
                    "https://opensource.org/licenses/MIT"}})

(deftask build
  "Build and install the project locally."
  []
  (comp (pom) (jar) (install)))

(require '[adzerk.boot-test :refer [test]])
(require '[metosin.boot-alt-test :refer [alt-test]])
