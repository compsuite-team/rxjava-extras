package com.github.davidmoten.rx;

import junit.framework.TestCase;
import junit.framework.TestSuite;
import rx.Observable;
import rx.functions.Func1;

import com.github.davidmoten.rx.testing.TestingHelper;

public class TestingHelperCountTest extends TestCase {

    public static TestSuite suite() {

        return TestingHelper.function(COUNT)
        // test empty
                .name("testCountOfEmptyReturnsEmpty").fromEmpty().expect(0)
                // test non-empty count
                .name("testCountOfTwoReturnsTwo").from(5, 6).expect(2)
                // test single input
                .name("testCountOfOneReturnsOne").from(5).expect(1)
                // unsub before completions
                .name("testCountofTwoReturnsOneWhenUnsubscribedAfterOne").from(5, 6, 7).expect(3)
                // get test suites
                .testSuite(TestingHelperCountTest.class);
    }

    public void testDummy() {
        // just here to fool eclipse
    }

    private static final Func1<Observable<Integer>, Observable<Integer>> COUNT = new Func1<Observable<Integer>, Observable<Integer>>() {
        @Override
        public Observable<Integer> call(Observable<Integer> o) {
            return o.count();
        }
    };

}