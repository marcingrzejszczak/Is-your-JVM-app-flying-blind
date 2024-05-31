import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

private static PrometheusMeterRegistry startPrometheusEndpoint() {
  PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  try {
    HttpServer server = HttpServer.create(new InetSocketAddress(4567), 0);
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