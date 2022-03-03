# Tips For develop this Project

1. when you try to build, run 'mvn clean package -DskipTests -X', 
maven checkstyle is opened, so the build process fail may related 
to style problem, find with -x and fix it!

2. modify and run test files in every package, for small part of code modification, for example:
   flink-simplified-checkpoint/flink-runtime/**src/test**/java/org/apache/flink/runtime/jobmaster/JobMassterTest.java


