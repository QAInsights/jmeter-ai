package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.swing.JLabel;
import javax.swing.JList;

import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelPickerRenderer}: metadata line construction
 * (context window, pricing, capability tags), compact number formatting, and
 * the two-line cell rendering incl. the pinned star. Uses the real vendored
 * models.dev catalog from the test classpath.
 */
class ModelPickerRendererTest {

    @TempDir
    Path tempDir;

    private ModelSelectorPreferences prefs() {
        return ModelSelectorPreferences.load(tempDir.resolve("preferences.json"));
    }

    private static FontMetrics fontMetrics() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            return graphics.getFontMetrics(new Font(Font.DIALOG, Font.BOLD, 12));
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void ellipsizeLeavesShortTextUnchanged() {
        String text = "short model";
        FontMetrics metrics = fontMetrics();

        assertEquals(text, ModelPickerRenderer.ellipsize(text, metrics, metrics.stringWidth(text)));
    }

    @Test
    void ellipsizeFitsLongTextAndReturnsNonNullForTinyWidths() {
        String text = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free";
        FontMetrics metrics = fontMetrics();
        int maxWidth = metrics.stringWidth(text) / 2;

        String shortened = ModelPickerRenderer.ellipsize(text, metrics, maxWidth);
        assertTrue(shortened.endsWith("…"));
        assertTrue(metrics.stringWidth(shortened) <= maxWidth);
        assertNotNull(ModelPickerRenderer.ellipsize(text, metrics, 1));
    }

    @Test
    void rendererSetsFullIdTooltipOnlyWhenTitleIsTruncated() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        JList<String> list = new JList<>();
        list.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        list.setSize(220, 60);
        String longModel = "openrouter:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free";

        JLabel truncated = (JLabel) renderer.getListCellRendererComponent(
                list, longModel, 0, false, false);
        assertEquals(longModel, truncated.getToolTipText());
        assertTrue(truncated.getText().contains("…"));
        assertTrue(truncated.getText().contains("<nobr>"));

