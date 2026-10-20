package maja;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MajaClient {
    private static final String DEFAULT_TOKEN_URL =
            "https://account.makaramas.com/auth/realms/maja/protocol/openid-connect/token";
    private static final String DEFAULT_BASE_URL = "https://billing.maja.id";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {};

    private final HttpClient http;
    private final ObjectMapper json;
    private final String tokenUrl;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String clientId;
    private final String clientSecret;
    private String accessToken;
    private Instant tokenAt;

    public MajaClient(Config config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
    }

    MajaClient(Config config, HttpClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
        this.tokenUrl = config.tokenUrl;
        this.baseUrl = config.baseUrl.replaceAll("/+$", "");
        this.username = config.username;
        this.password = config.password;
        this.clientId = config.clientId;
        this.clientSecret = config.clientSecret;
    }

    public static MajaClient fromEnvironment() {
        return new MajaClient(Config.fromEnvironment());
    }

    public boolean configured() {
        return present(username) && present(password) && present(clientId) && present(clientSecret);
    }

    public Map<String, Object> register(RegisterRequest request) {
        Map<String, Object> response = postJson("/api/v2/register", request);
        if (!"00".equals(response.get("code"))) {
            throw new BadRequestException(String.format("MAJA register ditolak (%s): %s",
                    valueOr(response.get("code"), "-"), valueOr(response.get("message"), "")));
        }
        return objectData(response, "MAJA register tanpa data", false);
    }

    public Map<String, Object> inquiry(String va, String invoiceNumber) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("va", va);
        request.put("invoiceNumber", invoiceNumber);
        Map<String, Object> response = postJson("/api/v2/inquiry", request);
        return objectData(response, String.format("MAJA inquiry gagal (%s): %s",
                valueOr(response.get("code"), "-"), valueOr(response.get("message"), "")), true);
    }

    public void cancel(String va, String invoiceNumber) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("va", va);
        request.put("invoiceNumber", invoiceNumber);
        Map<String, Object> response = postJson("/api/v2/cancel", request);
        if ("00".equals(response.get("code"))) {
            return;
        }
        String message = valueOr(response.get("message"), "").toLowerCase();
        List<String> idempotentMessages = List.of("sudah dibatalkan", "invalid", "tidak ditemukan");
        if (idempotentMessages.stream().anyMatch(message::contains)) {
            return;
        }
        throw new BadRequestException(String.format("MAJA cancel ditolak (%s): %s",
                valueOr(response.get("code"), "-"), message));
    }

    private synchronized String accessToken() {
        if (accessToken != null && tokenAt != null && Duration.between(tokenAt, Instant.now()).compareTo(TOKEN_TTL) < 0) {
            return accessToken;
        }
        if (!configured()) {
            throw new ServiceUnavailableException(
                    "MAJA belum dikonfigurasi (isi MAJA_USERNAME/MAJA_PASSWORD/MAJA_CLIENT_ID/MAJA_CLIENT_SECRET)");
        }

        String form = "grant_type=password"
                + "&username=" + encode(username)
                + "&password=" + encode(password)
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = send(request, "MAJA token endpoint");
        if (!isSuccess(response.statusCode())) {
            throw new ServiceUnavailableException(String.format("MAJA token gagal (HTTP %d)", response.statusCode()));
        }
        Map<String, Object> body = decodeJson(response.body(), "token");
        Object token = body.get("access_token");
        if (!(token instanceof String) || ((String) token).isEmpty()) {
            throw new ServiceUnavailableException("Respons MAJA token tanpa access_token");
        }
        accessToken = (String) token;
        tokenAt = Instant.now();
        return accessToken;
    }

    private Map<String, Object> postJson(String path, Object body) {
        String requestBody;
        try {
            requestBody = json.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new ServiceUnavailableException("Tidak dapat membuat request MAJA", error);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = send(request, "MAJA");
        if (!isSuccess(response.statusCode())) {
            String message = response.body().isEmpty() ? "HTTP error" : response.body();
            throw new ServiceUnavailableException(String.format(
                    "MAJA %s gagal (HTTP %d): %s", path, response.statusCode(), message));
        }
        return decodeJson(response.body(), path);
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Tidak dapat menghubungi " + operation, error);
        } catch (IOException | IllegalArgumentException error) {
            throw new ServiceUnavailableException("Tidak dapat menghubungi " + operation, error);
        }
    }

    private Map<String, Object> decodeJson(String body, String operation) {
        try {
            Map<String, Object> decoded = json.readValue(body, JSON_OBJECT);
            if (decoded == null) {
                throw new ServiceUnavailableException("Respons MAJA " + operation + " bukan objek JSON");
            }
            return decoded;
        } catch (IOException error) {
            throw new ServiceUnavailableException("Respons MAJA " + operation + " bukan JSON", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectData(Map<String, Object> response, String errorMessage, boolean badRequest) {
        Object data = response.get("data");
        if (data instanceof Map<?, ?>) {
            return (Map<String, Object>) data;
        }
        if (badRequest) {
            throw new BadRequestException(errorMessage);
        }
        throw new ServiceUnavailableException(errorMessage);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    private static String valueOr(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    public static final class Config {
        private final String tokenUrl;
        private final String baseUrl;
        private final String username;
        private final String password;
        private final String clientId;
        private final String clientSecret;

        public Config(String tokenUrl, String baseUrl, String username, String password,
                      String clientId, String clientSecret) {
            this.tokenUrl = tokenUrl == null || tokenUrl.isEmpty() ? DEFAULT_TOKEN_URL : tokenUrl;
            this.baseUrl = baseUrl == null || baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl;
            this.username = username;
            this.password = password;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public static Config fromEnvironment() {
            return new Config(System.getenv("MAJA_TOKEN_URL"), System.getenv("MAJA_BASE_URL"),
                    System.getenv("MAJA_USERNAME"), System.getenv("MAJA_PASSWORD"),
                    System.getenv("MAJA_CLIENT_ID"), System.getenv("MAJA_CLIENT_SECRET"));
        }
    }

    public static class MajaException extends RuntimeException {
        public MajaException(String message) {
            super(message);
        }

        public MajaException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ServiceUnavailableException extends MajaException {
        public ServiceUnavailableException(String message) {
            super(message);
        }

        public ServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class BadRequestException extends MajaException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class RegisterRequest {
        public String name;
        public String email;
        public String phone;
        public String number;
        public double amount;
        public String activeDate;
        public String date;
        public String inactiveDate;
        public List<InvoiceItem> items;
        public String attribute1;
        public String attribute2;
        public String attribute3;
        public String paymentMethod;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class InvoiceItem {
        public String description;
        public double unitPrice;
        public int qty;
        public double amount;
    }
}