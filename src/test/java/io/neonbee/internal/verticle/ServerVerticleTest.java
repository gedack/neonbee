package io.neonbee.internal.verticle;

import static com.google.common.truth.Truth.assertThat;
import static io.neonbee.internal.verticle.ServerVerticle.BEGIN_PUBLIC_KEY;
import static io.neonbee.internal.verticle.ServerVerticle.END_PUBLIC_KEY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.neonbee.test.base.NeonBeeTestBase;
import io.neonbee.test.helper.WorkingDirectoryBuilder;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;

public class ServerVerticleTest extends NeonBeeTestBase {
    @Test
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    void testDetermineSessionHandling() throws InterruptedException {
        Vertx mockedVertx = mock(Vertx.class);
        when(mockedVertx.isClustered()).thenReturn(false);

        JsonObject config = new JsonObject();

        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_NONE);

        config.remove(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING);
        config.put(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING, "");
        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_NONE);

        config.remove(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING);
        config.put(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING, "none");
        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_NONE);

        config.remove(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING);
        config.put(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING, "local");
        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_LOCAL);

        config.remove(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING);
        config.put(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING, "clustered");
        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_LOCAL);
    }

    @Test
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    void testDetermineSessionHandlingClustered() throws InterruptedException {
        Vertx mockedVertx = mock(Vertx.class);
        when(mockedVertx.isClustered()).thenReturn(true);

        JsonObject config = new JsonObject();

        config.put(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING, "local");
        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_LOCAL);

        config.remove(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING);
        config.put(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING, "clustered");
        assertThat(ServerVerticle.determineSessionHandling(mockedVertx, config))
                .isEqualTo(ServerVerticle.CONFIG_PROPERTY_SESSION_HANDLING_CLUSTERED);
    }

    @Test
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    void testMaximumInitialLineAndCookieSizes(VertxTestContext testCtx) {
        Checkpoint checkpoint = testCtx.checkpoint(4);

        // positive case, both initial line and headers have a small size
        createRequest(HttpMethod.GET, "/any404").putHeader("smallHeader", "x")
                .send(testCtx.succeeding(response -> testCtx.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(404);
                    checkpoint.flag();
                })));

        // positive edge case for the initial line, assuming we are sending a HTTP GET request, the initial line will be
        // in form of "GET /<URI> HTTP/1.1", so the minimum initial line (incl. the leading slash) takes up 14 bytes,
        // leaving the rest for the URI. By default the URI may be 4096 bytes long, that leaves 4082 bytes for the URI!
        // Note: Since the upgrade to Vert.x 4.0 we cannot send the full length of 4096 bytes, we have to send one byte
        // less. See https://github.com/eclipse-vertx/vert.x/commit/9363774e996a9549261ff2e30aa55f1e1cbe20a6
        createRequest(HttpMethod.GET, "/" + "x".repeat(4081)).send(testCtx.succeeding(response -> testCtx.verify(() -> {
            assertThat(response.statusCode()).isEqualTo(404);
            checkpoint.flag();
        })));

        // negative edge case for the initial line
        createRequest(HttpMethod.GET, "/" + "x".repeat(4083)).send(testCtx.succeeding(response -> testCtx.verify(() -> {
            assertThat(response.statusCode()).isEqualTo(414); // URI too long
            checkpoint.flag();
        })));

        // negative case for the header, all headers may not exceed 8192 bytes
        createRequest(HttpMethod.GET, "/any404").putHeader("largeHeader", "x".repeat(10000))
                .send(testCtx.succeeding(response -> testCtx.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(431);
                    checkpoint.flag();
                })));
    }

    @Test
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    void testLargerMaximumInitialLineAndCookieSizesConfig(VertxTestContext testCtx) {
        Checkpoint checkpoint = testCtx.checkpoint(2);

        // by default the initial line length may only be 4096 bytes
        createRequest(HttpMethod.GET, "/" + "x".repeat(4083)).send(testCtx.succeeding(response -> testCtx.verify(() -> {
            assertThat(response.statusCode()).isEqualTo(404);
            checkpoint.flag();
        })));

        // by default the maximum header size may only be 8196 bytes
        createRequest(HttpMethod.GET, "/any404").putHeader("largeHeader", "x".repeat(10000))
                .send(testCtx.succeeding(response -> testCtx.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(404);
                    checkpoint.flag();
                })));
    }

    @Test
    void testExtractPubSecKeys() {
        String algorithm = "RS256";
        String pubKey = "Hodor";
        JsonArray pubKeys = new JsonArray().add(new JsonObject().put("algorithm", "RS256").put("publicKey", pubKey));

        Buffer expectedBuffer = Buffer.buffer(BEGIN_PUBLIC_KEY + "\n" + pubKey + "\n" + END_PUBLIC_KEY);
        List<PubSecKeyOptions> keys = ServerVerticle.extractPubSecKeys(pubKeys);
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getAlgorithm()).isEqualTo(algorithm);
        assertThat(keys.get(0).getBuffer()).isEqualTo(expectedBuffer);
    }

    @Override
    protected WorkingDirectoryBuilder provideWorkingDirectoryBuilder(TestInfo testInfo, VertxTestContext testContext) {
        if ("testLargerMaximumInitialLineAndCookieSizesConfig"
                .equals(testInfo.getTestMethod().map(Method::getName).orElse(null))) {
            return WorkingDirectoryBuilder.standard().setCustomTask(root -> {
                Path serverVerticleConfigPath = root.resolve(WorkingDirectoryBuilder.CONFIG_DIR)
                        .resolve(ServerVerticle.class.getName() + ".json");
                JsonObject config = new JsonObject().put("config",
                        new JsonObject().put("maxHeaderSize", 32768).put("maxInitialLineLength", 8192));
                testContext.verify(() -> {
                    Buffer oldConfig = Buffer.buffer(Files.readAllBytes(serverVerticleConfigPath));
                    Buffer newConfig = oldConfig.toJsonObject().mergeIn(config, true).toBuffer();
                    Files.write(serverVerticleConfigPath, newConfig.getBytes());
                });
            });
        } else {
            return super.provideWorkingDirectoryBuilder(testInfo, testContext);
        }
    }
}
