/*
 * Copyright (C) 2012 Square, Inc.
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

import static java.util.Collections.unmodifiableList;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.http.GET;
import retrofit2.http.HTTP;
import retrofit2.http.Header;
import retrofit2.http.Url;

/**
 * Retrofit adapts a Java interface to HTTP calls by using annotations on the declared methods to
 * define how requests are made. Create instances using {@linkplain Builder the builder} and pass
 * your interface to {@link #create} to generate an implementation.
 *
 * <p>For example,
 *
 * Retrofit retrofit = new Retrofit.Builder()
 *     .baseUrl("https://api.example.com/")
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response<User> user = api.getUser().execute();
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 */
public final class Retrofit {
  //网络请求的配置对象，存储网络请求相关的配置，如网络请求的方法、数据转换器、网络请求适配器、网络请求工厂、基地址等。
  private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();

  final okhttp3.Call.Factory callFactory;
  final HttpUrl baseUrl;
  final List<Converter.Factory> converterFactories;
  final List<CallAdapter.Factory> callAdapterFactories;
  final @Nullable Executor callbackExecutor; //onResponse、onError回调执行的线程池
  final boolean validateEagerly; //是否要提前验证并解析method

  Retrofit(
      okhttp3.Call.Factory callFactory,
      HttpUrl baseUrl,
      List<Converter.Factory> converterFactories,
      List<CallAdapter.Factory> callAdapterFactories,
      @Nullable Executor callbackExecutor,
      boolean validateEagerly) {
    this.callFactory = callFactory;
    this.baseUrl = baseUrl;
    this.converterFactories = converterFactories; // Copy+unmodifiable at call site.
    this.callAdapterFactories = callAdapterFactories; // Copy+unmodifiable at call site.
    this.callbackExecutor = callbackExecutor;
    this.validateEagerly = validateEagerly;
  }

  /**
   *
   * 创建用户定义的Service的具体实现；
   *
   * 给定方法的相对路径是从描述请求类型的方法的注释中获得的。
   * 内置方法是GET，PUT，POST，PATCH，HEAD，DELETE和OPTIONS。
   * 您可以将自定义HTTP方法与@HTTP一起使用。
   * 对于动态URL，请省略注释上的路径，并使用@Url注释第一个参数。
   *
   * 方法参数可以通过使用@Path注释来替换URL的一部分。替换部分由用花括号括起来的标识符（例如“ {foo}”）表示。要将项目添加到URL的查询字符串中，请使用@Query。
   *
   * 请求的主体由@Body注释表示。该对象将通过Converter.Factory实例之一转换为请求表示形式。RequestBody也可以用于原始表示。
   *
   * 方法注释和相应的参数注释支持其他请求主体格式：
   *
   *    * {@link retrofit2.http.FormUrlEncoded} - 具有@Field参数批注指定的键/值对的表单编码数据。
   *    * {@link retrofit2.http.Multipart} - 符合RFC 2388的multi part数据，其部分由@Part参数注释指定。
   *
   * 可以使用@Headers方法注释为请求添加其他静态headers。对于每个请求的header控制，请使用@Header注释参数。
   *
   * 默认情况下，开发者在interface中定义的方法返回代表HTTP请求的{@link Call}
   *
   * Call的泛型参数是响应体的具体类型，并将由Converter.Factory实例之一进行转换。也可以用ResponseBody表示原始的响应类型。
   * 如果不关心响应内容，可以使用Void
   *
   * 例如：
   * public interface CategoryService {
   *   @POST("category/{cat}/")
   *   Call<List<Item>> categoryList(@Path("cat") String a, @Query("page") int b);
   * }
   * </pre>
   */
  @SuppressWarnings("unchecked") // Single-interface proxy creation guarded by parameter safety.
  public <T> T create(final Class<T> service) {
    validateServiceInterface(service); //验证接口的有效性，也就是验证开发者写的Api Service的格式对不对
    //这个方法也就是返回一个
    return (T)
        Proxy.newProxyInstance(
            service.getClassLoader(), //传入Service的classLoader，方便JVM进行类的加载
            new Class<?>[] {service}, //需要实现的Interface数组
                //真正的代理对象，外面调用到的就是invoke的实现
            new InvocationHandler() {
              private final Platform platform = Platform.get();
              private final Object[] emptyArgs = new Object[0];

              @Override
              public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
                  throws Throwable {
                // 如果该方法是Object中的方法，就直接执行普通调用
                // 因为所有的类都是继承Object的，例如equals、hashCode这些方法是不需要代理的
                if (method.getDeclaringClass() == Object.class) {
                  return method.invoke(this, args);
                }
                args = args != null ? args : emptyArgs; //方法参数
                return platform.isDefaultMethod(method) //java8支持的接口中方法的默认实现
                    ? platform.invokeDefaultMethod(method, service, proxy, args)
                    : loadServiceMethod(method).invoke(args); //这里可以看到，最后开发者对于ApiService.getUser()这样的调用，其实就是对于ServiceMethod.invoke()方法的调用
              }
            });
  }

