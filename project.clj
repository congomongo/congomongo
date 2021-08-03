(defproject congomongo
  "2.2.2"
  :description "Clojure-friendly API for MongoDB"
  :url "https://github.com/congomongo/congomongo"
  :mailing-list {:name "CongoMongo mailing list"
                 :archive "https://groups.google.com/forum/?fromgroups#!forum/congomongo-dev"
                 :post "congomongo-dev@googlegroups.com"}
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/data.json "0.2.7"]
                 [org.mongodb/mongo-java-driver "3.10.2"]
                 [org.clojure/clojure "1.10.1" :scope "provided"]]
  :deploy-repositories {"releases" {:url "https://repo.clojars.org" :creds :gpg}}
  ;; if a :dev profile is added, remember to update :aliases below to
  ;; use it in each with-profile group!
  :profiles {:1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.1"]]}}
  :aliases {"test-all" ["with-profile" "default,1.9:default,1.10" "test"]})
