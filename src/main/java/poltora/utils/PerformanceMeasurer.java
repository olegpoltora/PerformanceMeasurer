/*
 * Copyright (c) 2020 Oleg Poltoratskii www.poltora.info
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package poltora.utils;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Measuring device (measurer) for measuring performance
 *
 * @author Oleg Poltoratskii ( www.poltora.info )
 */
public class PerformanceMeasurer {

    private static final String SUCCESS_NAME = "success";
    private static final String ERROR_NAME = "error";
    private static final String FAIL_NAME = "fail";
    private static final String CREATE_NAME = "create";
    private static final String UPDATE_NAME = "update";
    private static final String SKIP_NAME = "skip";
    private static Logger LOGGER = LogManager.getLogger(PerformanceMeasurer.class);
    private static Map<String, PerformanceMeasurer> measurers = new ConcurrentHashMap<>();
    private static ScheduledExecutorService scheduler;
    private static int time = 15;
    private static TimeUnit timeUnit = TimeUnit.SECONDS;

    private static final String summarySensorName = "sum";
    private static final String throughputSensorName = "r/s";
    private static final String throughputMomentSensorName = "r/s/i";

    private Logger logger;
    private Level priority;
    private StringBuffer log;

    private String name;
    private Map<String, Sensor> sensors;
    private PerformanceMeasurer history;
    private long startTime;
    private ThreadLocal<Long> stepStartTime;
    private AtomicLong stepDuration;

    private long currentTime;

    private Sensor summarySensor;
    private Sensor throughputSensor;
    private Sensor throughputMomentSensor;

    // updated state
    private long duration;
    private float percent;
    private long leftTime;
    private Sensor forecastSensor;

