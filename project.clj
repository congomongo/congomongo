(defproject congomongo
  "0.2.1-SNAPSHOT"
  :description "clojure-friendly api for MongoDB"
  :url "https://github.com/aboekhoff/congomongo"
  :mailing-list {:name "congomongo mailing list"
                 :archive "https://groups.google.com/forum/?fromgroups#!forum/congomongo-dev"
                 :post "congomongo-dev@googlegroups.com"}
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :min-lein-version "2.0.0"
  :plugins [[lein-swank "1.4.4"]]
  :dependencies [[org.clojure/core.incubator "0.1.0"]
                 [org.clojure/data.json "0.1.3"]
                 [org.mongodb/mongo-java-driver "2.9.1"]
                 [org.clojure/clojure "1.4.0"]]
  ;; if a :dev profile is added, remember to update :aliases below to
  ;; use it in each with-profile group!
  :profiles {:1.2  {:dependencies [[org.clojure/clojure "1.2.1"]]}
             :1.3  {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.5S {:repositories [["sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots/"]]
                    :dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]}}
  :aliases {"test-all" ["with-profile" "default:1.2,default:1.3,default:1.5S,default" "test"]})
