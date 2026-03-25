package samaritan.fabric.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

public class PersistentStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_LENGTH = 12;

    private final Path stateFile = FabricLoader.getInstance().getConfigDir().resolve("samaritan-client.json");
    private final Path keyFile = FabricLoader.getInstance().getConfigDir().resolve("samaritan-client.key");

    public synchronized ClientState load() {
        try {
            if (!Files.exists(stateFile)) {
                return null;
            }

            String stored = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
            if (stored.isEmpty()) {
                return null;
            }

            EncryptedState encryptedState = GSON.fromJson(stored, EncryptedState.class);
            if (encryptedState != null
                    && "v1".equals(encryptedState.format)
                    && encryptedState.nonce != null
                    && encryptedState.data != null) {
                String plaintextJson = decrypt(encryptedState);
                if (plaintextJson == null) {
                    return null;
                }
                return GSON.fromJson(plaintextJson, ClientState.class);
            }

            ClientState plaintextState = GSON.fromJson(stored, ClientState.class);
            if (plaintextState != null) {
                save(plaintextState);
            }
            return plaintextState;
        } catch (JsonSyntaxException e) {
            System.err.println("[Samaritan] Invalid state file format: " + stateFile);
            return null;
        } catch (Exception e) {
            System.err.println("[Samaritan] Failed to load state file: " + e.getMessage());
            return null;
        }
    }

    public synchronized void save(ClientState state) {
        try {
            Files.createDirectories(stateFile.getParent());

            String plaintextJson = GSON.toJson(state);
            EncryptedState encryptedState = encrypt(plaintextJson);
            if (encryptedState == null) {
                System.err.println("[Samaritan] Failed to encrypt state, skipping save.");
                return;
            }
            Files.writeString(stateFile, GSON.toJson(encryptedState), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Samaritan] Failed to save state file: " + e.getMessage());
        }
    }

    private EncryptedState encrypt(String plaintextJson) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintextJson.getBytes(StandardCharsets.UTF_8));

            return new EncryptedState(
                    "v1",
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(encrypted)
            );
        } catch (Exception e) {
            System.err.println("[Samaritan] Encryption failed: " + e.getMessage());
            return null;
        }
    }

    private String decrypt(EncryptedState encryptedState) {
        try {
            byte[] nonce = Base64.getDecoder().decode(encryptedState.nonce);
            byte[] ciphertext = Base64.getDecoder().decode(encryptedState.data);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Samaritan] Decryption failed: " + e.getMessage());
            return null;
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        Files.createDirectories(keyFile.getParent());
        if (Files.exists(keyFile)) {
            byte[] encoded = Files.readAllBytes(keyFile);
            return new SecretKeySpec(encoded, "AES");
        }

        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();

        Files.write(keyFile, key.getEncoded());
        return key;
    }

    public record ClientState(
            String serverHost,
            int serverPort,
            boolean useTls,
            String username,
            String token,
            long tokenExpiresAtEpochMs,
            int espColorRgb,
            int maxRenderDistanceBlocks,
            int minArrowDistanceBlocks,
            boolean horizontalDistanceOnly,
            boolean onlyHighway
    ) {
    }

    private record EncryptedState(
            String format,
            String nonce,
            String data
    ) {
    }
}
