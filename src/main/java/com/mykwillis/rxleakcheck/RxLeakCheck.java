package com.mykwillis.rxleakcheck;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import rx.Subscriber;
import rx.Subscription;
import rx.observers.SafeSubscriber;
import rx.plugins.RxJavaHooks;
import timber.log.Timber;

/**
 * Utility to track RxJava Subscriptions and detect leaks.
 *
 *
 *      TL;DR - call `RxLeakCheck.initialize` at the end of application startup, and
 *      then call `RxLeakCheck.dumpLeaks` during graceful shutdown. Any calls to
 *      Observable.subscribe() that were not properly matched with an .unsubscribe()
 *      call, and whose source Observable has not terminated, will be dumped to
 *      the debug log as a leak.
 *
 *
 * Whenever application code subscribes to an Observable with Observable.subscribe(),
 * they receive a Subscription object in return. In general, application code should
 * invoke unsubscribe() on this this Subscription object in order to free up resources
 * when they are no longer interested in the source Observable.
 *
 * Failing to call unsubscribe() can cause significant resource leaks, as it is often
 * the case that Observable.subscribe() is called with lambdas or other constructs
 * that hold strong references to objects such as Fragments, Activities, and other
 * objects that would otherwise be released.
 *
 * Subscriptions are effectively unsubscribed, automatically, when the source
 * Observable terminates (completes with OnComplete, or emits OnError). And so,
 * in practice, the Subscription object returned from .subscribe is often ignore by
 * application code. This is done when the application programmer knows, believes, or
 * contrives to ensure that the source Observable will terminate at a predictable
 * time; for example, by using a timeout() or a bindToLifecycle() type of operation.
 *
 * So in order to ensure the Subscription object, and all its dependent resources, are
 * properly freed, one of two things has to happen: either the programmer has to
 * explicitly unsubscribe(), or the Observable has to terminate.
 *
 * RxLeakCheck uses RxHooks (provided by RxJava) to track Subscriptions and ensure that
 * one of those two things happen. At the point dumpLeaks() is called, any Subscription
 * that has not been unsubscribed in one of those two ways is considered a leaked
 * Subscription, and RxLeakCheck will report the stacktrace at the point of the original
 * subscribe.
 */
@SuppressWarnings("WeakerAccess")   // uses of public methods are in different projects
public class RxLeakCheck {
    /**
     * Default guess for index of the call frame that called Observable.subscribe()
     */
    private static final int APPLICATION_CALL_FRAME_INDEX = 8;
    private static final int MAX_CALL_FRAMES = 60;

    /**
     * Map holding allocation stacktrace of all currently-allocated Subscriptions.
     */
    static ConcurrentHashMap<Subscriber, StackTraceElement[]> subscriptionMap
            = new ConcurrentHashMap<>();

    /**
     * Install RxHook functions and begin tracking subscriptions.
     *
     * This method should be called by the host application during initialization.
     */
    @SuppressWarnings("unchecked")  // generics are hell with hook functions
    public static void initialize() {
        /*
         * Intercept calls to source Observable's onSubscribe method.
         *
         * This hook is called at the point in Observable.subscribe() where Rx intends
         * to connect the Subscriber with its Observable, which would normally be done
         * by calling Observable.onSubscribe(Subscriber). Our function implementation
         * below replaces that behavior and instead installs our Tracker objects.
         *
         * Parameters are:
         *  observable - the original source Observable
         *  onSubscribe - the onSubscribe method of the source Observable (that would
         *      normally be invoked to complete the subscription).
         *  subscriber - A Subscriber that maps, directly or via encapsulation, to the
         *      onNext/onComplete/onError Actions provided by application code or
         *      internal operators.
         */
        RxJavaHooks.setOnObservableStart((observable, onSubscribe) -> subscriber -> {
            // Avoid tracking subscriptions associated with internal operators
            // that use Observable.unsafeSubscribe (and therefore are not SafeSubscribers).
            // It wouldn't necessarily be wrong to track these, but it would add clutter
            // and hurt performance.
            if (!(subscriber instanceof SafeSubscriber)) {
                onSubscribe.call(subscriber);
                return;
            }

            Subscriber<?> actual = (Subscriber<?>) subscriber;
            onSubscribe.call(new SubscriberTracker(actual));
        });

        /*
         * Modify Subscription object returned by Observable.subscribe()
         *
         * This hook function is invoked just before returning a Subscription
         * object to the caller of Observable.subscribe(). We use the opportunity
         * to return our own SubscriptionTracker object instead of the actual
         * Subscription.
         */
        RxJavaHooks.setOnObservableReturn(subscription -> {
            // Ignore internal operators that use Observable.unsafeSubscribe().
            if (!(subscription instanceof SafeSubscriber)) {
                return subscription;
            }
            return new SubscriptionTracker(subscription);
        });
    }

