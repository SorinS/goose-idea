package io.github.sorins.goose;

import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.sorins.goose.lsp.GooseLspClientDescriptor;
import io.github.sorins.goose.lsp.GooseLspIntegrationProvider;
import io.github.sorins.goose.settings.GooseSettings;
import org.eclipse.lsp4j.ServerCapabilities;

import java.util.Collection;

/**
 * The platform's LSP client starts goose lsp through the plugin's descriptor
 * and initializes it. Skipped when no compiler is found.
 */
public class GooseLspClientTest extends BasePlatformTestCase {
    public void testPlatformClientStartsTheServer() {
        if (GooseSettings.getInstance(getProject()).compiler(getProject()) == null) {
            System.err.println("GooseLspClientTest skipped: no goose compiler found");
            return;
        }
        LspClientManager manager = LspClientManager.getInstance(getProject());
        manager.ensureClientStarted(GooseLspIntegrationProvider.class, new GooseLspClientDescriptor(getProject()));
        try {
            LspClient client = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                Collection<LspClient> clients = manager.getClients(GooseLspIntegrationProvider.class);
                client = clients.isEmpty() ? null : clients.iterator().next();
                if (client != null && client.getInitializeResult() != null) break;
                if (client != null && client.getState().name().startsWith("Shutdown")) break;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            assertNotNull("no client", client);
            assertNotNull("server not initialized; state " + client.getState(), client.getInitializeResult());
            assertEquals("Running", client.getState().name());
            ServerCapabilities capabilities = client.getInitializeResult().getCapabilities();
            assertNotNull(capabilities.getSemanticTokensProvider());
            assertNotNull(capabilities.getHoverProvider());
            assertNotNull(capabilities.getDefinitionProvider());
            assertNotNull(capabilities.getCodeLensProvider());
        } finally {
            manager.stopClients(GooseLspIntegrationProvider.class);
        }
    }
}