    static {
        PerformanceMeasurer.addShutdownHook();

        PerformanceMeasurer.schedulerInit();
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                display();
            }
        });
    }

    private static void schedulerInit() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                (Runnable) PerformanceMeasurer::scheduleWork,
                time, time, timeUnit
        );
    }

    @SuppressWarnings("unused")
    public static void setSchedulerTimeout(int time, TimeUnit timeUnit) {
        PerformanceMeasurer.time = time;
        PerformanceMeasurer.timeUnit = timeUnit;

        scheduler.scheduleAtFixedRate(
                (Runnable) PerformanceMeasurer::scheduleWork,
                time, time, timeUnit
        );
    }

    private static void scheduleWork() {
        display();
        purge();
    }

    @SuppressWarnings("Convert2streamapi")
    private static void purge() {
        if (measurers.isEmpty()) return;


        long curTime = System.currentTimeMillis();
        int maxSleepingTime = 24 * 60 * 60 * 1000;

        for (PerformanceMeasurer measurer : measurers.values()) {
            if (measurer.currentTime != 0 && measurer.currentTime < curTime - maxSleepingTime) {
                LOGGER.debug(String.format("Purging old measurers [%s]", measurer.name));
                measurers.remove(measurer.name);
            }
        }
    }

	public static void displayAndForget() {
		display();


		if (measurers.isEmpty()) return;


		for (PerformanceMeasurer measurer : measurers.values()) {
			measurers.remove(measurer.name);
		}
	}

  public static void displayAndReset() {
    display();

    measurers.values()
        .stream()
        .map(performanceMeasurer -> performanceMeasurer.sensors)
        .map(Map::values)
        .flatMap(Collection::stream)
        .forEach(Sensor::reset);
  }

    public static void display() {
        if (measurers.isEmpty()) return;


        for (PerformanceMeasurer measurer : measurers.values()) {
          measurer.show();
        }
    }

    public void show() {
    if (isUpdated()) {

      makeSummary();

      logger.log(
          priority,
          log()
      );

      snapshot();
    }
  }

  public void finish() {
    show();

    measurers.remove(name);
  }

    public static PerformanceMeasurer get() {

        return get(
                Thread.currentThread().getStackTrace()[2].getClassName()
        );
    }

    public static PerformanceMeasurer getByMethodName() {

        return get(
                String.format(
                        "%s.%s()",
                        Thread.currentThread().getStackTrace()[2].getClassName(),
                        Thread.currentThread().getStackTrace()[2].getMethodName()
                )
        );
    }

    public static PerformanceMeasurer get(Class clazz) {
        return get(
                clazz.getName()
        );
    }

    public static PerformanceMeasurer get(String name) {
        return measurers.computeIfAbsent(name, k -> PerformanceMeasurer.getInstance(name));
    }

    private static PerformanceMeasurer getInstance(String name) {

        PerformanceMeasurer measurer = new PerformanceMeasurer(name);
        measurer.history = new PerformanceMeasurer("Stub-ancestor-for-measurer");
        return measurer;
    }

    private PerformanceMeasurer(String name) {
        logger = LogManager.getLogger(name);
        this.priority = Level.INFO;

        this.name = name;
        startTime = System.currentTimeMillis();

        stepStartTime = new ThreadLocal<Long>() {
            @Override
            protected Long initialValue() {
                return 0L;
            }
        };
        stepDuration = new AtomicLong();//todo atomic -> adder

        sensors = new ConcurrentHashMap<>();


        // outside sensor list
        summarySensor = Sensor.getInstance(summarySensorName, this);
        throughputSensor = Sensor.getInstance(throughputSensorName, this);
        throughputMomentSensor = Sensor.getInstance(throughputMomentSensorName, this);
    }


    private void snapshot() {

        for (Sensor sensor : sensors.values()) {
            sensor.snapshot();
        }


        summarySensor.snapshot();
        throughputSensor.snapshot();
        throughputMomentSensor.snapshot();
    }


    @SuppressWarnings("Convert2streamapi")
    private void makeSummary() {

        currentTime = System.currentTimeMillis();


        if (hasPersonalTimer()) {
            duration = stepDuration.get();
        } else {
            duration = currentTime - startTime;
        }
        if (duration == 0) duration = 1;


        summarySensor.reset(); //history
        for (Sensor sensor : sensors.values()) {

            if (!sensor.isolated) {
                summarySensor.measure(sensor.take());
//                summarySensor.measure(sensor.take() - sensor.history.take());
            }
        }


        throughputSensor.reset();
        throughputSensor.measure((int) ((summarySensor.take() * 1000) / duration));


        throughputMomentSensor.reset();
        long durationStep = TimeUnit.MILLISECONDS.convert(time, timeUnit);
        durationStep = duration < durationStep ? duration : durationStep;
        throughputMomentSensor.measure(
                (int) (((summarySensor.take() - summarySensor.history.take()) * 1000) / durationStep)
        );


        // percent & left time
        long count = 0;
        long size = 0;

        if (forecastSensor != null) {
            count = forecastSensor.take();
            size = forecastSensor.possibleSize;
        }

        if (count != 0) {
            percent = (float) count * 100 / size;
            leftTime = (((long) (duration / percent) * 100)) - duration;
        }
    }


    private boolean isSeveralCommonSensors() {
        int number = 0;
        for (Sensor sensor : sensors.values()) {
            if (!sensor.isolated && sensor.isStarted()) {
                if (++number > 1) return true;
            }
        }
        return false;
    }

    private boolean isUpdated() {
        for (Sensor sensor : sensors.values()) {
            if (sensor.isUpdated()) {
                return true;
            }
        }

        return false;
    }

    private boolean isForecastCompleted() {
        return forecastSensor != null && forecastSensor.take() >= forecastSensor.possibleSize;
    }

    private boolean hasHistory() {
        for (Sensor sensor : sensors.values()) {
            if (sensor.hasHistory()) {
                return true;
            }
        }

        return false;
    }

    private boolean isLogAtOnce() {
        return isForecastCompleted() && !hasHistory();
    }


    @SuppressWarnings("Convert2streamapi")
    private String log() {
        log = new StringBuffer();


        if (hasPersonalTimer())
            log.append("(personal) ");

        logValue(formatDuration(duration, "HH:mm:ss"));


        //forecast
        if (forecastSensor != null) {
            if (percent == 0 && leftTime == 0) {
                logValue("   ∞    ");
            } else if (percent == 100) {
                if (hasHistory()) {
                    logValue("   .    ");
                }
            } else {
                logValue(formatDuration(leftTime, "HH:mm:ss"));
            }
        }

        //progress
        if (forecastSensor != null) {
            if (percent != 100 || hasHistory()) {
                logProgress((int) percent);
            }
        }


        // throughput
        if (summarySensor.isStarted()) {
            log.append(throughputSensor.log());
        }


        // throughput moment
        if (!hasPersonalTimer() && summarySensor.isStarted() && !isLogAtOnce()) {
            log.append(throughputMomentSensor.log());
        }


        //common
        for (Sensor sensor : sensors.values()) {
            if (!sensor.isolated) {
                log.append(sensor.log());
            }
        }
        if (isSeveralCommonSensors()) {
            log.append(summarySensor.log());
        }


        //isolated
        for (Sensor sensor : sensors.values()) {
            if (sensor.isolated) {
                log.append(sensor.log());
            }
        }


        return log.toString();
    }


    private void logProgress(int value) {

        String valueOf = String.valueOf(value);
        log.append(valueOf);
        log.append("%");


        int currentLength = valueOf.length() + 1;

        int logLength = 4; //100%
        if (currentLength < logLength) {
          log.append(" ".repeat(logLength - currentLength));
        }


        log.append(" ");
    }

    private void logValue(String value) {
        log
                .append(value)
                .append(" ")
        ;
    }


    public Sensor getSensor(String name) {
        return sensors.computeIfAbsent(name, k -> Sensor.getInstance(name, this));
    }


    public void measure(String name, int delta) {
        getSensor(name).measure(delta);
    }

    public void measure(String name) {
        measure(name, 1);
    }

    public void success(int delta) {
        getSensor(SUCCESS_NAME).measure(delta);
    }

    public void success() {
        success(1);
    }

    public void error(int delta) {
        getSensor(ERROR_NAME).measure(delta);
    }

    public void error() {
        error(1);
    }


    public void fail(int delta) {
        getSensor(FAIL_NAME).measure(delta);
    }

    public void fail() {
        fail(1);
    }

    public void create(int delta) {
        getSensor(CREATE_NAME).measure(delta);
    }

    public void create() {
        create(1);
    }

    public void update(int delta) {
        getSensor(UPDATE_NAME).measure(delta);
    }

    public void update() {
        update(1);
    }

    public void skip(int delta) {
        getSensor(SKIP_NAME).measure(delta);
    }

    public void skip() {
        skip(1);
    }

    public void measureByClassName(int delta) {
//        String className = Thread.currentThread().getStackTrace()[2].getClass().getSimpleName();
        String className = Thread.currentThread().getStackTrace()[2].getClassName();
        className = className.substring(className.lastIndexOf('.') + 1);

        getSensor(className).measure(delta);
    }

    // don't use overload method because of `getStackTrace()[2]`
    public void measureByClassName() {
        String className = Thread.currentThread().getStackTrace()[2].getClassName();
        className = className.substring(className.lastIndexOf('.') + 1);

        getSensor(className).measure();
    }

    public void measureByMethodName(int delta) {
        String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();

        getSensor(methodName).measure(delta);
    }

    // don't use overload method because of `getStackTrace()[2]`
    public void measureByMethodName() {
        String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();

        getSensor(methodName).measure();
    }


    public void possibleSize(int size) {
        this.forecastSensor = summarySensor;

        this.forecastSensor.possibleSize = size;
    }

    @SuppressWarnings("unused")
    public void possibleSize(String name, long size) {
        Sensor sensor = getSensor(name);

        this.forecastSensor = sensor;
        forecastSensor.possibleSize = size;

        // as forecast is depend on current sensor so it is isolated
        sensor.isolated = true;
    }

    @SuppressWarnings("unused")
    public PerformanceMeasurer setIsolated(String name) {
        Sensor sensor = getSensor(name);
        sensor.isolated = true;
        return this;
    }

    public void start() {
        stepStartTime.set(System.currentTimeMillis());
    }

    public void stop() {
        stepDuration.addAndGet(System.currentTimeMillis() - stepStartTime.get());
    }

    public PerformanceMeasurer setPriority(Level priority) {
        this.priority = priority;
        return this;
    }

    private boolean hasPersonalTimer() {
        return stepDuration.get() != 0;
    }

    public static class Sensor {

        private static String logTemplVal = "%s: %s;  "; //sum: 246;
        private static String logTemplDelta = "%s: %s(%s);  "; //sum: 342(+96);
        private static String logTemplPerc = "%s: %s%% %s;  "; //success: 33% 81;
        private static String logTemplDeltaPercent = "%s: %s%% %s(%s%% %s);  "; //success: 30% 125(28% +22);

        private String name;
        private PerformanceMeasurer measurer;
        private LongAdder sensor;
        private boolean isolated;
        private long possibleSize;
        private int logLength;
        private Sensor history;

        private static Sensor getInstance(String name, PerformanceMeasurer measurer) {
            Sensor sensor = new Sensor(name, measurer);
            sensor.history = new Sensor("Stub-ancestor-for-sensor", measurer);

            return sensor;
        }

        private Sensor(String name, PerformanceMeasurer measurer) {
            this.name = name;
            this.measurer = measurer;
            sensor = new LongAdder();
        }

        private Sensor(Sensor other) {
            this.name = other.name;
            this.measurer = other.measurer;
            this.isolated = other.isolated;
            this.possibleSize = other.possibleSize;
            this.logLength = other.logLength;

            //
            this.history = other.history;

            //clone
            this.sensor = other.sensor;
        }

        private Sensor newClone() {
            Sensor clone = new Sensor(this);
            clone.sensor = new LongAdder();
            clone.sensor.add(sensor.sum());
            return clone;
        }

        private Sensor snapshot() {
            Sensor clone = newClone();
            this.history = clone;
            clone.history = null;

            return clone;
        }

        public void measure() {
            sensor.increment();
        }

        public void measure(long delta) {
            sensor.add(delta);
        }

        private long take() {
            return sensor.sum();
        }

        private void reset() {
            sensor.reset();
            history.sensor.reset(); // isUpdated
        }

        private boolean isStarted() {
            return take() != 0;
        }

        private boolean hasHistory() {
            return history.isStarted();
        }

        private boolean isUpdated() {
            return take() != history.take();
        }


        private boolean isAlone() {
            for (Sensor sensor : measurer.sensors.values()) {
                if (!this.equals(sensor) && !sensor.isolated && sensor.isStarted()) {
                    return false;
                }
            }
            return true;
        }


        private String log() {

            String result;

            boolean isSpecialSensors = name.equals(summarySensorName) || name.equals(throughputSensorName) || name.equals(throughputMomentSensorName);


            long val = take();
            if (isolated || isSpecialSensors || isAlone()) {
                if (!hasHistory()) {
                    //sum: 246;

                    result = String.format(logTemplVal,
                            name,
                            val
                    );

                    if (!measurer.isForecastCompleted()) {
                        result += " ".repeat(String.valueOf(val).length() + 3); // (+)
                    }
                } else {
                    //sum: 342(+96);

                    long delta = val - history.take();

                    result = String.format(logTemplDelta,
                            name,
                            val,
                            delta >= 0 ? "+" + delta : delta
                    );
                }
            } else {
                DecimalFormat format = new DecimalFormat("0");

                float percent = (float) val * 100 / measurer.summarySensor.take();

                if (!hasHistory()) {
                    //success: 33% 81;

                    result = String.format(logTemplPerc,
                            name,
                            format.format(percent),
                            val
                    );

                    if (!measurer.isForecastCompleted()) {
                        result += " ".repeat(String.valueOf(val).length() + 3); // (+)
                        result += " ".repeat(String.valueOf(format.format(percent)).length() + 2);// _%
                    }
                } else {
                    //success: 30% 125(28% +22);

                    long delta = val - history.take();
                    float deltaPercent = 0;
                    if (delta != 0) {
                        deltaPercent = (float) delta * 100 / (measurer.summarySensor.take() - measurer.summarySensor.history.take());
                    }

                    result = String.format(logTemplDeltaPercent,
                            name,
                            format.format(percent),
                            val,
                            format.format(deltaPercent),
                            delta >= 0 ? "+" + delta : delta
                    );
                }
            }


            int currentLength = result.length();

            if (currentLength < logLength) {
                result += " ".repeat(logLength - currentLength);
            }
            if (currentLength > logLength) {
                logLength = currentLength;
            }

            return result;
        }

        @Override
        public String toString() {
            return "Sensor{" +
                    "name='" + name + '\'' +
                    ", sensor=" + sensor +
//                    ", isolated=" + isolated +
//                    ", possibleSize=" + possibleSize +
//                    ", logLength=" + logLength +
                    ", history=" + history +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "PerformanceMeasurer{" +
                "name='" + name + '\'' +
                ", sensors=" + sensors +
//                ", startTime=" + startTime +
//                ", stepStartTime=" + stepStartTime +
//                ", stepDuration=" + stepDuration +
//                ", currentTime=" + currentTime +
                ", summarySensor=" + summarySensor +
                ", throughputSensor=" + throughputSensor +
                ", throughputMomentSensor=" + throughputMomentSensor +
                ", duration=" + duration +
                ", percent=" + percent +
                ", leftTime=" + leftTime +
                ", forecastSensor=" + forecastSensor +
                '}';
    }

  public static String formatDuration(long durationMillis, String pattern) {
    Duration duration = Duration.ofMillis(durationMillis);

    if ("HH:mm:ss".equals(pattern)) {
      long hours = duration.toHours();
      long minutes = duration.toMinutes() % 60;
      long seconds = duration.getSeconds() % 60;

      return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
    throw new IllegalArgumentException("Unsupported pattern: " + pattern);
  }}