    /**
     * Get the index of the application code responsible for a Subscription.
     *
     * When we take a stack trace to track a Subscription allocation, we capture
     * "extra" frames, one for each function call between the application code
     * that called .subscribe() and the point where we call getStackTrace().
     *
     * This method attempts to determine the index of the application code that
     * called .subscribe(). This index is not always the same, because there
     * are different overloads of .subscribe() that may have different call
     * paths.
     *
     * @param stes The full stack trace captured
     * @return our best guess as to the index of the application code calling .subscribe.
     */
    private static int getApplicationCallFrameIndex(StackTraceElement[] stes) {
        // Strategy: walk down the stack until we find the most-recent
        // "Observable.subscribe" method, and then continue walking until
        // we hit something that isn't in the rx.* namespace and isn't our
        // own RxLeakCheck code.
        boolean foundSubscribe = false;
        for (int i = 0; i < stes.length; i++) {
            String className = stes[i].getClassName();
            String methodName = stes[i].getMethodName();

            if ("rx.Observable".equals(className) && "subscribe".equals(methodName)) {
                foundSubscribe = true;
                continue;
            }

            if (className != null
                    && (className.startsWith("rx.")
                    || (className.startsWith("com.mykwillis.rxleakcheck.RxLeakCheck")
                    && !className.equals("com.mykwillis.rxleakcheck.RxLeakCheckTest")))) {
                continue;
            }

            if (foundSubscribe) {
                return i;
            }
        }

        Timber.w("RxLeakCheck: failed to locate application code");
        return Math.min(stes.length - 1, APPLICATION_CALL_FRAME_INDEX);
    }

    private static StackTraceElement getApplicationStackTraceElement(StackTraceElement[] stes) {
        return stes[getApplicationCallFrameIndex(stes)];
    }

    /**
     * Determine the location of leaked Subscriptions.
     *
     * We group all of the leaked Subscriptions by the call frame we identify as
     * being the responsible application-level call to Observable.subscribe.
     *
     * This method resets the tracking state, so should only be called one time
     * at application shutdown.
     */
    @SuppressLint("DefaultLocale")
    public static List<SubscriptionLeak> dumpLeaks() {
        // Set this flag to `true` if you need to see all unique stacktraces for
        // each Subscription leak, rather than just seeing one example stack trace
        // for each.
        boolean verbose = false;

        HashMap<StackTraceElement, SubscriptionLeak> offenders = new HashMap<>();

        Timber.w("RxLeakCheck: detected " + subscriptionMap.size()
                + " active Subscriptions at shutdown.");

        /*
         * Build the offenders map to hold each offending application stack frame,
         * along with the count of leaks it was responsible for.
         */
        for (StackTraceElement[] stes : subscriptionMap.values()) {
            StackTraceElement ste = getApplicationStackTraceElement(stes);
            SubscriptionLeak offender = offenders.get(ste);
            if (offender == null) {
                offenders.put(ste, new SubscriptionLeak(ste, stes));
            } else {
                offender.count++;
            }
        }

        // Sort the SubscriptionLeaks from most leaks to least
        ArrayList<SubscriptionLeak> offenderList = new ArrayList<>(offenders.values());
        Collections.sort(offenderList);
        Collections.reverse(offenderList);

        StringBuilder sb = new StringBuilder("RxLeakCheck: Offender Counts\n");
        sb.append("            #/Leaks   Application code most likely responsible for leak.\n");
        sb.append("            -------   --------------------------------------------------\n");
        int i = 0;
        for (SubscriptionLeak offender : offenderList) {
            sb.append(String.format("RxLeakCheck %7d   %s\n", offender.count, offender.offender));
            if (++i % 30 == 0) {
                Timber.w(sb.toString());
                sb = new StringBuilder();
            }
        }
        Timber.w(sb.toString());

        // If verbose, we print every single leak's stack frame.
        // If not, we just print one stack trace per leak
        if (verbose) {
            for (StackTraceElement[] stes : subscriptionMap.values()) {
                dumpStackTrace(stes, null);
            }
        } else {
            for (SubscriptionLeak offender : offenderList) {
                dumpStackTrace(offender.fullStack, offender.count);
            }
        }

        subscriptionMap.clear();
        return offenderList;
    }

