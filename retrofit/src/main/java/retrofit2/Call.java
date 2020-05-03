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
package retrofit2;

import java.io.IOException;
import okhttp3.Request;
import okio.Timeout;

/**
 * 对Retrofit方法的调用，该方法将请求发送到Web服务器并返回响应。
 * 每个调用都会产生自己的HTTP请求和响应对。
 * 使用clone（）对相同的Web服务器进行具有相同参数的多个调用；这可用于实施轮询或重试失败的呼叫。
 *
 * 可以execute（）来进行同步执行，也可以使用enqueue（retrofit2.Callback
 * <T>）异步执行。无论哪种情况，都可以使用cancel（）随时取消请求。
 * 正在写请求或读取其响应的调用可能会收到IOException；这是按设计工作。
 *
 * @param <T> Successful response body type.
 */
public interface Call<T> extends Cloneable {
  /**
   * Synchronously send the request and return its response.
   *
   * @throws IOException if a problem occurred talking to the server.
   * @throws RuntimeException (and subclasses) if an unexpected error occurs creating the request or
   *     decoding the response.
   */
  Response<T> execute() throws IOException;

  /**
   * Asynchronously send the request and notify {@code callback} of its response or if an error
   * occurred talking to the server, creating the request, or processing the response.
   */
  void enqueue(Callback<T> callback);

  /**
   * Returns true if this call has been either {@linkplain #execute() executed} or {@linkplain
   * #enqueue(Callback) enqueued}. It is an error to execute or enqueue a call more than once.
   */
  boolean isExecuted();

  /**
   * Cancel this call. An attempt will be made to cancel in-flight calls, and if the call has not
   * yet been executed it never will be.
   */
  void cancel();

  /** True if {@link #cancel()} was called. */
  boolean isCanceled();

  /**
   * Create a new, identical call to this one which can be enqueued or executed even if this call
   * has already been.
   */
  Call<T> clone();

  /** The original HTTP request. */
  Request request();

  /**
   * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
   * body, server processing, and reading the response body. If the call requires redirects or
   * retries all must complete within one timeout period.
   */
  Timeout timeout();
}
