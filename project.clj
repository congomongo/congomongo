;; common dependencies:
(def deps '[[org.clojure/core.incubator "0.1.0"]
            [org.clojure/data.json "0.1.3"]
            [org.mongodb/mongo-java-driver "2.7.3"]])

;; project definition for multi-version testing:
(defproject congomongo
  "0.1.11-SNAPSHOT"
  :description "clojure-friendly api for MongoDB"
  :url "https://github.com/aboekhoff/congomongo"
  :mailing-list {:name "congomongo mailing list"
                 :archive "https://groups.google.com/forum/?fromgroups#!forum/congomongo-dev"
                 :post "congomongo-dev@googlegroups.com"}
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :dev-dependencies [[lein-multi "1.1.0"]
                     [swank-clojure "1.4.2"]]
  :repositories [["sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots/"]]
  :dependencies ~(conj deps '[org.clojure/clojure "1.3.0"])
  :multi-deps {"1.2"  ~(conj deps '[org.clojure/clojure "1.2.1"])
               "1.4"  ~(conj deps '[org.clojure/clojure "1.4.0"])
               "1.5S" ~(conj deps '[org.clojure/clojure "1.5.0-master-SNAPSHOT"])})
