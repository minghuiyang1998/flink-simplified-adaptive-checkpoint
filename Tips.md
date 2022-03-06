# Tips For develop this Project

1. when you try to build, run 'mvn clean package -DskipTests -X', 
maven checkstyle is opened, so the build process fail may related 
to style problem, find with -x and fix it!

2. modify and run test files in every package, for small part of code modification, for example:
   flink-simplified-checkpoint/flink-runtime/**src/test**/java/org/apache/flink/runtime/jobmaster/JobMassterTest.java

3. before using  "testCheckpointPrecedesSavepointRecovery" test in "jobMasterTest.java" to test Adapter and
JobMaster Gateway (run "mvn clean package" first, Otherwise, an error message is displayed 
indicating that the "rpcService" cannot be established.)
