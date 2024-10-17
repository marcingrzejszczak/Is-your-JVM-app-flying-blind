package com.example;

import brave.Tracing;
import brave.baggage.BaggagePropagation;
import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.B3Propagation;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplySenderContext;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.handler.TracingAwareMeterObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Objects;

/**
 * Hello world!
 */
public class ClientApp {

  private static final Logger log = LoggerFactory.getLogger(ClientApp.class);

  public static void main(String[] args) throws Exception {

      //================================================= CONFIG =========================================

    PrometheusMeterRegistry prometheusMeterRegistry = startPrometheusEndpoint();

    // [Brave component] Example of using a SpanHandler. SpanHandler is a component
// that gets called when a span is finished. Here we have an example of setting it
// up with sending spans
// in a Zipkin format to the provided location via the UrlConnectionSender
// (through the <io.zipkin.reporter2:zipkin-sender-urlconnection> dependency)
// Another option could be to use a TestSpanHandler for testing purposes.
    AsyncZipkinSpanHandler spanHandler = AsyncZipkinSpanHandler
        .create(URLConnectionSender.create("http://localhost:9411/api/v2/spans"));

// [Brave component] CurrentTraceContext is a Brave component that allows you to
// retrieve the current TraceContext.
    ThreadLocalCurrentTraceContext braveCurrentTraceContext = ThreadLocalCurrentTraceContext.newBuilder()
        .addScopeDecorator(MDCScopeDecorator.get()) // Example of Brave's
        // automatic MDC setup
        .build();

// [Micrometer Tracing component] A Micrometer Tracing wrapper for Brave's
// CurrentTraceContext
    CurrentTraceContext bridgeContext = new BraveCurrentTraceContext(braveCurrentTraceContext);

// [Brave component] Tracing is the root component that allows to configure the
// tracer, handlers, context propagation etc.
    Tracing tracing = Tracing.newBuilder()
        .currentTraceContext(braveCurrentTraceContext)
        .supportsJoin(false)
        .traceId128Bit(true)
        // For Baggage to work you need to provide a list of fields to propagate
        .propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
            .build())
        .sampler(Sampler.ALWAYS_SAMPLE)
        .addSpanHandler(spanHandler)
        .build();


// [Brave component] Tracer is a component that handles the life-cycle of a span
    brave.Tracer braveTracer = tracing.tracer();

// [Micrometer Tracing component] A Micrometer Tracing wrapper for Brave's Tracer
    Tracer tracer = new BraveTracer(braveTracer, bridgeContext, new BraveBaggageManager());

    ObservationRegistry observationRegistry = ObservationRegistry.create();
    observationRegistry.observationConfig().observationHandler(new TracingAwareMeterObservationHandler<>(new DefaultMeterObservationHandler(prometheusMeterRegistry), tracer));
    observationRegistry.observationConfig().observationHandler(new FirstMatchingCompositeObservationHandler(new PropagatingReceiverTracingObservationHandler<>(tracer, new BravePropagator(tracing)), new PropagatingSenderTracingObservationHandler<>(tracer, new BravePropagator(tracing)), new DefaultTracingObservationHandler(tracer)));

    observationRegistry.observationConfig().observationFilter(context -> context.addLowCardinalityKeyValue(
        KeyValue.of("appName", "conferenceClientApp")));


      //================================================= APP =========================================

  RequestReplySenderContext<HttpRequest.Builder, HttpResponse> context = new RequestReplySenderContext<>(
      (carrier, key, value) -> Objects.requireNonNull(carrier).header(key, value));

  HttpClient client = HttpClient.newHttpClient();
  HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(URI.create("http://localhost:7070/"))
      .GET();
  context.setCarrier(builder);
  Observation.createNotStarted("http.client.requests", () -> context, observationRegistry)
      .observeChecked(() -> {
          HttpRequest request = builder.build();
          HttpResponse<String> send = client.send(request, BodyHandlers.ofString());
          log.info("GOT RESPONSE");
          context.setResponse(send);
      });

  }



  private static PrometheusMeterRegistry startPrometheusEndpoint() {
    PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(4568), 0);
      server.createContext("/prometheus", httpExchange -> {
        String response = prometheusRegistry.scrape();
        httpExchange.sendResponseHeaders(200, response.getBytes().length);
        try (OutputStream os = httpExchange.getResponseBody()) {
          os.write(response.getBytes());
        }
      });

      new Thread(server::start).start();
      return prometheusRegistry;
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