        list.setSize(500, 60);
        JLabel shortModel = (JLabel) renderer.getListCellRendererComponent(
                list, "openai:gpt-5.1", 1, false, false);
        assertNull(shortModel.getToolTipText());
    }

    @Test
    void metadataForFullEntry() {
        String metadata = ModelPickerRenderer.metadataFor(
                "openai:gpt-5.1", ModelCapabilityCatalog.getInstance());
        assertEquals("400k ctx · $1.25/$10 per Mtok · vision · thinking", metadata);
    }

    @Test
    void metadataForMetadataOnlyEntry() {
        // No reasoning/vision flags - only context + cost survive the trim
        String metadata = ModelPickerRenderer.metadataFor(
                "google:gemini-2.5-flash-preview-tts", ModelCapabilityCatalog.getInstance());
        assertEquals("8k ctx · $0.5/$10 per Mtok", metadata);
    }

    @Test
    void metadataForLocalAndUnknownModels() {
        ModelCapabilityCatalog catalog = ModelCapabilityCatalog.getInstance();
        assertEquals("local model", ModelPickerRenderer.metadataFor("ollama:qwen3:8b", catalog));
        assertEquals("", ModelPickerRenderer.metadataFor("openai:gpt-9-turbo", catalog));
        assertEquals("", ModelPickerRenderer.metadataFor(null, catalog));
        assertEquals("", ModelPickerRenderer.metadataFor("", catalog));
    }

    @Test
    void secondLineForUsesProviderFallbacksWithoutCatalogMetadata() {
        ModelCapabilityCatalog catalog = ModelCapabilityCatalog.getInstance();

        assertEquals("Uses your ChatGPT plan through the Codex CLI",
                ModelPickerRenderer.secondLineFor("codex:unlisted-model", catalog));
        assertEquals("Uses your Claude plan through Claude Code",
                ModelPickerRenderer.secondLineFor("claude-code:unlisted-model", catalog));
        assertEquals("Runs through the OpenCode CLI",
                ModelPickerRenderer.secondLineFor("opencode:unlisted/model", catalog));
        assertEquals("Routed through OpenRouter",
                ModelPickerRenderer.secondLineFor("openrouter:unlisted/model", catalog));
        assertEquals("Anthropic model",
                ModelPickerRenderer.secondLineFor("unlisted-claude-model", catalog));
        assertEquals("local model",
                ModelPickerRenderer.secondLineFor("ollama:qwen3:8b", catalog));
    }

    @Test
    void secondLineForUsesCatalogMetadataWhenAvailable() {
        assertEquals("400k ctx · $1.25/$10 per Mtok · vision · thinking",
                ModelPickerRenderer.secondLineFor(
                        "openai:gpt-5.1", ModelCapabilityCatalog.getInstance()));
    }

    @Test
    void formatContextCompacts() {
        assertEquals("1M", ModelPickerRenderer.formatContext(1_048_576));
        assertEquals("400k", ModelPickerRenderer.formatContext(400_000));
        assertEquals("131k", ModelPickerRenderer.formatContext(131_072));
        assertEquals("16k", ModelPickerRenderer.formatContext(16_385));
        assertEquals("8k", ModelPickerRenderer.formatContext(8_192));
        assertEquals("500", ModelPickerRenderer.formatContext(500));
    }

    @Test
    void formatCostStripsTrailingZeros() {
        assertEquals("10", ModelPickerRenderer.formatCost(10.0));
        assertEquals("1.25", ModelPickerRenderer.formatCost(1.25));
        assertEquals("0.075", ModelPickerRenderer.formatCost(0.075));
        assertEquals("0.3", ModelPickerRenderer.formatCost(0.3));
    }

    @Test
    void escapeHtmlNeutralizesSpecials() {
        assertEquals("a&lt;b&gt;&amp;c", ModelPickerRenderer.escapeHtml("a<b>&c"));
    }

    private static String rgb(java.awt.Color c) {
        return "rgb(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + ")";
    }

    @Test
    void everyRowShowsAStarColoredByPinState() {
        ModelSelectorPreferences prefs = prefs();
        prefs.togglePinned("openai:gpt-5.1");
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs, ModelCapabilityCatalog.getInstance());

        Component pinned = renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-5.1", 0, false, false);
        String pinnedHtml = ((JLabel) pinned).getText();
        assertTrue(pinnedHtml.contains("★"));
        assertTrue(pinnedHtml.contains(rgb(org.qainsights.jmeter.ai.gui.theme.ThemeColors.accent())));

        Component unpinned = renderer.getListCellRendererComponent(
                new JList<>(), "google:gemini-2.5-pro", 1, false, false);
        String unpinnedHtml = ((JLabel) unpinned).getText();
        assertTrue(unpinnedHtml.contains("★"), "unpinned rows must still show the pin affordance");
        assertTrue(unpinnedHtml.contains(
                rgb(org.qainsights.jmeter.ai.gui.theme.ThemeColors.secondaryText())));
    }

    @Test
    void selectedCellUsesExplicitReadableForeground() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-5.1", 0, true, false);
        String foreground = rgb(
                org.qainsights.jmeter.ai.gui.theme.ThemeColors.foreground());

        assertTrue(label.getText().contains(foreground));
        assertTrue(label.getText().contains("<b>gpt-5.1</b>"));
        assertEquals(org.qainsights.jmeter.ai.gui.theme.ThemeColors.selectedBackground(),
                label.getBackground());
    }

    @Test
    void cellRendersTwoLinesWithDisplayNameAndMetadata() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        Component c = renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-5.1", 0, false, false);
        String html = ((JLabel) c).getText();
        assertTrue(html.contains("<b>gpt-5.1</b> · OpenAI"));
        assertTrue(html.contains("400k ctx"));
        assertTrue(html.contains("<br>"));
    }

    @Test
    void cellWithoutMetadataStillRendersSecondLine() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        Component c = renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-9-turbo", 0, false, false);
        String html = ((JLabel) c).getText();
        assertTrue(html.contains("<b>gpt-9-turbo</b> · OpenAI"));
        assertTrue(html.contains("<br>"));
        assertTrue(html.contains("OpenAI model"));
    }
}
