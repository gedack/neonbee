package io.neonbee.internal.handler;

import java.net.HttpURLConnection;
import java.util.Optional;

import io.neonbee.logging.LoggingFacade;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;

/**
 * Similar but simplified to the io.vertx.ext.web.handler.impl.LoggerHandlerImpl, w/ minor adaptions for logging the
 * correlationId.
 */
public class LoggerHandler implements Handler<RoutingContext> {
    /**
     * The facaded logger to use to log the events.
     */
    private static final LoggingFacade LOGGER = LoggingFacade.create();

    /**
     * Convenience method as similar other Vert.x handler implementations (e.g. LoggerHandler).
     *
     * @return The ErrorHandler
     */
    public static LoggerHandler create() {
        return new LoggerHandler();
    }

    @Override
    public void handle(RoutingContext routingContext) {
        routingContext.addBodyEndHandler(nothing -> log(routingContext, System.currentTimeMillis()));
        routingContext.next();
    }

    private void log(RoutingContext routingContext, long timestamp) {
        HttpServerRequest request = routingContext.request();

        String version;
        switch (request.version()) {
        case HTTP_1_0:
            version = "HTTP/1.0";
            break;
        case HTTP_1_1:
            version = "HTTP/1.1";
            break;
        case HTTP_2:
            version = "HTTP/2.0";
            break;
        default:
            version = "-";
            break;
        }

        int statusCode = request.response().getStatusCode();
        String message = String.format("%s - %s %s %s %d %d - %d ms",
                Optional.ofNullable(request.remoteAddress()).map(SocketAddress::host).orElse(null), request.method(),
                request.uri(), version, statusCode, request.response().bytesWritten(),
                System.currentTimeMillis() - timestamp);

        LOGGER.correlateWith(routingContext);

        if (statusCode >= HttpURLConnection.HTTP_INTERNAL_ERROR) {
            LOGGER.error(message);
        } else if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            LOGGER.warn(message);
        } else {
            LOGGER.info(message);
        }
    }
}
