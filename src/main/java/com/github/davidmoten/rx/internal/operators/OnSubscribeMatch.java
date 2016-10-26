package com.github.davidmoten.rx.internal.operators;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.github.davidmoten.util.Preconditions;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Producer;
import rx.Subscriber;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.internal.operators.BackpressureUtils;
import rx.internal.util.unsafe.MpscLinkedQueue;
import rx.internal.util.unsafe.UnsafeAccess;

public final class OnSubscribeMatch<A, B, K, C> implements OnSubscribe<C> {

    private final Observable<A> a;
    private final Observable<B> b;
    private final Func1<? super A, ? extends K> aKey;
    private final Func1<? super B, ? extends K> bKey;
    private final Func2<? super A, ? super B, C> combiner;
    private final long requestSize;

    public OnSubscribeMatch(Observable<A> a, Observable<B> b, Func1<? super A, ? extends K> aKey,
            Func1<? super B, ? extends K> bKey, Func2<? super A, ? super B, C> combiner,
            long requestSize) {
        Preconditions.checkNotNull(a, "a should not be null");
        Preconditions.checkNotNull(b, "b should not be null");
        Preconditions.checkNotNull(aKey, "aKey cannot be null");
        Preconditions.checkNotNull(bKey, "bKey cannot be null");
        Preconditions.checkNotNull(combiner, "combiner cannot be null");
        Preconditions.checkArgument(requestSize >= 1, "requestSize must be >=1");
        this.a = a;
        this.b = b;
        this.aKey = aKey;
        this.bKey = bKey;
        this.combiner = combiner;
        this.requestSize = requestSize;
    }

    @Override
    public void call(Subscriber<? super C> child) {
        AtomicReference<Receiver> receiverHolder = new AtomicReference<Receiver>();
        MySubscriber<A, K> aSub = new MySubscriber<A, K>(Source.A, receiverHolder, requestSize);
        MySubscriber<B, K> bSub = new MySubscriber<B, K>(Source.B, receiverHolder, requestSize);
        child.add(aSub);
        child.add(bSub);
        MyProducer<A, B, K, C> producer = new MyProducer<A, B, K, C>(a, b, aKey, bKey, combiner,
                aSub, bSub, child, requestSize);
        receiverHolder.set(producer);
        child.setProducer(producer);
        a.unsafeSubscribe(aSub);
        b.unsafeSubscribe(bSub);
    }

