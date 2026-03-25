package samaritan.fabric.client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AuthClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String serverUrl;
    private final HttpClient httpClient;

    private String currentToken;
    private String currentUsername;
    private long tokenExpiresAt;

    public AuthClient(String serverUrl) {
        this.serverUrl = serverUrl.replaceAll("/$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public boolean login(String username, String password) {
        try {
            String jsonBody = String.format(
                    "{\"username\":\"%s\",\"password\":\"%s\"}",
                    escapeJson(username),
                    escapeJson(password)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                this.currentToken = json.get("token").getAsString();
                this.currentUsername = username;
                this.tokenExpiresAt = extractExpiryEpochMs(this.currentToken);
                return true;
            }

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isTokenValid() {
        if (currentToken == null) {
            return false;
        }
        if (System.currentTimeMillis() > tokenExpiresAt) {
            currentToken = null;
            return false;
        }
        return true;
    }

    public String getToken() {
        return isTokenValid() ? currentToken : null;
    }

    public String getUsername() {
        return currentUsername;
    }

    public long getTokenTimeRemaining() {
        if (currentToken == null) {
            return 0;
        }
        return Math.max(0, tokenExpiresAt - System.currentTimeMillis());
    }

    public long getTokenExpiresAtEpochMs() {
        return tokenExpiresAt;
    }

    public boolean restoreSession(String username, String token, long tokenExpiresAtEpochMs) {
        if (username == null || username.isBlank() || token == null || token.isBlank()) {
            return false;
        }

        this.currentUsername = username;
        this.currentToken = token;
        this.tokenExpiresAt = tokenExpiresAtEpochMs > 0 ? tokenExpiresAtEpochMs : extractExpiryEpochMs(token);
        return isTokenValid();
    }

    public void logout() {
        currentToken = null;
        currentUsername = null;
        tokenExpiresAt = 0;
    }

    public ChangePasswordResult changePassword(String currentPassword, String newPassword) {
        String token = getToken();
        if (token == null) {
            return new ChangePasswordResult(false, "No valid token");
        }

        try {
            String jsonBody = String.format(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}",
                    escapeJson(currentPassword),
                    escapeJson(newPassword)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/auth/change-password"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ChangePasswordResult(true, "Password changed");
            }

            String error = "Password change failed (" + response.statusCode() + ")";
            try {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("error")) {
                    error = json.get("error").getAsString();
                }
            } catch (Exception ignored) {
            }
            return new ChangePasswordResult(false, error);
        } catch (Exception e) {
            return new ChangePasswordResult(false, "Password change request failed");
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private long extractExpiryEpochMs(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return System.currentTimeMillis() + 43_200_000L;
            }
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            if (payload.has("exp")) {
                long expSeconds = payload.get("exp").getAsLong();
                return expSeconds * 1000L;
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis() + 43_200_000L;
    }

    public record ChangePasswordResult(boolean success, String message) {
    }
}
