package edu.umd.cs.mtc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.internal.runners.statements.InvokeMethod;
import org.junit.runners.model.FrameworkMethod;

/**
 * This class plugs the {@link MultithreadedTestCase} into the JUnit framework.
 * <p/>
 * <p/>
 * Each test case starts by running all the thread methods in different threads.
 * After that, the actual junit test method will be executed when all threads
 * have finished. The thread methods are run in a new thread group, and are
 * regulated by a separate clock thread. The clock thread checks periodically to
 * see if all threads are blocked. If all threads are blocked and at least one
 * is waiting for a tick, the clock thread advances the clock to the next
 * desired tick. (A slight delay -- about a clock period -- is applied before
 * advancing the clock to ensure that this is not done prematurely and any
 * threads trying to unblock are given a chance to do so.)
 * <p/>
 * <p/>
 * The clock thread also detects deadlock (when all threads are blocked, none
 * are waiting for a tick, and none are in state TIMED_WAITING), and can stop a
 * test that is going on too long (a thread is in state RUNNABLE for too long.)
 * <p/>
 * <p/>
 * Since the test case threads are placed in a new thread group, any other
 * threads created by these test cases will be placed in this thread group by
 * default. All threads in the thread group will be considered by the clock
 * thread when deciding whether to advance the clock, declare a deadlock, or
 * stop a long-running test.
 * <p/>
 * <p/>
 * The framework catches exceptions thrown in the threads and propagates them to
 * the JUnit test (It also throws AssertionErrors)
 * <p/>
 * <p/>
 * This class also defines a number of parameters to be used to control the
 * tests. Set command line parameter -Dtunit.runLimit=<em>n</em> to cause a test
 * case to fail if at least one thread stays in a runnable state for more than
 * <em>n</em> seconds without becoming blocked or waiting for a metronome tick.
 * Set command line parameter -Dtunit.clockPeriod=<em>p</em> to cause the clock
 * thread to check the status of all the threads every <em>p</em> milliseconds.
 * 
 * @author William Pugh
 * @author Nathaniel Ayewah
 * @see MultithreadedTestCase
 * @see #runOnce(MultithreadedTestCase)
 * @see #runManyTimes(MultithreadedTestCase, int)
 * @since 1.0
 */
public class RunThreadsThenInvokeMethod extends InvokeMethod {
    private final MultithreadedTestCase test;

    private final int runTimes;

    public RunThreadsThenInvokeMethod(FrameworkMethod testMethod,
	    Object target, int runTimes) {
	super(testMethod, target);
	this.test = (MultithreadedTestCase) target;
	this.runTimes = runTimes;
    }

    /**
     * Command line key for indicating the regularity (in milliseconds) with
     * which the clock thread regulates the thread methods.
     */
    public static final String CLOCKPERIOD_KEY = "tunit.clockPeriod";

    /**
     * Command line key for indicating the time limit (in seconds) for runnable
     * threads.
     */
    public static final String RUNLIMIT_KEY = "tunit.runLimit";

    /**
     * The default clock period in milliseconds
     */
    public static final Integer DEFAULT_CLOCKPERIOD = 10;

    /**
     * The default run limit in seconds
     */
    public static final Integer DEFAULT_RUNLIMIT = 5;

    /**
     * Change/set the system property for the clock period
     * 
     * @param v
     *            the new value for the clock period
     */
    public static void setGlobalClockPeriod(Integer v) {
	if (v != null)
	    System.setProperty(CLOCKPERIOD_KEY, v.toString());
    }

    /**
     * Change/set the system property for the run limit
     * 
     * @param v
     *            the new value for the run limit
     */
    public static void setGlobalRunLimit(Integer v) {
	if (v != null)
	    System.setProperty(RUNLIMIT_KEY, v.toString());
    }

