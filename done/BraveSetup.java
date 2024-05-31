import brave.Tracing;
import brave.baggage.BaggagePropagation;
import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.B3Propagation;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

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