    /**
     * Dump a stack trace to console, filtering for clarity.
     */
    @SuppressLint("DefaultLocale")
    private static void dumpStackTrace(StackTraceElement[] stes, Integer count) {
        String leakCount = " was never unsubscribed";
        if (count != null && count != 1) {
            leakCount = String.format(", or one similar, was leaked %d times", count);
        }
        StringBuilder sb = new StringBuilder(
                String.format("RxLeakCheck: The following Subscription%s:\n", leakCount));
        int framesPrinted = 0;
        for (int i = getApplicationCallFrameIndex(stes); i < stes.length; i++) {

            // filter some cruft...
            String className = stes[i].getClassName();
            if (className != null &&
                    className.startsWith("rx.internal")) {
                continue;
            }
            if (className != null &&
                    className.startsWith("com.mykwillis.rxleakcheck.RxLeakCheck")) {
                continue;
            }

            String fileName = stes[i].getFileName();
            if (fileName != null &&
                    fileName.contains("RxLeakCheck")) {
                continue;
            }

            sb.append(String.format("     [%-2d]", i));
            sb.append(stes[i].toString());
            sb.append("\n");

            if (++framesPrinted >= MAX_CALL_FRAMES) {
                break;
            }
        }
        Timber.w(sb.toString());
    }

    /**
     * Tracker for active Subscribers (observers).
     *
     * Whenever a Subscriber calls Observable.subscribe(), our RxHook intercepts
     * the request and effectively inserts a new instance of SubscriberTracker
     * between the Observable and its Subscriber. It does this by subscribing the
     * SubscriberTracker, instead of the actual Subscriber, to the source Observable,
     * and then effectively chaining any calls made to the SubscriberTracker down to
     * the Subscriber as appropriate.
     *
     * Observable --> SubscriberTracker --> Subscriber
     *
     * As we are now layered between the Observable and the Subscriber, we can
     * track the OnNext/OnCompleted/OnError events emitted by the Observable. In
     * particular, we can keep track of whether the source Observable has terminated
     * (completed or errored-out), which is the only way references to Subscriptions
     * are released by the system if they are not explicitly unsubscribed.
     *
     * We track in our database the stacktrace at the point where the SubscriberTracker
     * is created, removing this entry when (if) the source Observable terminates.
     * (The entry is also removed if the Subscription is unsubscribed; see
     * SubscriptionTracker below).
     *
     * (Note: I don't think we strictly need to derive from SafeSubscriber in this
     * class, but during implementation I found that using a simple class derived
     * from Subscriber would cause unsubscribe operations to not propogate upwards
     * to the source Observable. There may be an easier way to solve this problem,
     * but using SafeSubscriber seems to do the trick).
     *
     * (Note 2: The Subscriber we are given as the `actual` argument to our
     * constructor is actually a SafeSubscriber which was created inside of
     * Observable.create() to wrap the Subscriber provided by application code.
     * And, in the normal case where lambdas / Actions are used to define onNext/
     * onError/onCompleted, the application code is wrapped in an ActionSubscriber.
     * So the whole chain is actually something like:
     *
     * Observable -> SubscriptionTracker -> SafeSubscriber -> ActionSubscriber -> application code
     * )
     */
    public static class SubscriberTracker<T> extends SafeSubscriber<T> {
        /**
         * The SafeSubscriber created in the Observable.subscribe() call.
         */
        final Subscriber<? super T> actual;

