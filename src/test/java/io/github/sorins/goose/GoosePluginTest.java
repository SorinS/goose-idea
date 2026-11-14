package io.github.sorins.goose;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.sorins.goose.run.GooseConsoleFilter;
import io.github.sorins.goose.settings.GooseSettings;
import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;
import org.jetbrains.plugins.textmate.bundles.TextMateBundleReader;
import org.jetbrains.plugins.textmate.bundles.TextMateGrammar;
import org.jetbrains.plugins.textmate.bundles.TextMatePreferences;
import org.jetbrains.plugins.textmate.language.preferences.TextMateSnippet;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The plugin in a test IDE: the TextMate bundle, file type and icon, and the settings. */
public class GoosePluginTest extends BasePlatformTestCase {
    public void testBundleShipsWithThePlugin() {
        List<TextMateBundleProvider.PluginBundle> bundles = provider().getBundles();
        assertEquals(1, bundles.size());
        assertTrue(Files.isRegularFile(bundles.get(0).getPath().resolve("syntaxes/goose.tmLanguage.json")));
    }

    /**
     * The bundle reads as the TextMate plugin reads it: the grammar for
     * .goose, the language configuration (brackets, auto-closing pairs,
     * comments) and the snippets. (The TextMate plugin does not register
     * plugin bundles in unit-test mode, so this reads the bundle directly.)
     */
    public void testBundleReadsAsTextMateBundle() {
        TextMateBundleProvider.PluginBundle bundle = provider().getBundles().get(0);
        TextMateBundleReader reader = TextMateService.getInstance().readBundle(bundle.getPath());
        assertNotNull(reader);
        List<TextMateGrammar> grammars = list(reader.readGrammars().iterator());
        assertEquals(1, grammars.size());
        assertTrue(grammars.get(0).getFileNameMatchers().toString(),
                grammars.get(0).getFileNameMatchers().toString().contains("goose"));
        List<TextMatePreferences> preferences = list(reader.readPreferences().iterator());
        assertFalse(preferences.isEmpty());
        boolean braces = false, closing = false, comments = false;
        for (TextMatePreferences p : preferences) {
            braces |= !p.getHighlightingPairs().isEmpty();
            closing |= !p.getSmartTypingPairs().isEmpty();
            comments |= p.getVariables().toString().contains("//");
        }
        assertTrue("brackets", braces);
        assertTrue("auto-closing pairs", closing);
        assertTrue("line comment", comments);
        List<TextMateSnippet> snippets = list(reader.readSnippets().iterator());
        assertTrue(snippets.stream().anyMatch(sn -> "main".equals(sn.getKey())));
    }

    public void testGooseFilesGetTheGooseIcon() {
        var file = myFixture.configureByText("main.goose", "fn main() {\n}\n").getVirtualFile();
        assertTrue(GooseFiles.isGooseFile(file));
        assertSame(GooseFiles.ICON, new GooseIconProvider().getIcon(file, 0, getProject()));
    }

    private static GooseBundleProvider provider() {
        return ExtensionPointName.<TextMateBundleProvider>create("com.intellij.textmate.bundleProvider").getExtensionList().stream()
                .filter(GooseBundleProvider.class::isInstance).map(GooseBundleProvider.class::cast)
                .findFirst().orElseThrow();
    }

    private static <T> List<T> list(Iterator<T> it) {
        List<T> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }

    public void testSettingsDefaults() {
        GooseSettings settings = GooseSettings.getInstance(getProject());
        assertTrue(settings.getState().useLanguageServer);
        assertNull(settings.stdlib(getProject()));
        assertEquals(List.of(), settings.entryFiles(getProject()));
    }

    public void testConsoleFilterPattern() {
        assertTrue(GooseConsoleFilter.LINE.matcher("src/main.goose:12: error: unknown name x").matches());
        assertTrue(GooseConsoleFilter.LINE.matcher("/a/b.goose:3: warning: unused").matches());
        assertFalse(GooseConsoleFilter.LINE.matcher("hello world").matches());
    }
}
