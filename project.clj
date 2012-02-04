(defproject congomongo
  "0.1.8-SNAPSHOT"
  :description "clojure-friendly api for MongoDB"
  :dependencies [[org.clojure/clojure "[1.2.1,1.4.0-beta1]"]
                 [org.clojure/core.incubator "0.1.0" :exclusions [org.clojure/clojure]]
                 [org.clojure/data.json "0.1.2"]
                 [org.mongodb/mongo-java-driver "2.6.5"]]
  :dev-dependencies [[swank-clojure "1.3.1"]])
