(defproject circleci/congomongo
  "0.4.6"
  :description "Clojure-friendly API for MongoDB"
  :url "https://github.com/aboekhoff/congomongo"
  :mailing-list {:name "CongoMongo mailing list"
                 :archive "https://groups.google.com/forum/?fromgroups#!forum/congomongo-dev"
                 :post "congomongo-dev@googlegroups.com"}
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/core.incubator "0.1.3"]
                 [org.clojure/data.json "0.2.4"]
                 [org.mongodb/mongo-java-driver "2.13.2"]
                 [org.clojure/clojure "1.7.0"]]
  ;; if a :dev profile is added, remember to update :aliases below to
  ;; use it in each with-profile group!
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0-alpha2"]]}}
  :aliases {"test-all" ["with-profile" "default:1.3,default:1.4,default:1.5,default:1.6,default:1.8,default" "test"]})
