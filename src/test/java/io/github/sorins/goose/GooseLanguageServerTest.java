package io.github.sorins.goose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sorins.goose.lsp.GooseLspClientDescriptor;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.Gson;

import static org.junit.Assert.*;

/**
 * Starts the compiler's language server with the command line and the
 * initialization options the plugin uses, and checks what it serves.
 * Skipped when no compiler is found (-PgooseCompiler=/path/to/goose names one).
 */
public class GooseLanguageServerTest {
    private Path compiler;
    private Process process;

    @Before
    public void findCompiler() {
        String configured = System.getProperty("goose.compiler", "");
        compiler = GooseCompiler.find(configured, null);
        Assume.assumeNotNull(compiler);
    }

    @After
    public void stop() throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    @Test
    public void probe() throws Exception {
        Process p = new ProcessBuilder(compiler.toString(), "lsp", "--probe").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue());
        assertTrue(out, Pattern.compile("^goose lsp \\S+").matcher(out).find());
    }

    @Test
    public void initializeAdvertisesTheFeaturesThePluginUses() throws Exception {
        Path dir = Files.createTempDirectory("goose-lsp-test");
        Path main = dir.resolve("main.goose");
        Files.writeString(main, "fn main() {\n}\n");
        List<String> command = new ArrayList<>();
        command.add(compiler.toString());
        command.addAll(GooseCompiler.lspArguments(null));
        process = new ProcessBuilder(command).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        OutputStream in = process.getOutputStream();
        InputStream out = process.getInputStream();

        Map<String, Object> options = GooseLspClientDescriptor.initializationOptions(List.of(main), null);
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"processId\":null,"
                + "\"rootUri\":\"" + dir.toUri() + "\",\"capabilities\":{},\"initializationOptions\":"
                + new Gson().toJson(options) + "}}";
        send(in, init);
        JsonObject response = readResponse(out, 1);
        JsonObject capabilities = response.getAsJsonObject("result").getAsJsonObject("capabilities");
        for (String feature : List.of("textDocumentSync", "hoverProvider", "definitionProvider", "referencesProvider",
                "renameProvider", "documentSymbolProvider", "completionProvider", "semanticTokensProvider",
                "documentFormattingProvider", "signatureHelpProvider", "inlayHintProvider", "foldingRangeProvider",
                "codeLensProvider")) {
            assertTrue("server does not advertise " + feature + ": " + capabilities, capabilities.has(feature));
        }

        send(in, "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}");
        send(in, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\"}");
        readResponse(out, 2);
        send(in, "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
        assertTrue("server did not exit", process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    private static void send(OutputStream in, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        in.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        in.write(body);
        in.flush();
    }

    /** Reads messages until the response with this id, skipping notifications and requests. */
    private static JsonObject readResponse(InputStream out, int id) throws IOException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonObject message = JsonParser.parseString(read(out)).getAsJsonObject();
            if (message.has("id") && !message.has("method") && message.get("id").getAsInt() == id) {
                assertFalse("error: " + message, message.has("error"));
                return message;
            }
        }
        throw new AssertionError("no response " + id);
    }

    private static String read(InputStream out) throws IOException {
        int length = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int c = out.read();
            if (c < 0) throw new IOException("server closed its output");
            if (c == '\n') {
                String header = line.toString(StandardCharsets.US_ASCII).trim();
                line.reset();
                if (header.isEmpty()) break;
                if (header.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
            } else {
                line.write(c);
            }
        }
        if (length < 0) throw new IOException("no Content-Length");
        byte[] body = out.readNBytes(length);
        return new String(body, StandardCharsets.UTF_8);
    }
}