    /**
     * Run multithreaded test case multiple times using the default or global
     * settings for clock period and run limit. This method adds instrumentation
     * to count the number of times failures occur (an exception is thrown). If
     * the array <code>failureCount</code> is initialized to be of at least size
     * 1, it returns this count in <code>failureCount[0]</code>. If failures do
     * occur, it saves the first failure, and then throws it after running the
     * test <code>count</code> times.
     * 
     * @param test
     *            The multithreaded test case to run
     * @param count
     *            the number of times to run the test case
     * @param failureCount
     *            if this array is initialzed to at least size 1, the number of
     *            failures is returned in <code>failureCount[0]</code>
     * @throws Throwable
     *             if there is at least one failure -- the first failure is
     *             thrown
     */
    public static void runInstrumentedManyTimes(
	    final MultithreadedTestCase test, int count, int[] failureCount)
	    throws Throwable {
	int failures = 0;
	Throwable t = null;
	boolean failed = false;

	System.out.println("Testing " + test.getClass());

	for (int i = 0; i < count; i++) {
	    try {
		runOnce(test);
	    } catch (Throwable e) {
		failed = true;
		failures++;
		if (t == null)
		    t = e;
	    }
	    if (i % 10 == 9) {
		if (failed) {
		    System.out.print("f");
		    failed = false;
		} else
		    System.out.print(".");
		if (i % 100 == 99)
		    System.out.println(" " + (i + 1));
	    }
	}
	if (failureCount != null && failureCount.length > 0)
	    failureCount[0] = failures;
	if (t != null)
	    throw t;
    }

    /**
     * Run multithreaded test case multiple times using the default or global
     * settings for clock period and run limit. The value of this is limited,
     * since even running a test case a thousand or a million times may not
     * expose any bugs dependent upon particular thread interleavings.
     * 
     * @param test
     *            The multithreaded test case to run
     * @param count
     *            the number of times to run the test case
     * @throws Throwable
     *             -- if any of the test runs fails, the exception is thrown
     *             immediately without completing the rest of the test runs.
     */
    public static void runManyTimes(final MultithreadedTestCase test, int count)
	    throws Throwable {
	runManyTimes(test, count, null, null);
    }

    /**
     * Run multithreaded test case multiple times. The value of this is limited,
     * since even running a test case a thousand or a million times may not
     * expose any bugs dependent upon particular thread interleavings.
     * 
     * @param test
     *            The multithreaded test case to run
     * @param count
     *            the number of times to run the test case
     * @param clockPeriod
     *            The period (in ms) between checks for the clock (or null for
     *            default or global setting)
     * @param runLimit
     *            The limit to run the test in seconds (or null for default or
     *            global setting)
     * @throws Throwable
     *             -- if any of the test runs fails, the exception is thrown
     *             immediately without completing the rest of the test runs.
     */
    public static void runManyTimes(final MultithreadedTestCase test,
	    int count, Integer clockPeriod, Integer runLimit) throws Throwable {
	for (int i = 0; i < count; i++)
	    runOnce(test, clockPeriod, runLimit);
    }

    /**
     * Run a multithreaded test case once, using the default or global settings
     * for clock period and run limit
     * 
     * @param test
     *            The multithreaded test case to run
     * @throws Throwable
     *             if the test runs fails or causes an exception
     */
    public static void runOnce(final MultithreadedTestCase test)
	    throws Throwable {
	runOnce(test, null, null);
    }

    /**
     * Run multithreaded test case once.
     * 
     * @param test
     *            The multithreaded test case to run
     * @param clockPeriod
     *            The period (in ms) between checks for the clock (or null for
     *            default or global setting)
     * @param runLimit
     *            The limit to run the test in seconds (or null for default or
     *            global setting)
     * @throws Throwable
     *             if the test runs fails or causes an exception
     */
    public static void runOnce(final MultithreadedTestCase test,
	    Integer clockPeriod, Integer runLimit) throws Throwable {

	// choose global setting if parameter is null, or default value if there
	// is no global setting

	if (clockPeriod == null)
	    clockPeriod = Integer.getInteger(CLOCKPERIOD_KEY,
		    DEFAULT_CLOCKPERIOD);

	if (runLimit == null)
	    runLimit = Integer.getInteger(RUNLIMIT_KEY, DEFAULT_RUNLIMIT);

	// prepare run data structures
	final SortedMap<String, Method> methods = getAllThreads(test);
	LinkedList<java.lang.Thread> threads = new LinkedList<java.lang.Thread>();
	final Throwable[] error = new Throwable[1];

	// invoke initialize method before each run
	// test.initialize();
	test.clock = 0;

	// invoke each thread method in a seperate thread and place all threads
	// in a
	// new thread group
	ThreadGroup threadGroup = startMethodThreads(test, methods, threads,
		error);

	// start and add clock thread
	threads
		.add(startClock(test, threadGroup, error, clockPeriod, runLimit));

	// wait until all threads have ended
	waitForMethodThreads(threads, error);

	// invoke finish at the end of each run
	// test.finish();
    }

