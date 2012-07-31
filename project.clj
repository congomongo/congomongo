;; common dependencies:
(def deps '[[org.clojure/core.incubator "0.1.0"]
            [org.clojure/data.json "0.1.3"]
            [org.mongodb/mongo-java-driver "2.7.3"]])

;; project definition for multi-version testing:
(defproject congomongo
  "0.1.10"
  :description "clojure-friendly api for MongoDB"
  :dev-dependencies [[lein-multi "1.1.0"]
                     [swank-clojure "1.4.2"]]
  :repositories [["sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots/"]]
  :dependencies ~(conj deps '[org.clojure/clojure "1.3.0"])
  :multi-deps {"1.2"  ~(conj deps '[org.clojure/clojure "1.2.1"])
               "1.4"  ~(conj deps '[org.clojure/clojure "1.4.0"])
               "1.5S" ~(conj deps '[org.clojure/clojure "1.5.0-master-SNAPSHOT"])})
