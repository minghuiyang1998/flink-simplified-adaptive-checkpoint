# Tips For develop this Project

1. when you try to build, run 'mvn clean package -DskipTests -X', 
maven checkstyle is opened, so the build process fail may related 
to style problem, find with -x and fix it! run 'mvn spotless:apply'

2. Never run 'mvn clean package'. It will cause some issue with test. 
3. If 'xxx module couldn't be found' appears when you are running an example. Try to rebuild the whole module / folder

4. modify and run test files in every package, for small part of code modification, for example:
   flink-simplified-checkpoint/flink-runtime/**src/test**/java/org/apache/flink/runtime/jobmaster/JobMassterTest.java

5. before using  "testCheckpointPrecedesSavepointRecovery" test in "jobMasterTest.java" to test Adapter and
JobMaster Gateway (run "mvn clean package" first, Otherwise, an error message is displayed 
indicating that the "rpcService" cannot be established.)

6. The best way to learn how to use the API is to look at Example instead of searching for documentation on the website

