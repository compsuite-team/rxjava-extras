package com.github.davidmoten.rx.subjects;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import rx.Subscriber;
import rx.subjects.Subject;

/**
 * A {@link Subject} that supports a maximum of one {@link Subscriber}. When
 * there is no subscriber any notifications (<code>onNext</code>,
 * <code>onError</code>, <code>onCompleted</code>) are ignored.
 * 
 * @param <T>
 *            type of items being emitted/observed by this subject
 */
public final class PublishSubjectSingleSubscriber<T> extends Subject<T, T> {

    // Visible for testing
    static final String ONLY_ONE_SUBSCRIPTION_IS_ALLOWED = "only one subscription is allowed";

    private final SingleSubscribeOnSubscribe<T> subscriberHolder;

    private PublishSubjectSingleSubscriber(SingleSubscribeOnSubscribe<T> onSubscribe) {
        super(onSubscribe);
        subscriberHolder = onSubscribe;
    }

    private PublishSubjectSingleSubscriber() {
        this(new SingleSubscribeOnSubscribe<T>());
    }

    /**
     * Returns a new instance of a {@link PublishSubjectSingleSubscriber}.
     * 
     * @return the new instance
     * @param <T> type of items being emitted/observed by this subject
     */
    public static <T> PublishSubjectSingleSubscriber<T> create() {
        return new PublishSubjectSingleSubscriber<T>();
    }

    @Override
    public void onCompleted() {
        if (subscriberHolder.subscriber != null) {
            subscriberHolder.subscriber.onCompleted();
        }
    }

    @Override
    public void onError(Throwable e) {
        if (subscriberHolder.subscriber != null)
            subscriberHolder.subscriber.onError(e);
    }

    @Override
    public void onNext(T t) {
        if (subscriberHolder.subscriber != null)
            subscriberHolder.subscriber.onNext(t);
    }

    private static class SingleSubscribeOnSubscribe<T> implements OnSubscribe<T> {

        volatile Subscriber<? super T> subscriber;

        @SuppressWarnings("rawtypes")
        private final AtomicReferenceFieldUpdater<SingleSubscribeOnSubscribe, Subscriber> SUBSCRIBER = AtomicReferenceFieldUpdater
                .newUpdater(SingleSubscribeOnSubscribe.class, Subscriber.class, "subscriber");

        @Override
        public void call(Subscriber<? super T> subscriber) {
            if (SUBSCRIBER.compareAndSet(this, null, subscriber))
                this.subscriber = subscriber;
            else
                throw new RuntimeException(ONLY_ONE_SUBSCRIPTION_IS_ALLOWED);
        }

    }

    @Override
    public boolean hasObservers() {
        return subscriberHolder.subscriber != null;
    }

}