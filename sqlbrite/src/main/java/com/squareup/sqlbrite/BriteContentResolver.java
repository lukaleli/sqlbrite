/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sqlbrite;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import java.util.Arrays;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.subscriptions.Subscriptions;

import static com.squareup.sqlbrite.SqlBrite.Logger;
import static com.squareup.sqlbrite.SqlBrite.Query;

/**
 * A lightweight wrapper around {@link ContentResolver} which allows for continuously observing
 * the result of a query. Create using a {@link SqlBrite} instance.
 */
public final class BriteContentResolver {
  private final Handler contentObserverHandler = new Handler(Looper.getMainLooper());

  private final ContentResolver contentResolver;
  private final Logger logger;

  private volatile boolean logging;

  BriteContentResolver(@NonNull ContentResolver contentResolver, @NonNull Logger logger) {
    this.contentResolver = contentResolver;
    this.logger = logger;
  }

  /** Control whether debug logging is enabled. */
  public void setLoggingEnabled(boolean enabled) {
    logging = enabled;
  }

  /**
   * Create an observable which will notify subscribers with a {@linkplain Query query} for
   * execution. Subscribers are responsible for <b>always</b> closing {@link Cursor} instance
   * returned from the {@link Query}.
   * <p>
   * Subscribers will receive an immediate notification for initial data as well as subsequent
   * notifications for when the supplied {@code uri}'s data changes. Unsubscribe when you no longer
   * want updates to a query.
   * <p>
   * Note: To skip the immediate notification and only receive subsequent notifications when data
   * has changed call {@code skip(1)} on the returned observable.
   * <p>
   * <b>Warning:</b> this method does not perform the query! Only by subscribing to the returned
   * {@link Observable} will the operation occur.
   *
   * @see ContentResolver#query(Uri, String[], String, String[], String)
   * @see ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final Uri uri, @Nullable final String[] projection,
      @Nullable final String selection, @Nullable final String[] selectionArgs, @Nullable
      final String sortOrder, final boolean notifyForDescendents) {
    final Query query = new Query() {
      @Override public Cursor run() {
        return contentResolver.query(uri, projection, selection, selectionArgs, sortOrder);
      }
    };
    OnSubscribe<Query> subscribe = new OnSubscribe<Query>() {
      @Override public void call(final Subscriber<? super Query> subscriber) {
        final ContentObserver observer = new ContentObserver(contentObserverHandler) {
          @Override public void onChange(boolean selfChange) {
            if (logging) {
              log("QUERY\n  uri: %s\n  projection: %s\n  selection: %s\n  selectionArgs: %s\n  "
                      + "sortOrder: %s\n  notifyForDescendents: %s", uri,
                  Arrays.toString(projection), selection, Arrays.toString(selectionArgs), sortOrder,
                  notifyForDescendents);
            }
            subscriber.onNext(query);
          }
        };
        contentResolver.registerContentObserver(uri, notifyForDescendents, observer);
        subscriber.add(Subscriptions.create(new Action0() {
          @Override public void call() {
            contentResolver.unregisterContentObserver(observer);
          }
        }));
      }
    };
    Observable<Query> queryObservable = Observable.create(subscribe) //
        .startWith(query) //
        .onBackpressureLatest();
    return new QueryObservable(queryObservable);
  }

  private void log(String message, Object... args) {
    if (args.length > 0) message = String.format(message, args);
    logger.log(message);
  }
}