    /**
     * Use reflection to get the thread methods in this test. Thread methods
     * start with the name "thread", have no parameters and return void
     * 
     * @param test
     *            the test case from which to extract methods
     * @return a collection of Method objects, one for each thread method,
     *         mapped by name
     */
    private static SortedMap<String, Method> getAllThreads(
	    MultithreadedTestCase test) {
	SortedMap<String, Method> result = new TreeMap<String, Method>();
	for (Class<?> eachClass : getSuperClasses(test.getClass())) {
	    Method[] methods = eachClass.getDeclaredMethods();
	    for (Method method : methods) {
		final Threaded annotation = method
			.getAnnotation(Threaded.class);
		if (annotation != null)
		    result.put("".equals(annotation.value()) ? method.getName()
			    : annotation.value(), method);
	    }
	}
	return result;
    }

    private static List<Class<?>> getSuperClasses(Class<?> testClass) {
	ArrayList<Class<?>> results = new ArrayList<Class<?>>();
	Class<?> current = testClass;
	while (current != null) {
	    results.add(current);
	    current = current.getSuperclass();
	}
	return results;
    }

    /**
     * Start and return a clock thread which periodically checks all the test
     * case threads and regulates them.
     * <p/>
     * <p/>
     * If all the threads are blocked and at least one is waiting for a tick,
     * the clock advances to the next tick and the waiting thread is notified.
     * If none of the threads are waiting for a tick or in timed waiting, a
     * deadlock is detected. The clock thread times out if a thread is in
     * runnable or all are blocked and one is in timed waiting for longer than
     * the runLimit.
     * 
     * @param test
     *            the test case the clock thread is regulating
     * @param threadGroup
     *            the thread group containing the running thread methods
     * @param error
     *            an array containing any Errors/Exceptions that occur in thread
     *            methods or that are thrown by the clock thread
     * @param clockPeriod
     *            The period (in ms) between checks for the clock (or null for
     *            default or global setting)
     * @param runLimit
     *            The limit to run the test in seconds (or null for default or
     *            global setting)
     * @return The (already started) clock thread
     */
    private static java.lang.Thread startClock(
	    final MultithreadedTestCase test, final ThreadGroup threadGroup,
	    final Throwable[] error, final int clockPeriod, final int runLimit) {

	// hold a reference to the current thread. This thread
	// will be waiting for all the test threads to finish. It
	// should be interrupted if there is an deadlock or timeout
	// in the clock thread
	final java.lang.Thread mainThread = java.lang.Thread.currentThread();

	java.lang.Thread t = new java.lang.Thread("Tick thread") {
	    @Override
	    public void run() {
		try {
		    long lastProgress = System.currentTimeMillis();
		    int deadlocksDetected = 0;
		    int readyToTick = 0;
		    while (true) {

			java.lang.Thread.sleep(clockPeriod);

			// Attempt to get a write lock; this succeeds
			// if clock is not frozen
			if (!test.clockLock.writeLock().tryLock(
				1000L * runLimit, TimeUnit.MILLISECONDS)) {
			    synchronized (test.lock) {
				test.failed = true;
				test.lock.notifyAll();
				if (error[0] == null)
				    error[0] = new IllegalStateException(
					    "No progress");
				mainThread.interrupt();
				return;
			    }
			}

			synchronized (test.lock) {

			    try {

				// Get the contents of the thread group
				int tgCount = threadGroup.activeCount() + 10;
				java.lang.Thread[] ths = new java.lang.Thread[tgCount];
				tgCount = threadGroup.enumerate(ths, false);
				if (tgCount == 0)
				    return; // all threads are done

				// will set to true to force a check for timeout
				// conditions
				// and restart the loop
				boolean checkProgress = false;

				// will set true if any thread is in state
				// TIMED_WAITING
				boolean timedWaiting = false;

				int nextTick = Integer.MAX_VALUE;

				// examine the threads in the thread group; look
				// for
				// next tick
				for (int ii = 0; ii < tgCount; ii++) {
				    java.lang.Thread t = ths[ii];

				    try {
					if (test.getTrace())
					    System.out.println(t.getName()
						    + " is in state "
						    + t.getState());

					if (t.getState() == java.lang.Thread.State.RUNNABLE)
					    checkProgress = true;
					if (t.getState() == java.lang.Thread.State.TIMED_WAITING)
					    timedWaiting = true;
				    } catch (Throwable e) {
					// JVM may not support Threaded.State
					checkProgress = false;
					timedWaiting = true;
				    }

				    Integer waitingFor = test.threads.get(t);
				    if (waitingFor != null
					    && waitingFor > test.clock)
					nextTick = Math.min(nextTick,
						waitingFor);
				}

				// If not waiting for anything, but a thread is
				// in
				// TIMED_WAITING, then check progress and loop
				// again
				if (nextTick == Integer.MAX_VALUE
					&& timedWaiting)
				    checkProgress = true;

				// Check for timeout conditions and restart the
				// loop
				if (checkProgress) {
				    if (readyToTick > 0) {
					if (test.getTrace())
					    System.out
						    .println("Was Ready to tick too early");
					readyToTick = 0;
				    }
				    long now = System.currentTimeMillis();
				    if (now - lastProgress > 1000L * runLimit) {
					if (false)
					    for (int ii = 0; ii < tgCount; ii++) {
						java.lang.Thread t = ths[ii];
						System.out.println("thread "
							+ t.getName()
							+ " is in state "
							+ t.getState());
					    }
					test.failed = true;
					test.lock.notifyAll();
					if (error[0] == null)
					    error[0] = new IllegalStateException(
						    "No progress");
					mainThread.interrupt();
					return;
				    }
				    deadlocksDetected = 0;
				    continue;
				}

				// Detect deadlock
				if (nextTick == Integer.MAX_VALUE) {
				    if (readyToTick > 0) {
					if (test.getTrace())
					    System.out
						    .println("Was Ready to tick too early");
					readyToTick = 0;
				    }
				    if (++deadlocksDetected < 50) {
					if (deadlocksDetected % 10 == 0
						&& test.getTrace())
					    System.out
						    .println("[Detecting deadlock... "
							    + deadlocksDetected
							    + " trys]");
					continue;
				    }
				    if (test.getTrace())
					System.out.println("Deadlock!");

				    StringWriter sw = new StringWriter();
				    PrintWriter out = new PrintWriter(sw);
				    for (Map.Entry<java.lang.Thread, Integer> e : test.threads
					    .entrySet()) {
					java.lang.Thread t = e.getKey();
					out.println(t.getName() + " "
						+ t.getState());
					for (StackTraceElement st : t
						.getStackTrace()) {
					    out.println("  " + st);
					}
				    }
				    test.failed = true;
				    if (error[0] == null)
					error[0] = new IllegalStateException(
						"Apparent deadlock\n"
							+ sw.toString());
				    mainThread.interrupt();
				    return;
				}

				deadlocksDetected = 0;

				if (++readyToTick < 2) {
				    continue;
				}
				readyToTick = 0;

				// Advance to next tick
				test.clock = nextTick;
				lastProgress = System.currentTimeMillis();

				// notify any threads that are waiting for this
				// tick
				test.lock.notifyAll();
				if (test.getTrace())
				    System.out.println("Time is now "
					    + test.clock);
			    } finally {
				test.clockLock.writeLock().unlock();
			    }
			}
		    }
		} catch (Throwable e) {
		    // killed
		    if (test.getTrace())
			System.out.println("Tick thread killed");
		}
	    }
	};
	t.setDaemon(true);
	t.start();
	return t;
    }

