#!/bin/bash
javac -d out src/*.java
java -cp out ScenicSearch src/siena2-area.tmg 2 6
