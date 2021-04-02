package io.github.awscat;

import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import javax.annotation.*;

import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.*;
import com.google.gson.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;

import helpers.*;
import io.github.awscat.contrib.*;

// https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle
@SpringBootApplication
public class Main implements ApplicationRunner {

  public static void main(String[] args) throws Exception {
    System.err.println("main"+Arrays.asList(args));
    // args = new String[]{"dynamo:MyTable"};
    // args= new String[]{"dynamo:MyTableOnDemand,rcu=128","dynamo:MyTableOnDemand,delete=true,wcu=5"};
    System.exit(SpringApplication.exit(SpringApplication.run(Main.class, args)));
  }
  
  private final List<InputPluginProvider> inputPluginProviders = new ArrayList<>();
  private final List<OutputPluginProvider> outputPluginProviders = new ArrayList<>();

  AtomicLong in = new AtomicLong(); // pre-filter
  AtomicLong out = new AtomicLong(); // post-filter
  AtomicLong request = new AtomicLong(); // output plugin
  AtomicLong success = new AtomicLong(); // output plugin
  AtomicLong failure = new AtomicLong(); // output plugin

  private final LocalMeter rate = new LocalMeter();

  class Working {
    private final Number in; // filter
    // private final Number out; // filter
    // private final Number request;
    private final Number out;
    private final Number err;
    String rate;
    public String toString() {
      return getClass().getSimpleName()+new Gson().toJson(this);
    }
    Working(Number in, Number out, Number request, Number success, Number failure) {
      this.in = in;
      // this.out = out;
      // this.request = request;
      this.out = success;
      this.err = failure;
    }
  }