    /**
     * Wait for all of the test case threads to complete, or for one of the
     * threads to throw an exception, or for the clock thread to interrupt this
     * (main) thread of execution. When the clock thread or other threads fail,
     * the error is placed in the shared error array and thrown by this method.
     * 
     * @param threads
     *            List of all the test case threads and the clock thread
     * @param error
     *            an array containing any Errors/Exceptions that occur in thread
     *            methods or that are thrown by the clock thread
     * @throws Throwable
     *             The first error or exception that is thrown by one of the
     *             threads
     */
    @SuppressWarnings("deprecation")
    private static void waitForMethodThreads(
	    LinkedList<java.lang.Thread> threads, final Throwable[] error)
	    throws Throwable {
	for (java.lang.Thread t : threads)
	    try {
		if (t.isAlive() && error[0] != null)
		    t.stop();
		else
		    t.join();
	    } catch (InterruptedException e1) {
		if (error[0] != null)
		    throw error[0];
		throw new AssertionError(e1);
	    }
	if (error[0] != null)
	    throw error[0];
    }

    /**
     * Invoke each of the thread methods in a seperate thread and place them all
     * in a common (new) thread group. As a side-effect all the threads are
     * placed in the 'threads' LinkedList parameter, and any errors detected are
     * placed in the 'error' array parameter.
     * 
     * @param test
     *            The test case containing the thread methods
     * @param methods
     *            Collection of the methods to be invoked
     * @param threads
     *            By the time this method returns, this parameter will contain
     *            all the test case threads
     * @param error
     *            By the time this method returns, this parameter will contains
     *            the first error thrown by one of the threads.
     * @return The thread group for all the newly created test case threads
     */
    private static ThreadGroup startMethodThreads(
	    final MultithreadedTestCase test,
	    SortedMap<String, Method> methods,
	    final LinkedList<java.lang.Thread> threads, final Throwable[] error) {
	ThreadGroup threadGroup = new ThreadGroup("MTC-Threads");
	final CountDownLatch latch = new CountDownLatch(methods.size());
	final Semaphore waitForRegistration = new Semaphore(0);
	final AtomicBoolean signalledError = new AtomicBoolean(false);
	for (final Map.Entry<String, Method> entry : methods.entrySet()) {
	    Runnable r = new Runnable() {
		public void run() {
		    try {
			waitForRegistration.release();
			latch.countDown();
			latch.await();

			// At this point all threads are created and released
			// (in random order?) together to run in parallel

			test.hello();
			makeAccessible(entry.getValue());
			entry.getValue().invoke(test);
		    } catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof ThreadDeath)
			    return;
			if (error[0] == null) {
			    error[0] = cause;
			}
			if (signalledError.compareAndSet(false, true))
			    signalError(threads);
		    } catch (ThreadDeath e) {
			// ignore it
		    } catch (Throwable e) {
			System.out.println(java.lang.Thread.currentThread()
				.getName()
				+ " caught " + e.getMessage());
			if (error[0] == null)
			    error[0] = e;
			if (signalledError.compareAndSet(false, true))
			    signalError(threads);
		    } finally {
			test.goodbye();
		    }
		}
	    };
	    java.lang.Thread t = new java.lang.Thread(threadGroup, r, entry
		    .getKey());
	    threads.add(t);