    @SuppressWarnings("serial")
    private static final class MyProducer<A, B, K, C> extends AtomicLong
            implements Producer, Receiver {

        private final Queue<Object> queue;
        private final Map<K, Queue<A>> as = new ConcurrentHashMap<K, Queue<A>>();
        private final Map<K, Queue<B>> bs = new ConcurrentHashMap<K, Queue<B>>();
        private final Func1<? super A, ? extends K> aKey;
        private final Func1<? super B, ? extends K> bKey;
        private final Func2<? super A, ? super B, C> combiner;
        private final Subscriber<? super C> child;
        private final MySubscriber<A, K> aSub;
        private final MySubscriber<B, K> bSub;
        private final long requestSize;

        // mutable fields, guarded by `this` and `wip` value
        private final AtomicInteger wip = new AtomicInteger(0);
        private boolean requestAll = false;
        private int requestFromA = 0;
        private int requestFromB = 0;

        // completion state machine
        private int completed = COMPLETED_NONE;
        // completion states
        private static final int COMPLETED_NONE = 0;
        private static final int COMPLETED_A = 1;
        private static final int COMPLETED_B = 2;
        private static final int COMPLETED_BOTH = 3;

        MyProducer(Observable<A> a, Observable<B> b, Func1<? super A, ? extends K> aKey,
                Func1<? super B, ? extends K> bKey, Func2<? super A, ? super B, C> combiner,
                MySubscriber<A, K> aSub, MySubscriber<B, K> bSub, Subscriber<? super C> child,
                long requestSize) {
            this.aKey = aKey;
            this.bKey = bKey;
            this.combiner = combiner;
            this.child = child;
            this.aSub = aSub;
            this.bSub = bSub;
            this.requestSize = requestSize;
            if (UnsafeAccess.isUnsafeAvailable()) {
                queue = new MpscLinkedQueue<Object>();
            } else {
                queue = new ConcurrentLinkedQueue<Object>();
            }
        }

        @Override
        public void request(long n) {
            if (BackpressureUtils.validate(n)) {
                BackpressureUtils.getAndAddRequest(this, n);
                drain();
            }
        }

        void drain() {
            if (wip.getAndIncrement() == 0) {
                do {
                    long r;
                    if (requestAll) {
                        r = Long.MAX_VALUE;
                    } else {
                        r = get();
                        if (r == Long.MAX_VALUE) {
                            requestAll = true;
                        }
                    }
                    int emitted = 0;
                    while (r > emitted & !queue.isEmpty()) {
                        if (child.isUnsubscribed()) {
                            return;
                        }
                        // note will not return null
                        Object v = queue.poll();

                        if (v instanceof Item) {
                            Item item = (Item) v;
                            Emitted em = handleItem(item.value, item.source);
                            if (em == Emitted.FINISHED) {
                                return;
                            } else if (em == Emitted.ONE) {
                                emitted += 1;
                            }
                        } else if (v instanceof Source) {
                            //source completed
                            Status status = handleCompleted((Source) v);
                            if (status == Status.FINISHED) {
                                return;
                            }
                        } else {
                            // v must be an error
                            clear();
                            child.onError((Throwable) v);
                            return;
                        }
                        if (r == Long.MAX_VALUE) {
                            emitted = 0;
                        } else if (r == emitted) {
                            r = addAndGet(-emitted);
                            emitted = 0;
                        }
                    }
                    if (emitted > 0) {
                        // queue was exhausted but requests were not
                        addAndGet(-emitted);
                    }
                } while (wip.decrementAndGet() != 0);
            }
        }

        private Emitted handleItem(Object value, Source source) {
            Emitted result = Emitted.NONE;

            // logic duplication occurs below
            // would be nice to simplify without making code
            // unreadable. A bit of a toss-up.
            if (source == Source.A) {
                // look for match
                @SuppressWarnings("unchecked")
                A a = (A) value;
                K key;
                try {
                    key = aKey.call(a);
                } catch (Throwable e) {
                    clear();
                    child.onError(e);
                    return Emitted.FINISHED;
                }
                Queue<B> q = bs.get(key);
                if (q == null) {
                    // cache value
                    add(as, key, a);
                } else {
                    // emit match
                    B b = poll(bs, q, key);
                    C c;
                    try {
                        c = combiner.call(a, b);
                    } catch (Throwable e) {
                        clear();
                        child.onError(e);
                        return Emitted.FINISHED;
                    }
                    child.onNext(c);
                    result = Emitted.ONE;
                }
                // if the other source has completed and there
                // is nothing to match with then we should stop
                if (completed == COMPLETED_B && bs.isEmpty()) {
                    // can finish
                    clear();
                    child.onCompleted();
                    return Emitted.FINISHED;
                } else {
                    requestFromA += 1;
                }
            } else {
                // look for match
                @SuppressWarnings("unchecked")
                B b = (B) value;
                K key;
                try {
                    key = bKey.call(b);
                } catch (Throwable e) {
                    clear();
                    child.onError(e);
                    return Emitted.FINISHED;
                }
                Queue<A> q = as.get(key);
                if (q == null) {
                    // cache value
                    add(bs, key, b);
                } else {
                    // emit match
                    A a = poll(as, q, key);
                    C c;
                    try {
                        c = combiner.call(a, b);
                    } catch (Throwable e) {
                        clear();
                        child.onError(e);
                        return Emitted.FINISHED;
                    }
                    child.onNext(c);
                    result = Emitted.ONE;
                }
                // if the other source has completed and there
                // is nothing to match with then we should stop
                if (completed == COMPLETED_A && as.isEmpty()) {
                    // can finish
                    clear();
                    child.onCompleted();
                    return Emitted.FINISHED;
                } else {
                    requestFromB += 1;
                }
            }
            // requests are batched so that each source gets a turn
            checkToRequestMore();
            return result;
        }

        private enum Emitted {
            ONE, NONE, FINISHED;
        }

        private Status handleCompleted(Source source) {
            completed(source);
            final boolean done;
            if (source == Source.A) {
                aSub.unsubscribe();
                done = (completed == COMPLETED_BOTH) || (completed == COMPLETED_A && as.isEmpty());
            } else {
                bSub.unsubscribe();
                done = (completed == COMPLETED_BOTH) || (completed == COMPLETED_B && bs.isEmpty());
            }
            if (done) {
                clear();
                child.onCompleted();
                return Status.FINISHED;
            } else {
                checkToRequestMore();
                return Status.KEEP_GOING;
            }
        }

        private enum Status {
            FINISHED, KEEP_GOING;
        }

        private void checkToRequestMore() {
            if (requestFromA == requestSize && completed == COMPLETED_B) {
                requestFromA = 0;
                aSub.requestMore(requestSize);
            } else if (requestFromB == requestSize && completed == COMPLETED_A) {
                requestFromB = 0;
                bSub.requestMore(requestSize);
            } else if (requestFromA == requestSize && requestFromB == requestSize) {
                requestFromA = 0;
                requestFromB = 0;
                aSub.requestMore(requestSize);
                bSub.requestMore(requestSize);
            }
        }

        private void completed(Source source) {
            if (source == Source.A) {
                if (completed == COMPLETED_NONE) {
                    completed = COMPLETED_A;
                } else if (completed == COMPLETED_B) {
                    completed = COMPLETED_BOTH;
                }
            } else {
                if (completed == COMPLETED_NONE) {
                    completed = COMPLETED_B;
                } else if (completed == COMPLETED_A) {
                    completed = COMPLETED_BOTH;
                }
            }
        }

        private void clear() {
            as.clear();
            bs.clear();
            queue.clear();
            aSub.unsubscribe();
            bSub.unsubscribe();
        }

        private static <K, T> void add(Map<K, Queue<T>> map, K key, T value) {
            Queue<T> q = map.get(key);
            if (q == null) {
                q = new LinkedList<T>();
                map.put(key, q);
            }
            q.offer(value);
        }

        private static <K, T> T poll(Map<K, Queue<T>> map, Queue<T> q, K key) {
            T t = q.poll();
            if (q.isEmpty()) {
                map.remove(key);
            }
            return t;
        }

        @Override
        public void offer(Object item) {
            queue.offer(item);
            drain();
        }

    }

    interface Receiver {
        void offer(Object item);
    }

    private static class MySubscriber<T, K> extends Subscriber<T> {

        private final AtomicReference<Receiver> receiver;
        private final Source source;

        MySubscriber(Source source, AtomicReference<Receiver> receiver, long requestSize) {
            this.source = source;
            this.receiver = receiver;
            request(requestSize);
        }

        @Override
        public void onNext(T t) {
            // TODO can reduce allocations by emitting one source
            // without wrapping as an item. Would have to use NULL_SENTINEL
            // though because cannot rely on queue accepting nulls.
            receiver.get().offer(new Item(t, source));
        }

        @Override
        public void onCompleted() {
            receiver.get().offer(source);
        }

        @Override
        public void onError(Throwable e) {
            receiver.get().offer(e);
        }

        public void requestMore(long n) {
            request(n);
        }

    }

    static final class Item {
        final Object value;
        final Source source;

        Item(Object value, Source source) {
            this.value = value;
            this.source = source;
        }
    }

    enum Source {
        A, B;
    }
}