  // lazy
  private String failuresName;
  private PrintStream failuresPrintStream;
  private final Supplier<PrintStream> failures = Suppliers.memoize(()->{
    try {
      String now = CharMatcher.anyOf("1234567890").retainFrom(Instant.now().toString().substring(0, 20));
      String randomString = Hashing.sha256().hashInt(new SecureRandom().nextInt()).toString().substring(0, 7);
      //###TODO BUFFEREDOUTPUTSTREAM HERE??
      //###TODO BUFFEREDOUTPUTSTREAM HERE??
      //###TODO BUFFEREDOUTPUTSTREAM HERE??
      return failuresPrintStream = new PrintStream(new File(failuresName = String.format("failures-%s-%s.json", now, randomString)));
      //###TODO BUFFEREDOUTPUTSTREAM HERE??
      //###TODO BUFFEREDOUTPUTSTREAM HERE??
      //###TODO BUFFEREDOUTPUTSTREAM HERE??
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  });

  @Value("${project-version}")
  private String projectVersion;

  /**
   * ctor
   */
  public Main(List<InputPluginProvider> inputPluginProviders, List<OutputPluginProvider> outputPluginProviders) {
    log("ctor");
    this.inputPluginProviders.addAll(inputPluginProviders);
    this.inputPluginProviders.add(new SystemInPluginProvider()); // ensure last
    this.outputPluginProviders.addAll(outputPluginProviders);
    this.outputPluginProviders.add(new SystemOutPluginProvider()); // ensure last
  }

  /**
   * run
   */
  @Override
  public void run(ApplicationArguments args) throws Exception {
    log("awscat.jar", projectVersion);

    CatOptions options = Args.parseOptions(args, CatOptions.class);
    
    log("options", options);

    if (options.help) {

      log("Usage:");
      indent("awscat.jar [options] <source> [<target>]");
      
      log("options:");
      indent("--help");
      indent("--filter");
      indent("--modify");

      log("source:");
      for (InputPluginProvider plugin : inputPluginProviders) {
        indent(plugin.help());
      }
      log("target:");
      for (OutputPluginProvider plugin : outputPluginProviders) {
        indent(plugin.help());
      }

      return;
    }

    //###TODO probably eradicate this
    if (args.getNonOptionArgs().size() == 0) //###TODO probably eradicate this
      throw new Exception("missing source!");
    //###TODO probably eradicate this

    // input plugin
    String source = "-";
    if (args.getNonOptionArgs().size()>0)
      source = args.getNonOptionArgs().get(0);
    InputPluginProvider inputPluginProvider = resolveInputPlugin(source);
    
    // output plugin
    String target = "-";
    if (args.getNonOptionArgs().size()>1)
      target = args.getNonOptionArgs().get(1);
    OutputPluginProvider outputPluginProvider = resolveOutputPlugin(target);

    InputPlugin inputPlugin = inputPluginProvider.activate(source);
    log("inputPlugin", inputPlugin); //###TODO log inputPluginProvider here instead of inputPlugin
    Supplier<OutputPlugin> outputPluginSupplier = outputPluginProvider.activate(target);
    log("outputPlugin", outputPluginProvider);

    // ----------------------------------------------------------------------
    // main loop
    // ----------------------------------------------------------------------

    inputPlugin.setListener(jsonElements->{
      debug("listener", Iterables.size(jsonElements));
      return new FutureRunner() {
        Working work = new Working(in, out, request, success, failure);
        {
          run(()->{
            OutputPlugin outputPlugin = outputPluginSupplier.get();
            ExpressionsJs expressions = new ExpressionsJs();
            for (JsonElement jsonElement : jsonElements) {
              run(() -> {
                in.incrementAndGet();
                if (has(options.filter)) {
                  expressions.e(jsonElement);
                  if (expressions.eval(options.filter)) {
                    out.incrementAndGet(); //###TODO eradicate?
                    run(() -> {
                      request.incrementAndGet(); //###TODO eradicate?
                      for (String modify : options.modify )
                        expressions.eval(modify);
                      return outputPlugin.write(expressions.e());
                    }, result -> {
                      success.incrementAndGet();
                    }, e -> {
                      log(e);
                      // e.printStackTrace();
                      failure.incrementAndGet();
                      failures.get().println(jsonElement); // pre-transform
                    }, () -> {
                      rate.add(1);
                    });
                  }
                }
                return Futures.immediateVoidFuture();
              });
            }
            return outputPlugin.flush();
          }, ()->{
            work.rate = rate.toString();
            log(work);
            //###TODO flush failuresPrintStream here??
            //###TODO flush failuresPrintStream here??
            //###TODO flush failuresPrintStream here??
          });
        }
      }.get();

    });

    int mtu = outputPluginProvider.mtu();
    log("start", "mtu", mtu);
    inputPlugin.read(mtu).get();
    log("finish", "mtu", mtu);

    // log("output.flush().get();111");
    // outputPlugin.flush().get();
    // log("output.flush().get();222");

  }

  @PreDestroy
  public void destroy() {
    if (failuresName != null) {
      try {
        failuresPrintStream.close();
      } finally {
        log(" ########## " + failuresName + " ########## ");
      }
    }
  }

  private InputPluginProvider resolveInputPlugin(String source) {
    for (InputPluginProvider provider : inputPluginProviders) {
      try {
        if (provider.canActivate(source))
          return provider;
      } catch (Exception e) {
        log(provider.name(), e);
      }
    }
    return new SystemInPluginProvider();
  }

  private OutputPluginProvider resolveOutputPlugin(String target) {
    for (OutputPluginProvider provider : outputPluginProviders) {
      try {
        if (provider.canActivate(target))
          return provider;
      } catch (Exception e) {
        log(provider.name(), e);
      }
    }
    return new SystemOutPluginProvider();
  }

  private boolean has(String s) {
    return Strings.nullToEmpty(s).length()>0; 
  }

  // private static AppState parseState(String base64) throws Exception {
  //   byte[] bytes = BaseEncoding.base64().decode(base64);
  //   ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
  //   InputStream in = new GZIPInputStream(bais);
  //   String json = CharStreams.toString(new InputStreamReader(in));

  //   AppState state = new Gson().fromJson(json, AppState.class);

  //   // sigh.
  //   for (Map<String, AttributeValue> exclusiveStartKey : state.exclusiveStartKeys) {
  //     exclusiveStartKey.putAll(Maps.transformValues(exclusiveStartKey, (value) -> {
  //       //###TODO handle all types
  //       //###TODO handle all types
  //       //###TODO handle all types
  //       return AttributeValue.builder().s(value.s()).build();
  //     }));
  //   }

  //   return state;
  // }

  // private static String renderState(AppState state) throws Exception {
  //   ByteArrayOutputStream baos = new ByteArrayOutputStream();
  //   try (OutputStream out = new GZIPOutputStream(baos, true)) {
  //     out.write(new Gson().toJson(state).getBytes());
  //   }
  //   return BaseEncoding.base64().encode(baos.toByteArray());
  // }

  private void log(Object... args) {
    new LogHelper(this).log(args);
  }
  private void indent(Object... args) {
    System.err.println("   "+String.join(" ", Lists.transform(Arrays.asList(args), s->""+s)));
  }

  private void debug(Object... args) {
    new LogHelper(this).debug(args);
  }
}