	    // add thread to map of method threads, mapped by name
	    test.putThread(t.getName(), t);

	    t.start();
	    waitForRegistration.acquireUninterruptibly();
	}
	return threadGroup;
    }

    /**
     * Stop all test case threads and clock thread, except the thread from which
     * this method is called. This method is used when a thread is ready to end
     * in failure and it wants to make sure all the other threads have ended
     * before throwing an exception.
     * 
     * @param threads
     *            LinkedList of all the test case threads and the clock thread
     */
    @SuppressWarnings("deprecation")
    private static void signalError(final LinkedList<java.lang.Thread> threads) {
	java.lang.Thread currentThread = java.lang.Thread.currentThread();
	for (java.lang.Thread t : threads)
	    if (t != currentThread && t.isAlive()) {
		t.interrupt();
		try {
		    java.lang.Thread.sleep(100);
		} catch (InterruptedException e) {
		    assert true; // ignore
		}
		AssertionError assertionError = new AssertionError(t.getName()
			+ " killed by " + currentThread.getName());
		StackTraceElement[] stackTrace = t.getStackTrace();
		if (stackTrace != null)
		    assertionError.setStackTrace(stackTrace);
		if (t.isAlive()) {
		    t.stop();
		    System.err.println(assertionError);;
		}
	    }
    }

    /**
     * Change security access on an accessible object (e.g. Method or
     * Constructor) so it can be invoked
     * 
     * @param obj
     *            the object to make accessible
     */
    private static void makeAccessible(final AccessibleObject obj) {
	AccessController.doPrivileged(new PrivilegedAction<Void>() {
	    public Void run() {
		obj.setAccessible(true);
		return null;
	    }
	});
    }

    @Override
    public void evaluate() throws Throwable {
	// do multi threaded magic
	runManyTimes(test, runTimes);

	// invoke the actual test method afterwards
	super.evaluate();
    }
}
