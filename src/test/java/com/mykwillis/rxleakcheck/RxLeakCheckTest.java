package com.mykwillis.rxleakcheck;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;

import static org.junit.Assert.assertEquals;

public class RxLeakCheckTest {
    @Test
    public void intializeAndCheck() {
        RxLeakCheck.initialize();
        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();
        assertEquals(0, leaks.size());
    }

    @Test
    public void detectsCompletion() {
        RxLeakCheck.initialize();

        Observable.just(true) // completes immediately
                .subscribe(b -> { });

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();
        assertEquals(0, leaks.size());
    }

    @Test
    public void detectsCompletion_take() {
        RxLeakCheck.initialize();

        Observable.interval(0, 1, TimeUnit.SECONDS)
                .take(1)    // completes
                .subscribe(b -> { });

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();
        assertEquals(0, leaks.size());
    }

    @Test
    public void detectsCompletion_subscribeUntil() {
        RxLeakCheck.initialize();

        Observable.interval(0, 1, TimeUnit.SECONDS)
                .takeUntil(Observable.just(false))
                .subscribe(b -> { });

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();
        assertEquals(0, leaks.size());
    }

    @Test
    public void detectsUnsubscribe() {
        RxLeakCheck.initialize();

        Subscription s = Observable.interval(1, TimeUnit.SECONDS)
                .subscribe(b -> { });
        s.unsubscribe();

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();
        assertEquals(0, leaks.size());
    }

    @Test
    public void detectsLeak_simple() {
        RxLeakCheck.initialize();

        Observable.interval(1, TimeUnit.SECONDS)
                .subscribe(b -> { });

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_simple", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_withOnError() {
        RxLeakCheck.initialize();

        Observable.interval(1, TimeUnit.SECONDS)
                .subscribe(b -> { }, e -> {});

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_withOnError", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_withOnComplete() {
        RxLeakCheck.initialize();

        Observable.interval(1, TimeUnit.SECONDS)
                .subscribe(b -> { }, e -> {}, () -> {});

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_withOnComplete", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_withSubscriber() {
        RxLeakCheck.initialize();

        Observable.interval(1, TimeUnit.SECONDS)
                .subscribe(new Subscriber<Long>() {
                               @Override
                               public void onCompleted() { }

                               @Override
                               public void onError(Throwable throwable) { }

                               @Override
                               public void onNext(Long aLong) { }
                           });

        List < RxLeakCheck.SubscriptionLeak > leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_withSubscriber", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_behaviorSubject() {
        RxLeakCheck.initialize();

        BehaviorSubject.create((Void)null)  // does not complete
                .subscribe(b -> { });

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_behaviorSubject", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_observeOn() {
        RxLeakCheck.initialize();

        Observable.timer(1, TimeUnit.SECONDS)
                .observeOn(Schedulers.io())
                .subscribe(tick -> {});

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_observeOn", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_flatMap() {
        RxLeakCheck.initialize();

        Observable.timer(1, TimeUnit.SECONDS)
                .flatMap(Observable::just)
                .subscribe(tick -> {});

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_flatMap", leak.offender.getMethodName());
    }

    @Test
    public void detectsLeak_subscribeOn() {
        RxLeakCheck.initialize();

        Observable.timer(1, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.io())
                .flatMap(Observable::just)
                .subscribe(tick -> {});

        List<RxLeakCheck.SubscriptionLeak> leaks = RxLeakCheck.dumpLeaks();

        assertEquals(1, leaks.size());
        RxLeakCheck.SubscriptionLeak leak = leaks.get(0);
        assertEquals(RxLeakCheckTest.class.getName(), leak.offender.getClassName());
        assertEquals( "detectsLeak_subscribeOn", leak.offender.getMethodName());
    }

}