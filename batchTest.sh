#!/bin/bash

for i in 3 5 7 10
do
   java -cp target/scala-2.11/epic-assembly-0.4-SNAPSHOT.jar JavaProject.Tester 600 vote $i train
done




#for i in $(seq 100 100 600)
#do
#   java -cp target/scala-2.11/epic-assembly-0.4-SNAPSHOT.jar JavaProject.Tester thresh 300 train
#done

#Helgen
#java -cp target/scala-2.11/epic-assembly-0.4-SNAPSHOT.jar JavaProject.Tester 600 lc infodens train
#java -cp target/scala-2.11/epic-assembly-0.4-SNAPSHOT.jar JavaProject.Tester 600 vote 3 infodens train
#java -cp target/scala-2.11/epic-assembly-0.4-SNAPSHOT.jar JavaProject.Tester 600 gibbs infodens train

#java -cp target/scala-2.11/epic-assembly-0.4-SNAPSHOT.jar JavaProject.Tester 600 lc noise train