  /**
   * 验证Service的格式
   * @param service
   */
  private void validateServiceInterface(Class<?> service) {
    if (!service.isInterface()) { //必须是Java interface
      throw new IllegalArgumentException("API declarations must be interfaces.");
    }

    Deque<Class<?>> check = new ArrayDeque<>(1);
    check.add(service);
    while (!check.isEmpty()) {
      Class<?> candidate = check.removeFirst();
      if (candidate.getTypeParameters().length != 0) { //这个service本身不能带泛型参数
        StringBuilder message =
            new StringBuilder("Type parameters are unsupported on ").append(candidate.getName());
        if (candidate != service) {
          message.append(" which is an interface of ").append(service.getName());
        }
        throw new IllegalArgumentException(message.toString());
      }
      Collections.addAll(check, candidate.getInterfaces());
    }

    if (validateEagerly) { //尽早验证参数的有效性，并且解析方法，提前缓存ServiceMethod对象
      Platform platform = Platform.get();
      for (Method method : service.getDeclaredMethods()) {
        //1.方法不能有默认实现
        //2.不能是静态方法
        if (!platform.isDefaultMethod(method) && !Modifier.isStatic(method.getModifiers())) {
          loadServiceMethod(method); //预加载
        }
      }
    }
  }

  /**
   * 加载ServiceMethod，如果缓存中已经有了，就从缓存中拿出来。如果是新方法，就执行相应的封装，返回一个ServiceMethod。
   * @param method Api Service中声明的方法
   * @return
   */
  ServiceMethod<?> loadServiceMethod(Method method) {
    ServiceMethod<?> result = serviceMethodCache.get(method);// 缓存的key是method，value是封装的ServiceMethod
    if (result != null) return result;

    synchronized (serviceMethodCache) {
      result = serviceMethodCache.get(method);
      if (result == null) {
        result = ServiceMethod.parseAnnotations(this, method);
        serviceMethodCache.put(method, result);
      }
    }
    return result;
  }

  /**
   * The factory used to create {@linkplain okhttp3.Call OkHttp calls} for sending a HTTP requests.
   * Typically an instance of {@link OkHttpClient}.
   */
  public okhttp3.Call.Factory callFactory() {
    return callFactory;
  }

  /** The API base URL. */
  public HttpUrl baseUrl() {
    return baseUrl;
  }

  /**
   * Returns a list of the factories tried when creating a {@linkplain #callAdapter(Type,
   * Annotation[])} call adapter}.
   */
  public List<CallAdapter.Factory> callAdapterFactories() {
    return callAdapterFactories;
  }

  /**
   * 从用户创建的callAdapterFactories中根据returnType返回一个合适的CallAdapter
   *
   * @throws IllegalArgumentException if no call adapter available for {@code type}.
   */
  public CallAdapter<?, ?> callAdapter(Type returnType, Annotation[] annotations) {
    return nextCallAdapter(null, returnType, annotations);
  }

