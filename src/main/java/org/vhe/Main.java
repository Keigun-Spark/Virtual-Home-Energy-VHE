package org.vhe;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for the Virtual Home Energy plugin.
 * Handles environment variables loading, authenticates the client, and initializes the Energy Manager.
 */
public class Main {
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String host = dotenv.get("HCU_HOST", System.getenv("HCU_HOST"));
        String pluginId = dotenv.get("PLUGIN_ID", System.getenv("PLUGIN_ID"));
        if (pluginId == null || pluginId.isEmpty()) {
            pluginId = "de.spark.keigun.plugin.virtualhomeenergy";
        }

        String authToken = dotenv.get("AUTH_TOKEN", System.getenv("AUTH_TOKEN"));
        String authTokenFile = dotenv.get("AUTH_TOKEN_FILE", System.getenv("AUTH_TOKEN_FILE"));

        if (authTokenFile != null && !authTokenFile.isEmpty()) {
            File f = new File(authTokenFile);
            if (f.exists()) {
                try {
                    authToken = new String(Files.readAllBytes(Paths.get(authTokenFile))).trim();
                } catch (Exception e) {
                    System.err.print("Failed to read token file: " + e.getMessage() + "\n");
                }
            }
        }

        if (host == null || host.isEmpty() || authToken == null || authToken.isEmpty()) {
            System.err.print("Missing required environment variables: HCU_HOST, AUTH_TOKEN (or AUTH_TOKEN_FILE)\n");
            System.exit(1);
        }

        System.out.print("Starting plugin with HCU_HOST=" + host + ", PLUGIN_ID=" + pluginId + "\n");

        HmipClient client = new HmipClient(host, pluginId, authToken);
        EnergyManager energyManager = new EnergyManager(client);

        try {
            CompletableFuture<Void> connectFuture = client.connect();
            connectFuture.get(); // wait for connection

            client.send("PLUGIN_STATE_RESPONSE", java.util.Map.of(
                "pluginReadinessStatus", "READY"
            ));

            energyManager.initialize();

            System.out.print("Virtual Home Energy Plugin is running.\n");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.print("Shutting down plugin...\n");
                energyManager.shutdown();
                client.shutdown();
            }));

        } catch (Exception e) {
            System.err.print("Failed to start plugin: " + e.getMessage() + "\n");
            System.exit(1);
        }
    }
}
