package io.vertx.test.core;

import io.vertx.core.Handler;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequestBuilder;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.test.core.jackson.WineAndCheese;
import org.junit.Test;

import java.io.File;
import java.net.ConnectException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class HttpClientRequestBuilderTest extends HttpTestBase {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    client = vertx.createHttpClient(new HttpClientOptions());
    server = vertx.createHttpServer(new HttpServerOptions().setPort(DEFAULT_HTTP_PORT).setHost(DEFAULT_HTTP_HOST));
  }

  @Test
  public void testGet() throws Exception {
    testRequest(HttpMethod.GET);
  }

  @Test
  public void testHead() throws Exception {
    testRequest(HttpMethod.HEAD);
  }

  @Test
  public void testDelete() throws Exception {
    testRequest(HttpMethod.DELETE);
  }

  private void testRequest(HttpMethod method) throws Exception {
    waitFor(4);
    server.requestHandler(req -> {
      assertEquals(method, req.method());
      complete();
      req.response().end();
    });
    startServer();

    HttpClientRequestBuilder builder = null;

    switch (method) {
      case GET:
        builder = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        break;
      case HEAD:
        builder = client.createHead(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        break;
      case DELETE:
        builder = client.createDelete(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        break;

      default: fail("Invalid HTTP method");
    }

    builder.send(onSuccess(resp -> {
      complete();
    }));
    builder.send(onSuccess(resp -> {
      complete();
    }));
    await();
  }

  @Test
  public void testPost() throws Exception {
    testRequestWithBody(HttpMethod.POST, false);
  }

  @Test
  public void testPostChunked() throws Exception {
    testRequestWithBody(HttpMethod.POST, true);
  }

  @Test
  public void testPut() throws Exception {
    testRequestWithBody(HttpMethod.PUT, false);
  }

  @Test
  public void testPutChunked() throws Exception {
    testRequestWithBody(HttpMethod.PUT, true);
  }

  @Test
  public void testPatch() throws Exception {
    testRequestWithBody(HttpMethod.PATCH, false);
  }

  private void testRequestWithBody(HttpMethod method, boolean chunked) throws Exception {
    String expected = TestUtils.randomAlphaString(1024 * 1024);
    File f = File.createTempFile("vertx", ".data");
    f.deleteOnExit();
    Files.write(f.toPath(), expected.getBytes());
    waitFor(2);
    server.requestHandler(req -> req.bodyHandler(buff -> {
      assertEquals(method, req.method());
      assertEquals(Buffer.buffer(expected), buff);
      complete();
      req.response().end();
    }));
    startServer();
    vertx.runOnContext(v -> {
      AsyncFile asyncFile = vertx.fileSystem().openBlocking(f.getAbsolutePath(), new OpenOptions());

      HttpClientRequestBuilder builder = null;

      switch (method) {
        case POST:
          builder = client.createPost(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        case PUT:
          builder = client.createPut(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        case PATCH:
          builder = client.createPatch(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        default:
          fail("Invalid HTTP method");
      }

      if (!chunked) {
        builder = builder.putHeader("Content-Length", "" + expected.length());
      }
      builder.sendStream(asyncFile, onSuccess(resp -> {
            assertEquals(200, resp.statusCode());
            complete();
          }));
    });
    await();
  }

  @Test
  public void testSendJsonObjectBody() throws Exception {
    JsonObject body = new JsonObject().put("wine", "Chateauneuf Du Pape").put("cheese", "roquefort");
    testSendBody(body, buff -> assertEquals(body, buff.toJsonObject()));
  }

  @Test
  public void testSendJsonObjectPojoBody() throws Exception {
    testSendBody(new WineAndCheese().setCheese("roquefort").setWine("Chateauneuf Du Pape"),
        buff -> assertEquals(new JsonObject().put("wine", "Chateauneuf Du Pape").put("cheese", "roquefort"), buff.toJsonObject()));
  }

  @Test
  public void testSendJsonArrayBody() throws Exception {
    JsonArray body = new JsonArray().add(0).add(1).add(2);
    testSendBody(body, buff -> assertEquals(body, buff.toJsonArray()));
  }

  @Test
  public void testSendBufferBody() throws Exception {
    Buffer body = TestUtils.randomBuffer(2048);
    testSendBody(body, buff -> assertEquals(body, buff));
  }

  private void testSendBody(Object body, Consumer<Buffer> checker) throws Exception {
    waitFor(2);
    server.requestHandler(req -> {
      req.bodyHandler(buff -> {
        checker.accept(buff);
        complete();
        req.response().end();
      });
    });
    startServer();
    HttpClientRequestBuilder post = client.createPost(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    if (body instanceof Buffer) {
      post.sendBuffer((Buffer) body, onSuccess(resp -> {
        complete();
      }));
    } else {
      post.sendJson(body, onSuccess(resp -> {
        complete();
      }));
    }
    await();
  }

  @Test
  public void testConnectError() throws Exception {
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onFailure(err -> {
      assertTrue(err instanceof ConnectException);
      complete();
    }));
    await();
  }

  @Test
  public void testRequestSendError() throws Exception {
    HttpClientRequestBuilder post = client.createPost(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    server.requestHandler(req -> {
      req.handler(buff -> {
        req.connection().close();
      });
    });
    startServer();
    post.putHeader("Content-Length", "2048")
        .sendStream(new ReadStream<Buffer>() {
          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
          }
          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            handler.handle(TestUtils.randomBuffer(1024));
            return this;
          }
          @Override
          public ReadStream<Buffer> pause() {
            return this;
          }
          @Override
          public ReadStream<Buffer> resume() {
            return this;
          }
          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
          }
        }, onFailure(err -> {
          assertTrue(err.getMessage().contains("Connection was closed"));
          complete();
        }));
    await();
  }

  @Test
  public void testRequestPumpError() throws Exception {
    waitFor(2);
    HttpClientRequestBuilder post = client.createPost(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    CompletableFuture<Void> done = new CompletableFuture<>();
    server.requestHandler(req -> {
      req.response().closeHandler(v -> {
        complete();
      });
      req.handler(buff -> {
        done.complete(null);
      });
    });
    Throwable cause = new Throwable();
    startServer();
    post.sendStream(new ReadStream<Buffer>() {
          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            if (handler != null) {
              done.thenAccept(v -> {
                handler.handle(cause);
              });
            }
            return this;
          }
          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            if (handler != null) {
              handler.handle(TestUtils.randomBuffer(1024));
            }
            return this;
          }
          @Override
          public ReadStream<Buffer> pause() {
            return this;
          }
          @Override
          public ReadStream<Buffer> resume() {
            return this;
          }
          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
          }
        }, onFailure(err -> {
          assertSame(cause, err);
          complete();
        }));
    await();
  }

  @Test
  public void testRequestPumpErrorNotYetConnected() throws Exception {
    HttpClientRequestBuilder post = client.createPost(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    server.requestHandler(req -> {
      fail();
    });
    Throwable cause = new Throwable();
    startServer();
    post.sendStream(new ReadStream<Buffer>() {
      Handler<Throwable> exceptionHandler;
      @Override
      public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
      }
      @Override
      public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        if (handler != null) {
          handler.handle(TestUtils.randomBuffer(1024));
          vertx.runOnContext(v -> {
            exceptionHandler.handle(cause);
          });
        }
        return this;
      }
      @Override
      public ReadStream<Buffer> pause() {
        return this;
      }
      @Override
      public ReadStream<Buffer> resume() {
        return this;
      }
      @Override
      public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        return this;
      }
    }, onFailure(err -> {
      assertSame(cause, err);
      testComplete();
    }));
    await();
  }

  @Test
  public void testAsJsonObject() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.bufferBody().asJsonObject().send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testAsJsonMapped() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.bufferBody().as(WineAndCheese.class).send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(new WineAndCheese().setCheese("Goat Cheese").setWine("Condrieu"), resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testAsJsonObjectUnknownContentType() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.bufferBody().send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.bodyAsJsonObject());
      testComplete();
    }));
    await();
  }

  @Test
  public void testAsJsonMappedUnknownContentType() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> {
      req.response().end(expected.encode());
    });
    startServer();
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.bufferBody().send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(new WineAndCheese().setCheese("Goat Cheese").setWine("Condrieu"), resp.bodyAs(WineAndCheese.class));
      testComplete();
    }));
    await();
  }

  @Test
  public void testBodyUnmarshallingError() throws Exception {
    server.requestHandler(req -> {
      req.response().end("not-json-object");
    });
    startServer();
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.bufferBody().asJsonObject().send(onFailure(err -> {
      assertTrue(err instanceof DecodeException);
      testComplete();
    }));
    await();
  }

  @Test
  public void testHttpResponseError() throws Exception {
    server.requestHandler(req -> {
      req.response().setChunked(true).write(Buffer.buffer("some-data")).close();
    });
    startServer();
    HttpClientRequestBuilder get = client.createGet(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.bufferBody().asJsonObject().send(onFailure(err -> {
      assertTrue(err instanceof VertxException);
      testComplete();
    }));
    await();
  }
}