  /**
   *
   * 根据传入的{@param returnType}来匹配到对应的Adapter,返回这个CallAdapter。
   * 系统默认情况下是传入了一个{@link DefaultCallAdapterFactory}进去的，它接受的是Object,所以它其实对任何类型都是适用的。
   *
   * @param skipPast 是否要跳过某个CallAdapter.
   * @throws IllegalArgumentException 对于returnType没有合适的CallAdapter.
   */
  public CallAdapter<?, ?> nextCallAdapter(
      @Nullable CallAdapter.Factory skipPast, Type returnType, Annotation[] annotations) {
    Objects.requireNonNull(returnType, "returnType == null");
    Objects.requireNonNull(annotations, "annotations == null");

    int start = callAdapterFactories.indexOf(skipPast) + 1; //会把skipPast前面的都跳过
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
      //这里其实是依赖各个CallAdapter的实现自己要去根据returnType做判断，看是不是自己想要的类型，然后才返回CallAdapter的实例
      CallAdapter<?, ?> adapter = callAdapterFactories.get(i).get(returnType, annotations, this);
      if (adapter != null) {
        return adapter;
      }
    }
    //没找到对应的callAdapter，下面都是报错信息
    StringBuilder builder =
        new StringBuilder("Could not locate call adapter for ").append(returnType).append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns an unmodifiable list of the factories tried when creating a {@linkplain
   * #requestBodyConverter(Type, Annotation[], Annotation[]) request body converter}, a {@linkplain
   * #responseBodyConverter(Type, Annotation[]) response body converter}, or a {@linkplain
   * #stringConverter(Type, Annotation[]) string converter}.
   */
  public List<Converter.Factory> converterFactories() {
    return converterFactories;
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
   * {@linkplain #converterFactories() factories}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<T, RequestBody> requestBodyConverter(
      Type type, Annotation[] parameterAnnotations, Annotation[] methodAnnotations) {
    return nextRequestBodyConverter(null, type, parameterAnnotations, methodAnnotations);
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
   * {@linkplain #converterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<T, RequestBody> nextRequestBodyConverter(
      @Nullable Converter.Factory skipPast,
      Type type,
      Annotation[] parameterAnnotations,
      Annotation[] methodAnnotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(parameterAnnotations, "parameterAnnotations == null");
    Objects.requireNonNull(methodAnnotations, "methodAnnotations == null");

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter.Factory factory = converterFactories.get(i);
      Converter<?, RequestBody> converter =
          factory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<T, RequestBody>) converter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate RequestBody converter for ").append(type).append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
   * {@linkplain #converterFactories() factories}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<ResponseBody, T> responseBodyConverter(Type type, Annotation[] annotations) {
    return nextResponseBodyConverter(null, type, annotations);
  }

  /**
   * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
   * {@linkplain #converterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<ResponseBody, T> nextResponseBodyConverter(
      @Nullable Converter.Factory skipPast, Type type, Annotation[] annotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(annotations, "annotations == null");

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter<ResponseBody, ?> converter =
          converterFactories.get(i).responseBodyConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<ResponseBody, T>) converter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate ResponseBody converter for ")
            .append(type)
            .append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link String} from the available {@linkplain
   * #converterFactories() factories}.
   */
  public <T> Converter<T, String> stringConverter(Type type, Annotation[] annotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(annotations, "annotations == null");

    for (int i = 0, count = converterFactories.size(); i < count; i++) {
      Converter<?, String> converter =
          converterFactories.get(i).stringConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<T, String>) converter;
      }
    }

    // Nothing matched. Resort to default converter which just calls toString().
    //noinspection unchecked
    return (Converter<T, String>) BuiltInConverters.ToStringConverter.INSTANCE;
  }

  /**
   * The executor used for {@link Callback} methods on a {@link Call}. This may be {@code null}, in
   * which case callbacks should be made synchronously on the background thread.
   */
  public @Nullable Executor callbackExecutor() {
    return callbackExecutor;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Build a new {@link Retrofit}.
   *
   * <p>Calling {@link #baseUrl} is required before calling {@link #build()}. All other methods are
   * optional.
   */
  public static final class Builder {
    //平台类型对象（Android、Java）
    private final Platform platform;
    // 网络请求工厂类，默认使用OkHttpCall（工厂方法模式）
    private @Nullable okhttp3.Call.Factory callFactory;
    // 网络请求的url地址
    private @Nullable HttpUrl baseUrl;
    // 数据转换器工厂的集合
    private final List<Converter.Factory> converterFactories = new ArrayList<>();
    // 网络请求适配器工厂的集合，默认是DefaultCallAdapterFactory
    private final List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>();
    // 回调方法执行器，在Android上默认是封装了handler的MainThreadExecutor，默认作用是：切换线程（子线程 -> 主线程）
    private @Nullable Executor callbackExecutor;
    private boolean validateEagerly;

    Builder(Platform platform) {
      this.platform = platform;
    }

    public Builder() {
      this(Platform.get());
    }

    Builder(Retrofit retrofit) {
      platform = Platform.get();
      callFactory = retrofit.callFactory;
      baseUrl = retrofit.baseUrl;

      // Do not add the default BuiltIntConverters and platform-aware converters added by build().
      for (int i = 1,
              size = retrofit.converterFactories.size() - platform.defaultConverterFactoriesSize();
          i < size;
          i++) {
        converterFactories.add(retrofit.converterFactories.get(i));
      }

      // Do not add the default, platform-aware call adapters added by build().
      for (int i = 0,
              size =
                  retrofit.callAdapterFactories.size() - platform.defaultCallAdapterFactoriesSize();
          i < size;
          i++) {
        callAdapterFactories.add(retrofit.callAdapterFactories.get(i));
      }

      callbackExecutor = retrofit.callbackExecutor;
      validateEagerly = retrofit.validateEagerly;
    }

    /**
     * The HTTP client used for requests.
     *
     * <p>This is a convenience method for calling {@link #callFactory}.
     */
    public Builder client(OkHttpClient client) {
      return callFactory(Objects.requireNonNull(client, "client == null"));
    }

    /**
     * Specify a custom call factory for creating {@link Call} instances.
     *
     * <p>Note: Calling {@link #client} automatically sets this value.
     */
    public Builder callFactory(okhttp3.Call.Factory factory) {
      this.callFactory = Objects.requireNonNull(factory, "factory == null");
      return this;
    }

    /**
     * 设置base Url
     *
     * @see #baseUrl(HttpUrl)
     */
    public Builder baseUrl(URL baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      return baseUrl(HttpUrl.get(baseUrl.toString()));
    }

    /**
     * Set the API base URL.
     *
     * @see #baseUrl(HttpUrl)
     */
    public Builder baseUrl(String baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      return baseUrl(HttpUrl.get(baseUrl));
    }

    /**
     * Set the API base URL.
     *
     * <p>The specified endpoint values (such as with {@link GET @GET}) are resolved against this
     * value using {@link HttpUrl#resolve(String)}. The behavior of this matches that of an {@code
     * <a href="">} link on a website resolving on the current URL.
     *
     * baseURL应该以反斜杠(/)结尾。
     *
     * <p>A trailing {@code /} ensures that endpoints values which are relative paths will correctly
     * append themselves to a base which has path components.
     *
     * 正确的例子：
     * Base URL: http://example.com/api/
     * Endpoint: foo/bar/<br>
     * Result: http://example.com/api/foo/bar/
     *
     * 错误的例子：
     * Base URL: http://example.com/api
     * Endpoint: foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * 这个函数强制要求了baseUrl必须以/结尾。
     *
     * <p><b>Endpoint values which contain a leading {@code /} are absolute.</b>
     *
     * <p>Absolute values retain only the host from {@code baseUrl} and ignore any specified path
     * components.
     *
     * <p>Base URL: http://example.com/api/<br>
     * Endpoint: /foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p>Base URL: http://example.com/<br>
     * Endpoint: /foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p><b>Endpoint values may be a full URL.</b>
     *
     * <p>Values which have a host replace the host of {@code baseUrl} and values also with a scheme
     * replace the scheme of {@code baseUrl}.
     *
     * <p>Base URL: http://example.com/<br>
     * Endpoint: https://github.com/square/retrofit/<br>
     * Result: https://github.com/square/retrofit/
     *
     * <p>Base URL: http://example.com<br>
     * Endpoint: //github.com/square/retrofit/<br>
     * Result: http://github.com/square/retrofit/ (note the scheme stays 'http')
     */
    public Builder baseUrl(HttpUrl baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      List<String> pathSegments = baseUrl.pathSegments();
      if (!"".equals(pathSegments.get(pathSegments.size() - 1))) {
        throw new IllegalArgumentException("baseUrl must end in /: " + baseUrl);
      }
      this.baseUrl = baseUrl;
      return this;
    }

    /** Add converter factory for serialization and deserialization of objects. */
    public Builder addConverterFactory(Converter.Factory factory) {
      converterFactories.add(Objects.requireNonNull(factory, "factory == null"));
      return this;
    }

    /**
     * Add a call adapter factory for supporting service method return types other than {@link
     * Call}.
     */
    public Builder addCallAdapterFactory(CallAdapter.Factory factory) {
      callAdapterFactories.add(Objects.requireNonNull(factory, "factory == null"));
      return this;
    }

    /**
     * The executor on which {@link Callback} methods are invoked when returning {@link Call} from
     * your service method.
     *
     * <p>Note: {@code executor} is not used for {@linkplain #addCallAdapterFactory custom method
     * return types}.
     */
    public Builder callbackExecutor(Executor executor) {
      this.callbackExecutor = Objects.requireNonNull(executor, "executor == null");
      return this;
    }

    /** Returns a modifiable list of call adapter factories. */
    public List<CallAdapter.Factory> callAdapterFactories() {
      return this.callAdapterFactories;
    }

    /** Returns a modifiable list of converter factories. */
    public List<Converter.Factory> converterFactories() {
      return this.converterFactories;
    }

    /**
     * When calling {@link #create} on the resulting {@link Retrofit} instance, eagerly validate the
     * configuration of all methods in the supplied interface.
     */
    public Builder validateEagerly(boolean validateEagerly) {
      this.validateEagerly = validateEagerly;
      return this;
    }

    /**
     * 创建Retrofit实例
     *
     * <p>Note: If neither {@link #client} nor {@link #callFactory} is called a default {@link
     * OkHttpClient} will be created and used.
     */
    public Retrofit build() {
      if (baseUrl == null) {
        throw new IllegalStateException("Base URL required.");
      }
      //如果没有设置client（也是callFactory）或者是callFactory的话，默认使用OkHttpClient
      okhttp3.Call.Factory callFactory = this.callFactory;
      if (callFactory == null) {
        callFactory = new OkHttpClient();
      }

      Executor callbackExecutor = this.callbackExecutor;
      if (callbackExecutor == null) {
        callbackExecutor = platform.defaultCallbackExecutor();
      }

      // 默认的适配器添加在尾部.
      List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>(this.callAdapterFactories);
      callAdapterFactories.addAll(platform.defaultCallAdapterFactories(callbackExecutor));

      // 默认是不会转换的
      List<Converter.Factory> converterFactories =
          new ArrayList<>(
              1 + this.converterFactories.size() + platform.defaultConverterFactoriesSize());

      // Add the built-in converter factory first. This prevents overriding its behavior but also
      // ensures correct behavior when using converters that consume all types.
      converterFactories.add(new BuiltInConverters()); //内建的Converter加在最前面
      converterFactories.addAll(this.converterFactories);
      converterFactories.addAll(platform.defaultConverterFactories());

      return new Retrofit(
          callFactory,
          baseUrl,
          unmodifiableList(converterFactories),
          unmodifiableList(callAdapterFactories),
          callbackExecutor,
          validateEagerly);
    }
  }
}
