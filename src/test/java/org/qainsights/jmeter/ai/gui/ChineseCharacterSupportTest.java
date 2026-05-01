package org.qainsights.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Verifies that Chinese (and broader CJK) characters are handled correctly
 * throughout the plugin.  These tests cover three layers of the fix introduced
 * for the "jmeter agent not supports chinese characters on Windows 11" issue:
 *
 * <ol>
 *   <li><b>CommandDispatcher</b> – Chinese messages must be routed to the AI
 *       service unchanged; they must never be parsed as {@code @commands} and
 *       the exact Unicode text must appear in the conversation history.</li>
 *   <li><b>MessageProcessor</b> – Chinese text inserted into a
 *       {@link DefaultStyledDocument} must be preserved byte-for-byte, and the
 *       font family applied to normal text runs must be {@value Font#DIALOG}
 *       (Java's composite font that activates the platform CJK fallback
 *       chain).</li>
 *   <li><b>Font layer</b> – The {@code Dialog} composite font must report that
 *       it can display representative Simplified Chinese, Traditional Chinese,
 *       Japanese, and Korean glyphs, and a font produced via
 *       {@link Font#deriveFont(float)} must preserve that capability.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ChineseCharacterSupportTest {

    // =========================================================================
    // Shared Mockito infrastructure
    // =========================================================================

    /** Static mock that controls AiConfig.isStreamingEnabled() per test. */
    private static MockedStatic<AiConfig> aiConfigMock;

    @Mock
    private CommandCallback cb;

    private CommandDispatcher dispatcher;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = Mockito.mockStatic(AiConfig.class);
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMock != null) {
            aiConfigMock.close();
        }
    }

    @BeforeEach
    void setUp() {
        dispatcher = new CommandDispatcher(cb);
    }

    // =========================================================================
    // Section 1: CommandDispatcher – getCommand() with CJK input
    // =========================================================================

    @Test
    void getCommand_pureChineseMessage_returnsEmptyString() {
        // Pure Chinese messages must never be treated as @commands.
        assertEquals(
            "",
            CommandDispatcher.getCommand("你好"),
            "Chinese-only message must not be parsed as a command"
        );
    }

    @Test
    void getCommand_chineseSentence_returnsEmptyString() {
        assertEquals(
            "",
            CommandDispatcher.getCommand("请帮我优化这个测试计划"),
            "Chinese sentence must not be parsed as a command"
        );
    }

    @ParameterizedTest(name = "message=''{0}''")
    @ValueSource(
        strings = {
            "你好", // Simplified Chinese
            "繁體中文", // Traditional Chinese
            "こんにちは", // Japanese (Hiragana)
            "テスト", // Japanese (Katakana)
            "안녕하세요", // Korean
            "中文输入测试", // Simplified – longer phrase
        }
    )
    void getCommand_cjkMessages_neverExtractCommand(String msg) {
        assertEquals(
            "",
            CommandDispatcher.getCommand(msg),
            "CJK message must not be treated as a @command: " + msg
        );
    }

    @Test
    void getCommand_atCommandFollowedByChinese_extractsCommandPart() {
        // @commands with Chinese arguments must still be recognised as commands.
        assertEquals(
            "@lint",
            CommandDispatcher.getCommand("@lint 中文参数说明"),
            "@lint command must be extracted even when followed by Chinese args"
        );
    }

    @Test
    void getCommand_atCommandFollowedByJapanese_extractsCommandPart() {
        assertEquals(
            "@optimize",
            CommandDispatcher.getCommand("@optimize テスト")
        );
    }

    // =========================================================================
    // Section 2: CommandDispatcher – dispatch() with Chinese messages
    // =========================================================================

    @Test
    void dispatch_chineseMessage_appendsUserMessageWithExactText() {
        String msg = "请帮我创建一个HTTP请求";
        aiConfigMock.when(AiConfig::isStreamingEnabled).thenReturn(true);
        stubStreamingComplete(msg);

        dispatcher.dispatch(msg);

        // The chat area must show the exact Chinese text prefixed with "You: "
        verify(cb).appendUserMessage("You: " + msg);
    }

    @Test
    void dispatch_chineseMessage_addsOriginalTextToConversationHistory() {
        String msg = "你好，请帮我添加一个线程组";
        aiConfigMock.when(AiConfig::isStreamingEnabled).thenReturn(true);
        stubStreamingComplete(msg);

        dispatcher.dispatch(msg);

        // The raw Chinese string (not a mangled copy) must end up in history.
        verify(cb).addToConversationHistory(msg);
    }

    @Test
    void dispatch_chineseMessage_clearsInputFieldAfterSend() {
        String msg = "中文测试消息";
        aiConfigMock.when(AiConfig::isStreamingEnabled).thenReturn(true);
        stubStreamingComplete(msg);

        dispatcher.dispatch(msg);

        verify(cb).clearMessageField();
    }

    @Test
    void dispatch_chineseMessage_showsLoadingIndicator() {
        String msg = "测试加载动画";
        aiConfigMock.when(AiConfig::isStreamingEnabled).thenReturn(true);
        stubStreamingComplete(msg);

        dispatcher.dispatch(msg);

        verify(cb).appendLoadingIndicator();
    }

    @Test
    void dispatch_chineseStreamingResponse_aggregatesTokensAndAddsToHistory() {
        aiConfigMock.when(AiConfig::isStreamingEnabled).thenReturn(true);
        String msg = "你好";
        String token1 = "我可以";
        String token2 = "帮助你";
        String fullReply = token1 + token2;

        doAnswer(
            (Answer<Runnable>) inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(1);
                Runnable onComplete = inv.getArgument(2);
                tokenConsumer.accept(token1);
                tokenConsumer.accept(token2);
                onComplete.run();
                return () -> {};
            }
        )
            .when(cb)
            .getAiStreamResponse(eq(msg), any(), any(), any());

        dispatcher.dispatch(msg);

        // Every streamed token must reach the chat area
        verify(cb).appendStreamToken(token1);
        verify(cb).appendStreamToken(token2);
        // The fully assembled Chinese response must be stored in history
        verify(cb).addToConversationHistory(fullReply);
        verify(cb).onStreamComplete(fullReply);
    }

    @Test
    void dispatch_mixedChineseAndEnglish_routesAsRegularMessage() {
        String msg = "add HTTP请求 for 登录";
        aiConfigMock.when(AiConfig::isStreamingEnabled).thenReturn(true);
        stubStreamingComplete(msg);

        dispatcher.dispatch(msg);

        // Should reach the streaming path (not the element-creation short-circuit)
        verify(cb).showStopButton();
        verify(cb).appendUserMessage("You: " + msg);
    }

    // =========================================================================
    // Section 3: MessageProcessor – CJK text preservation in StyledDocument
    // =========================================================================

    @Test
    void appendMessage_chineseText_preservedInDocumentUnchanged()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();
        String chinese = "你好世界"; // Hello World

        processor.appendMessage(doc, chinese, null, false);

        String content = doc.getText(0, doc.getLength());
        assertTrue(
            content.contains(chinese),
            "appendMessage must store Chinese text in the document unchanged"
        );
    }

    @Test
    void processMarkdown_chineseBodyText_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();
        String chinese = "这是一个关于JMeter的问题";

        processor.processMarkdownMessage(doc, chinese);

        assertTrue(
            doc.getText(0, doc.getLength()).contains(chinese),
            "processMarkdownMessage must preserve Chinese body text"
        );
    }

    @Test
    void processMarkdown_chineseH1Heading_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(doc, "# 你好世界\n详细说明如下");

        String content = doc.getText(0, doc.getLength());
        assertTrue(
            content.contains("你好世界"),
            "Heading Chinese text must be present"
        );
        assertTrue(
            content.contains("详细说明如下"),
            "Body Chinese text must be present"
        );
    }

    @Test
    void processMarkdown_chineseH2Heading_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(
            doc,
            "## 性能测试\n使用JMeter进行测试"
        );

        String content = doc.getText(0, doc.getLength());
        assertTrue(
            content.contains("性能测试"),
            "H2 Chinese heading must be present in the document"
        );
    }

    @Test
    void processMarkdown_chineseH3Heading_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(doc, "### 线程组配置\n配置说明");

        String content = doc.getText(0, doc.getLength());
        assertTrue(
            content.contains("线程组配置"),
            "H3 Chinese heading must be present"
        );
    }

    @Test
    void processMarkdown_mixedChineseAndEnglish_bothPreservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(doc, "Hello 你好 World 世界");

        String content = doc.getText(0, doc.getLength());
        assertTrue(content.contains("你好"), "Chinese part must be present");
        assertTrue(content.contains("世界"), "Chinese part must be present");
        assertTrue(content.contains("Hello"), "English part must be present");
        assertTrue(content.contains("World"), "English part must be present");
    }

    @Test
    void processMarkdown_boldChineseText_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(doc, "前言 **重要内容** 结论");

        String content = doc.getText(0, doc.getLength());
        assertTrue(
            content.contains("重要内容"),
            "Bold Chinese text must be present"
        );
        assertTrue(
            content.contains("前言"),
            "Preceding Chinese text must be present"
        );
        assertTrue(
            content.contains("结论"),
            "Trailing Chinese text must be present"
        );
    }

    @Test
    void processMarkdown_italicChineseText_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(doc, "正文 *强调文字* 尾部");

        String content = doc.getText(0, doc.getLength());
        assertTrue(
            content.contains("强调文字"),
            "Italic Chinese text must be present"
        );
    }

    @Test
    void processMarkdown_traditionalChineseText_preservedInDocument()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();
        String traditional = "繁體中文測試內容";

        processor.processMarkdownMessage(doc, traditional);

        assertTrue(
            doc.getText(0, doc.getLength()).contains(traditional),
            "Traditional Chinese characters must be preserved"
        );
    }

    @Test
    void processMarkdown_japaneseText_preservedInDocument() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();
        String japanese = "JMeterのパフォーマンステスト";

        processor.processMarkdownMessage(doc, japanese);

        assertTrue(
            doc.getText(0, doc.getLength()).contains(japanese),
            "Japanese characters must be preserved in the document"
        );
    }

    @Test
    void processMarkdown_koreanText_preservedInDocument() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();
        String korean = "성능 테스트 설정";

        processor.processMarkdownMessage(doc, korean);

        assertTrue(
            doc.getText(0, doc.getLength()).contains(korean),
            "Korean characters must be preserved in the document"
        );
    }

    /**
     * Verifies that the font family applied to plain-text runs is
     * {@link Font#DIALOG} ("Dialog") – Java's guaranteed composite font.
     *
     * <p>This guards against regression to {@code "SansSerif"} (a physical font
     * on many Windows configurations) which does not activate the JVM's CJK
     * glyph-fallback chain and causes Chinese characters to appear as empty
     * boxes.</p>
     */
    @Test
    void processMarkdown_normalTextRun_usesDiaglogFontFamilyForCjkFallback()
        throws Exception {
        MessageProcessor processor = new MessageProcessor();
        DefaultStyledDocument doc = new DefaultStyledDocument();

        processor.processMarkdownMessage(doc, "test");

        // Inspect the character element at offset 0 – it should carry the
        // font family explicitly set by processBasicMarkdown().
        Element charElement = doc.getCharacterElement(0);
        String fontFamily = StyleConstants.getFontFamily(
            charElement.getAttributes()
        );

        assertEquals(
            Font.DIALOG,
            fontFamily,
            "Normal text runs must use Font.DIALOG ('Dialog') so the JVM activates " +
                "its CJK composite-font fallback chain on Windows"
        );
    }

    // =========================================================================
    // Section 4: Font layer – Dialog composite font can display CJK glyphs
    //
    // Font.canDisplay(char) queries the physical font chain underlying the
    // logical font name.  On Windows 11 the "Dialog" composite font delegates
    // to the system fonts (Segoe UI + Microsoft YaHei / Gothic etc.) which
    // cover the BMP Chinese/Japanese/Korean code points used below.
    // =========================================================================

    @Test
    void dialogFont_canDisplaySimplifiedChineseCharacters() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
        for (char c : "你好世界中文测试".toCharArray()) {
            assertTrue(
                font.canDisplay(c),
                "Dialog font must display Simplified Chinese char '" + c + "'"
            );
        }
    }

    @Test
    void dialogFont_canDisplayTraditionalChineseCharacters() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
        for (char c : "繁體中文測試內容".toCharArray()) {
            assertTrue(
                font.canDisplay(c),
                "Dialog font must display Traditional Chinese char '" + c + "'"
            );
        }
    }

    @Test
    void dialogFont_canDisplayJapaneseHiragana() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
        for (char c : "こんにちは".toCharArray()) {
            assertTrue(
                font.canDisplay(c),
                "Dialog font must display Japanese Hiragana char '" + c + "'"
            );
        }
    }

    @Test
    void dialogFont_canDisplayJapaneseKatakana() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
        for (char c : "テスト".toCharArray()) {
            assertTrue(
                font.canDisplay(c),
                "Dialog font must display Japanese Katakana char '" + c + "'"
            );
        }
    }

    @Test
    void dialogFont_canDisplayKoreanCharacters() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
        for (char c : "한국어테스트".toCharArray()) {
            assertTrue(
                font.canDisplay(c),
                "Dialog font must display Korean char '" + c + "'"
            );
        }
    }

    /**
     * Verifies that {@link Font#deriveFont(float)} – used in AiChatPanel to
     * scale the UI font – preserves the ability to display CJK glyphs.
     *
     * <p>The original code used {@code new Font(defaultFont.getFamily(), ...)}
     * which can produce a <em>physical</em> (non-composite) font that bypasses
     * the fallback chain.  The fix switches to {@code deriveFont()} which
     * preserves the composite nature of the base font.</p>
     */
    @Test
    void derivedDialogFont_preservesCjkGlyphDisplay() {
        Font base = new Font(Font.DIALOG, Font.PLAIN, 12);
        Font derived = base.deriveFont(16f); // matches the +2 scaling in AiChatPanel

        for (char c : "你好世界".toCharArray()) {
            assertTrue(
                derived.canDisplay(c),
                "Font derived from Dialog via deriveFont() must still display " +
                    "Chinese char '" +
                    c +
                    "'"
            );
        }
    }

    @Test
    void derivedDialogFont_withBoldStyle_preservesCjkGlyphDisplay() {
        Font base = new Font(Font.DIALOG, Font.BOLD, 12);
        Font derived = base.deriveFont(Font.BOLD, 14f);

        for (char c : "测试".toCharArray()) {
            assertTrue(
                derived.canDisplay(c),
                "Bold-derived Dialog font must still display Chinese char '" +
                    c +
                    "'"
            );
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Stubs {@code cb.getAiStreamResponse} so it immediately calls
     * {@code onComplete} (simulates a zero-token successful stream).
     * Only matches calls where the first argument equals {@code expectedMsg}.
     */
    @SuppressWarnings("unchecked")
    private void stubStreamingComplete(String expectedMsg) {
        doAnswer(
            (Answer<Runnable>) inv -> {
                Runnable onComplete = inv.getArgument(2);
                onComplete.run();
                return () -> {};
            }
        )
            .when(cb)
            .getAiStreamResponse(
                eq(expectedMsg),
                any(Consumer.class),
                any(Runnable.class),
                any(Consumer.class)
            );
    }
}
