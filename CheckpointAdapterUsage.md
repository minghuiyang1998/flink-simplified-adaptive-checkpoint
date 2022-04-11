# Usage of Checkpoint Adapter
6 APIs are added to `StreamExecutionEnvironment` to set up an adaptive checkpoint.
```java
// if you enable a checkpoint adapter without recovery time,
// default recovery time is 10000L
enableCheckpointAdapter()
enableCheckpointAdapter(long recoveryTime)
// before setting params, you must enable a checkpoint adapter
setCheckpointAdapterMetricInterval(long metricInterval)
setCheckpointAdapterAllowRange(double allowRange)
setCheckpointAdapterChangeInterval(long changeInterval)
setCheckpointAdapterDebounceMode(boolean debounceMode)
```
Each checkpoint adapter mainly includes 2 parts, so make sure
you set up **_these 2 parts and periodic checkpoint_** properly, 
otherwise the adapter will not work properly: 
1. get metrics from each task
2. calculate and update checkpoint interval referring to metrics
## Set up periodic checkpoint and set up adapter
```java
env.enableCheckpointing(3000L);
env.enableCheckpointAdapter(10000L); 
/** set up recoveryTime without set up metrics submission and update strategy,
 the metrics will be submitted after completing each checkpoint and checkpoint
 interval will not be updated
*/
```
## Set up metrics submission
There are 2 ways for each task to submit metrics:
1. submit metrics after each task complete a checkpoint(default)
2. submit metrics periodically
```java
// to set up a checkpoint adapter with metrics submission
public static void main(String[] args){
        env.enableCheckpointAdapter(long recoveryTime);
        /** 1. submit after completing a checkpoint */
        env.setCheckpointAdapterMetricInterval();
        /** 
         * 2. submit periodically
         * represent metric submission period will be 10000s
         * */
        env.setCheckpointAdapterMetricInterval(10000L); 
}
```

## Set up reset checkpoint strategy
There are 4 ways for each task to reset checkpoint interval.
These 4 ways are decided by 3 params: 
1. `allowRange` (default: -1), 
2. `changeInterval` (default -1), 
3. `isDebounceMode` (default: false)

1.Only set `allowRange`. When an interval calculated from metrics submitted by 
a task changes from the current interval by more than allowRange, the checkpoint
interval will be changed.
```java
public static void main(String[] args){
        env.enableCheckpointAdapter(long recoveryTime);
        env.setCheckpointAdapterAllowRange(0.3) // represent 30%
        /** checkpoint interval will be changed when an interval calculated from metrics submitted 
         * by a task changes from the current interval by more than 30%*/
        }
```
2.Only set `changeInterval`. In each changeInterval, checkpoint interval will be changed to the 
minimal interval which is calculated in this changeInterval.
```java
public static void main(String[] args){
        env.enableCheckpointAdapter(long recoveryTime);
        env.setCheckpointAdapterChangeInterval(10000L)
        /** checkpoint interval will be changed every 10000s */ 
}
```
3.Set `allowRange` and `changeInterval`.  When an interval calculated from metrics submitted by
a task changes from the current interval by more than allowRange, the interval will be compared with 
the minimal interval in this changeInterval. In each changeInterval, checkpoint interval will be 
changed to the minimal interval which is calculated in this changeInterval.
```java
public static void main(String[] args){
        env.enableCheckpointAdapter(long recoveryTime);
        env.setCheckpointAdapterAllowRange(0.3) // represent 30%
        env.setCheckpointAdapterChangeInterval(10000L)
        /** When an interval calculated from metrics submitted by a task changes from the current
        interval by more than 30%, this interval will be compared with minimal interval. In each 
        changeInterval, checkpoint interval will be changed to the minimal interval which is
        calculated in this changeInterval.
         * */
}
```
4. Set `allowRange`, `changeInterval` and `isDebounceMode`. 
The checkpoint interval will be changed only if the calculated interval is maintained within 
a range (allowRange) for a period of time (changeInterval).
(Ps: if you only set isDebounceMode = true, it will not work)
```java
public static void main(String[] args){
        env.enableCheckpointAdapter(long recoveryTime);
        env.setCheckpointAdapterAllowRange(0.3) // represent 30%
        env.setCheckpointAdapterChangeInterval(10000L)
        env.setCheckpointAdapterDebounceMode(true)
        /**
         * When a period is calculated, the timer will be started. Only if the period calculated
         * within 10000ms is within 30% of this value, the period of the checkpoint will be updated to
         * this value.
         * */
}
```
