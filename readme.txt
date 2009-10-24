CongoMongo:

Clojure wrapper for the MongoDB java api.

Summary:

Provides a convenience wrapper around most of the standard methods on 
collections from the java api and introduces some coercion utilities 
for convenient serialization of Clojure data-structures.

Basics:

(ns my-mongo-app
  (:require [somnium.congomongo :as congo]))

(congo/mongo!
  :db "mydb")

(congo/mass-insert!
  :my-collection
  (for [x (range 100) y (range 100)] {:x x :y y}))

(congo/fetch
  :my-collection
  :where {:x {'> 7 
              '< 42}
          :y {'in [1 2 3 4]}})

(congo/fetch
  :my-collection 
  :as :json)

(congo/fetch
  :my-collection
  :where {:x 5}
  :only  [:y])

(congo/update!
  :my-collection 
  {:x {'> 5 '< 10}}
  {:x "you've been updated!"})

Coercions:

  The MongoDB java api will serialize any collections that
implement java.util.Map or java.util.List. That covers most
of Clojure already, for convenience Congo coerces all keywords
to strings on insert, and coerces map keys back to keywords
on fetch (unless you're fetching as json).

  It also coerces query shortcuts (like '>) to their Mongo form
("$gt"). A full list is located in congo.coerce.
  It also coerces strings mapped to keys that
begin with an underscore and end with id to com.mongodb.ObjectId 
instances. This is useful for querying by id when you're feeling
more relational than key-value.

  You can turn all the coercions off if they bother you, just do:

(congo/mongo!
  :db "my-db"
  :coerce-to   []
  :coerce-from [])

  You can also write your own using the defcoercion macro in
congo.coerce. See the source for details.
   
Dependencies:

  Congo depends on the MongoDB java api and clojure.
It also needs a running MongoDB instance to talk to in order to do
anything useful.

  You can get the mongodb-java-api jar here.
Just in case you can't find clojure try looking here.

Install:

Ideas:

  Generate fetch-related functions like a relational ORM?
  Compile coercions by direction and test for increased efficiency?
  Wrap additional sections of the java api?   

Feedback:

  If you've used, improved, or abused congo I'd like to hear about it.
Drop me a line at spam_central.boekhoffa@spam_me_please.gmail.i_like_spam.com.
