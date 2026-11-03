package codes.nord;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointTest {
    private HttpServer mock;
    private Process app;
    private URI endpoint;
    private volatile int authStatus, actionStatus;
    private volatile String upstreamBody;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    record Call(String path, String method, String body, String contentType, String accept) {}

    @BeforeAll void start() throws Exception {
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            calls.add(new Call(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Accept")));
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/auth") ? authStatus : actionStatus, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        mock.start();
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        endpoint = URI.create("http://127.0.0.1:" + port + "/endpoint");
        Files.createDirectories(Path.of("target"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        app = new ProcessBuilder(java, "-Dsecret=qazWSXedc", "-Dmock=http://127.0.0.1:" + mock.getAddress().getPort() + "/",
            "-jar", System.getProperty("sut.jar", "sut/internal-0.0.1-SNAPSHOT.jar"), "--server.port=" + port)
            .redirectErrorStream(true).redirectOutput(Path.of("target/application.log").toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) throw new IllegalStateException("Приложение завершилось. См. target/application.log");
            try { client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()); return; }
            catch (java.io.IOException e) { Thread.sleep(150); }
        }
        throw new IllegalStateException("Приложение не запустилось за 40 секунд");
    }
    @AfterAll void stop() throws Exception {
        if (app != null) { app.destroy(); if (!app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) app.destroyForcibly(); }
        if (mock != null) mock.stop(0);
    }
    @BeforeEach void reset() { calls.clear(); authStatus = actionStatus = 200; upstreamBody = "arbitrary external body"; }
    String token() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT); }
    String form(String token, String action) {
        return (token == null ? "" : "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&")
            + (action == null ? "" : "action=" + URLEncoder.encode(action, StandardCharsets.UTF_8));
    }
    HttpResponse<String> send(String token, String action, String key) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json");
        if (key != null) request.header("X-Api-Key", key);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(form(token, action))).build(), HttpResponse.BodyHandlers.ofString());
    }
    void result(String token, String action, String expected) throws Exception {
        var response = send(token, action, "qazWSXedc");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"), "Ответ должен быть JSON: " + response);
        JsonNode body = json.readTree(response.body());
        assertEquals(expected, body.path("result").asText(), "Ответ приложения: " + response.body());
        if (expected.equals("ERROR")) assertFalse(body.path("message").asText().isBlank(), "Ошибка должна содержать причину");
    }
    void outbound(String path, String token) {
        assertEquals(1, calls.size(), "Ровно один запрос во внешний сервис");
        Call call = calls.get(0);
        assertAll(() -> assertEquals(path, call.path()), () -> assertEquals("POST", call.method()),
            () -> assertEquals("token=" + token, call.body()),
            () -> assertTrue(call.contentType() != null && call.contentType().startsWith("application/x-www-form-urlencoded")),
            () -> assertEquals("application/json", call.accept()));
    }
    @Test @DisplayName("Полный цикл: вход, действие, выход и запрет действия после выхода")
    void lifecycle() throws Exception {
        String t = token(); result(t,"LOGIN","OK"); outbound("/auth",t);
        calls.clear(); result(t,"ACTION","OK"); outbound("/doAction",t);
        calls.clear(); result(t,"LOGOUT","OK"); assertTrue(calls.isEmpty());
        result(t,"ACTION","ERROR"); assertTrue(calls.isEmpty());
    }
    @Test @DisplayName("Без входа действие запрещено и внешний сервис не вызывается")
    void anonymous() throws Exception { result(token(),"ACTION","ERROR"); assertTrue(calls.isEmpty()); }
    @ParameterizedTest(name="Недопустимый токен: [{0}]")
    @NullAndEmptySource @ValueSource(strings={"A", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA ", "АAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidToken(String t) throws Exception { result(t,"LOGIN","ERROR"); assertTrue(calls.isEmpty()); }
    @ParameterizedTest(name="Недопустимое действие: [{0}]") @NullAndEmptySource @ValueSource(strings={"UNKNOWN","login"," LOGIN","ACTION "})
    void invalidAction(String action) throws Exception { result(token(),action,"ERROR"); assertTrue(calls.isEmpty()); }
    @ParameterizedTest(name="Неверный API-ключ: [{0}]") @NullAndEmptySource @ValueSource(strings={"wrong","QAZWSXEDC"})
    void invalidKey(String key) throws Exception {
        var r = send(token(),"LOGIN",key);
        assertTrue(r.statusCode()==401 || r.statusCode()==403, "Доступ должен быть запрещён: " + r.statusCode() + " " + r.body());
        assertTrue(calls.isEmpty());
    }
    @ParameterizedTest(name="Ошибка авторизации внешнего сервиса: HTTP {0}") @ValueSource(ints={400,401,403,500,503})
    void failedLogin(int status) throws Exception {
        String t=token(); authStatus=status; result(t,"LOGIN","ERROR"); outbound("/auth",t);
        calls.clear(); result(t,"ACTION","ERROR"); assertTrue(calls.isEmpty(),"Неудачный вход не создаёт сессию");
    }
    @ParameterizedTest(name="Ошибка действия внешнего сервиса: HTTP {0}") @ValueSource(ints={400,403,500,503})
    void failedAction(int status) throws Exception {
        String t=token(); result(t,"LOGIN","OK"); calls.clear(); actionStatus=status;
        result(t,"ACTION","ERROR"); outbound("/doAction",t);
    }
    @Test @DisplayName("Выход одного пользователя не завершает сессию другого")
    void isolation() throws Exception {
        String a=token(), b=token(); result(a,"LOGIN","OK"); result(b,"LOGIN","OK"); result(a,"LOGOUT","OK");
        calls.clear(); result(b,"ACTION","OK"); outbound("/doAction",b);
        calls.clear(); result(a,"ACTION","ERROR"); assertTrue(calls.isEmpty());
    }
    @Test @DisplayName("Тело ответа внешнего сервиса не влияет на успешный вход")
    void arbitraryBody() throws Exception { upstreamBody="{\"result\":\"ERROR\"}"; result(token(),"LOGIN","OK"); }
    @ParameterizedTest(name="Допустимый токен: {0}") @ValueSource(strings={"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","00000000000000000000000000000000","ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"})
    void validToken(String t) throws Exception { result(t,"LOGIN","OK"); outbound("/auth",t); result(t,"LOGOUT","OK"); }
}