        SubscriberTracker(Subscriber<? super T> actual) {
            super(actual);
            this.actual = actual;
            subscriptionMap.put(actual, Thread.currentThread().getStackTrace());
        }

        @Override
        public void onCompleted() {
            super.onCompleted();
            subscriptionMap.remove(actual);
            // refWatcher.watch(subscriber);
        }

        @Override
        public void onError(Throwable e) {
            super.onError(e);
            subscriptionMap.remove(actual);
            // refWatcher.watch(subscriber);
        }

        @Override
        public void onNext(T t) {
            super.onNext(t);
        }
    }

    /**
     * Tracker for Subscriptions.
     *
     * Whenever application code subscribes to an Observable with Observable.subscribe(),
     * they receive a Subscription object[*] in return. Our RxHook code intercepts calls
     * to .subscribe and allows us to return our SubscriptionTracker object instead of
     * the actual Subscription object that would otherwise be returned.
     *
     * SubscriberTracker is used to detect Observable termination, whereas SubscriptionTracker
     * is used to detect calls to unsubscribe().
     *
     * Application Code --> SubscriptionTracker --> Subscription
     *
     * [*] In practice, the Subscription returned by Observable.subscribe() is actually an
     * Rx-defined SafeSubscriber object that wraps the Subscriber itself (i.e., the original
     * Subscriber supplied in the call to .subscribe()). This means that the object passed
     * to the source Observable's OnSubscribe (as a Subscriber) is exactly the same object
     * that is returned by Observable.subscribe (as a Subscription).
     */
    public static class SubscriptionTracker implements Subscription {
        /**
         * The actual Subscription that would've been returned by Observable.subscribe().
         * Normally this is the same object passed to the source Observable.OnSubscribe()
         * (a wrapper of the original Subscriber supplied by application code).
         */
        final Subscription actual;

        SubscriptionTracker(Subscription actual) {
            this.actual = actual;
        }

        @Override
        public void unsubscribe() {
            // We rely on the fact that the Subscription object we are tracking is actually
            // one and the same as the Subscriber object tracked in the subscriptionMap.
            // See java.rx.Observable.subscribe() to verify this is true.
            //noinspection SuspiciousMethodCalls
            subscriptionMap.remove(actual);
            actual.unsubscribe();
        }

        @Override
        public boolean isUnsubscribed() {
            return actual.isUnsubscribed();
        }
    }

    /**
     * Record of a leaked subscription.
     *
     * These objects are returned from `dumpLeaks`, one for each unique point
     * in application where a Subscription was leaked one or more times.
     */
    public static class SubscriptionLeak implements Comparable<SubscriptionLeak> {
        /**
         * Best guess as to the application code responsible for the leak.
         *
         * If this does not seem to point at application code, you may need to
         * adjust APPLICATION_CALL_FRAME_INDEX for your environment. See the
         * notes at its declaration above.
         */
        public final StackTraceElement offender;

        /**
         * One example of a full stack trace of a leaked Subscription. It is
         * possible that there are other stack traces for this same Subscription
         * leak (e.g., multiple different code paths that get to the same place
         * that leaks the Subscription). See the `verbose` flag in `dumpLeaks` if
         * you need to see all of the unique traces.
         */
        public final StackTraceElement[] fullStack;

        /**
         * Count of Subscription leaks due to this application call.
         */
        public int count;

        SubscriptionLeak(StackTraceElement offender, StackTraceElement[] fullStack) {
            this.offender = offender;
            this.fullStack = fullStack;
            this.count = 1;
        }

        /**
         * Sort descending by the count of leaks.
         */
        @Override
        public int compareTo(@NonNull SubscriptionLeak o) {
            return count - o.count;
        }
    }
}






