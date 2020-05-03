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

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

/**
 * 将Call<R>转换成Call<T>.
 */
public interface CallAdapter<R, T> {
  /**
   * 返回此适配器将HTTP响应主体转换为Java对象时使用的值类型。
   * 例如，Call<Repo>的响应类型为Repo。此类型用于准备传递给#adapt的调用。
   * 这个类型将被Call用来传递给adapt()方法。
   *
   * 注意：此类型通常与提供给此CallAdapter.Factory的returnType不同。
   */
  Type responseType();

  /**
   * 返回委托给Call的T的实例，实际上就执行真正的转换方法。
   *
   * 例如，给定假设的实用程序Async的实例，该实例将返回一个新的Async <R>，该Async <R>在运行时会调用该调用。
   *
   * 例如，以下方法实例会把Call<R>转换成Async<R>
   *
   * @Override
   *  public <R> Async<R> adapt(final Call<R> call) {
   *    return Async.create(new Callable<Response<R>>() {
   *      @Override
   *      public Response<R> call() throws Exception {
   *        return call.execute();
   *      }
   *    });
   *  }
   */
  T adapt(Call<R> call);

  /**
   * 根据Retrofit.create(class)方法的返回类型来创建CallAdapter实例
   */
  abstract class Factory {
    /**
     * 根据{returnType}创建合适的CallAdapter，如果returnType不是自己想处理的类型，就返回null。
     */
    public abstract @Nullable CallAdapter<?, ?> get(
        Type returnType, Annotation[] annotations, Retrofit retrofit);

    /**
     * 获取泛型参数的顶级类型，例如传入(1, Map<String, ? extend Runnable>)返回的是Runnable.
     */
    protected static Type getParameterUpperBound(int index, ParameterizedType type) {
      return Utils.getParameterUpperBound(index, type);
    }

    /**
     * 返回type的原始类型，例如List<? extends Runnable>返回的是List.class
     */
    protected static Class<?> getRawType(Type type) {
      return Utils.getRawType(type);
    }
  }
}
