package io.vertx.ext.rx.java.test;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.rx.java.ObservableHandler;
import io.vertx.ext.rx.java.RxHelper;
import org.junit.Test;
import rx.Subscription;
import rx.exceptions.OnErrorNotImplementedException;

import static org.junit.Assert.*;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
  public class AsyncResultHandlerTest {

  @Test
  public void testCompleteWithSuccessBeforeSubscribe() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    o.asHandler().handle(Future.completedFuture("abc"));
    MySubscriber<String> subscriber = new MySubscriber<>();
    o.subscribe(subscriber);
    subscriber.assertItem("abc").assertCompleted().assertEmpty();
  }

  @Test
  public void testCompleteWithSuccessAfterSubscribe() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    MySubscriber<String> subscriber = new MySubscriber<>();
    o.subscribe(subscriber);
    subscriber.assertEmpty();
    o.asHandler().handle(Future.completedFuture("abc"));
    subscriber.assertItem("abc").assertCompleted().assertEmpty();
  }

  @Test
  public void testCompleteWithFailureBeforeSubscribe() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    Throwable failure = new Throwable();
    o.asHandler().handle(Future.completedFuture(failure));
    MySubscriber<String> subscriber = new MySubscriber<>();
    o.subscribe(subscriber);
    subscriber.assertError(failure).assertEmpty();
  }

  @Test
  public void testCompleteWithFailureAfterSubscribe() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    MySubscriber<String> subscriber = new MySubscriber<>();
    o.subscribe(subscriber);
    subscriber.assertEmpty();
    Throwable failure = new Throwable();
    o.asHandler().handle(Future.completedFuture(failure));
    subscriber.assertError(failure).assertEmpty();
  }

  @Test
  public void testUnsubscribeBeforeResolve() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    MySubscriber<String> subscriber = new MySubscriber<>();
    Subscription sub = o.subscribe(subscriber);
    sub.unsubscribe();
    assertTrue(sub.isUnsubscribed());
    subscriber.assertCompleted().assertEmpty();
  }

  @Test
  public void testCompleteTwice() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    MySubscriber<String> subscriber = new MySubscriber<>();
    o.subscribe(subscriber);
    o.asHandler().handle(Future.completedFuture("abc"));
    o.asHandler().handle(Future.completedFuture("def"));
    subscriber.assertItem("abc").assertCompleted().assertEmpty();
  }

  @Test
  public void testFailTwice() {
    ObservableHandler<String> o = RxHelper.observableHandler();
    MySubscriber<String> subscriber = new MySubscriber<>();
    o.subscribe(subscriber);
    Throwable failure = new Throwable();
    o.asHandler().handle(Future.completedFuture(failure));
    o.asHandler().handle(Future.completedFuture(new Throwable()));
    subscriber.assertError(failure).assertEmpty();
  }

  @Test
  public void testFulfillAdaptedSubscriber() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber);
    o.handle(Future.completedFuture("abc"));
    subscriber.assertItem("abc").assertCompleted().assertEmpty();
  }

  @Test
  public void testRejectAdaptedSubscriber() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber);
    Exception e = new Exception();
    o.handle(Future.completedFuture(e));
    subscriber.assertError(e).assertEmpty();
  }

  @Test
  public void testFulfillAdaptedFunctions1() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber::onNext);
    o.handle(Future.completedFuture("abc"));
    subscriber.assertItem("abc").assertEmpty();
  }

  @Test
  public void testFulfillAdaptedFunctions2() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber::onNext, subscriber::onError);
    o.handle(Future.completedFuture("abc"));
    subscriber.assertItem("abc").assertEmpty();
  }

  @Test
  public void testFulfillAdaptedFunctions3() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber::onNext, subscriber::onError, subscriber::onCompleted);
    o.handle(Future.completedFuture("abc"));
    subscriber.assertItem("abc").assertCompleted().assertEmpty();
  }

  @Test
  public void testRejectAdaptedFunctions1() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber::onNext);
    Exception cause = new Exception();
    try {
      o.handle(Future.completedFuture(cause));
    } catch (OnErrorNotImplementedException e) {
      assertSame(cause, e.getCause());
    }
    subscriber.assertEmpty();
  }

  @Test
  public void testRejectAdaptedFunctions2() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber::onNext, subscriber::onError);
    Exception cause = new Exception();
    o.handle(Future.completedFuture(cause));
    subscriber.assertError(cause).assertEmpty();
  }

  @Test
  public void testRejectAdaptedFunctions3() {
    MySubscriber<String> subscriber = new MySubscriber<>();
    Handler<AsyncResult<String>> o = RxHelper.toHandler(subscriber::onNext, subscriber::onError, subscriber::onCompleted);
    Exception cause = new Exception();
    o.handle(Future.completedFuture(cause));
    subscriber.assertError(cause).assertEmpty();
  }
}
