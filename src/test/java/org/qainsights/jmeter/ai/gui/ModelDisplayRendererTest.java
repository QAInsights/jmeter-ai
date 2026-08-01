package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelDisplayRendererTest {

    @Test
    void parsesPrefixedModels() {
        assertArrayEquals(
            new String[] { "gpt-4o", "OpenAI" },
            ModelDisplayRenderer.parse("openai:gpt-4o")
        );
        assertArrayEquals(
            new String[] { "llama3.1", "Ollama" },
            ModelDisplayRenderer.parse("ollama:llama3.1")
        );
        assertArrayEquals(
            new String[] { "deepseek-chat", "DeepSeek" },
            ModelDisplayRenderer.parse("deepseek:deepseek-chat")
        );
        assertArrayEquals(
            new String[] { "gemini-2.5-flash", "Google" },
            ModelDisplayRenderer.parse("google:gemini-2.5-flash")
        );
        assertArrayEquals(
            new String[] { "grok-2", "Grok" },
            ModelDisplayRenderer.parse("grok:grok-2")
        );
        assertArrayEquals(
            new String[] { "llama-3", "Meta" },
            ModelDisplayRenderer.parse("meta:llama-3")
        );
        assertArrayEquals(
            new String[] { "claude-3-7", "AWS Bedrock" },
            ModelDisplayRenderer.parse("bedrock:claude-3-7")
        );
    }

    @Test
    void unprefixedModelsDefaultToAnthropic() {
        assertArrayEquals(
            new String[] { "claude-sonnet-4", "Anthropic" },
            ModelDisplayRenderer.parse("claude-sonnet-4")
        );
    }

    @Test
    void nullAndEmptyModelsShowLoadingPlaceholder() {
        assertArrayEquals(
            new String[] { "Loading models...", "" },
            ModelDisplayRenderer.parse(null)
        );
        assertArrayEquals(
            new String[] { "Loading models...", "" },
            ModelDisplayRenderer.parse("")
        );
    }

    @Test
    void rendererDisplaysFriendlyLabel() {
        ModelDisplayRenderer renderer = new ModelDisplayRenderer();
        javax.swing.JLabel label = (javax.swing.JLabel) renderer.getListCellRendererComponent(
            new javax.swing.JList<>(),
            "openai:gpt-4o",
            0,
            false,
            false
        );
        assertEquals("gpt-4o  ·  OpenAI", label.getText());
    }

    @Test
    void rendererKeepsRawIdForLoadingState() {
        ModelDisplayRenderer renderer = new ModelDisplayRenderer();
        javax.swing.JLabel label = (javax.swing.JLabel) renderer.getListCellRendererComponent(
            new javax.swing.JList<>(),
            null,
            0,
            false,
            false
        );
        assertEquals("Loading models...", label.getText());
    }
}
