package org.qainsights.jmeter.ai.param;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.gui.ParameterDialogControls;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

class ParameterRegressionTest {
    @TempDir Path directory;

    @BeforeAll
    static void init() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @Test
    void extractKeepsItsSubstringReplacementSemantics() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setPath("/12345/abc1234x");
        assertEquals(
                1,
                org.qainsights.jmeter.ai.extract.ExtractPlacement.replace(
                        List.of(new JMeterTreeNode(http, null)), "1234", "id"));
        assertEquals("/${id}5/abc${id}x", http.getPath());
    }

    @Test
    void jsonEditsPreserveEveryOtherCharacter() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setPostBodyRaw(true);
        String original =
                "{\r\n"
                        + "  \"username\" : \"a\\\"bcd\",\n"
                        + "  \"id\": 12345,\n"
                        + "  \"other\" : [ 12345, \"unchanged\" ]\n"
                        + "}\n";
        http.addNonEncodedArgument("", original, "");
        JMeterTreeNode scope = new JMeterTreeNode(http, null);
        List<ParameterScanner.Candidate> chosen =
                ParameterScanner.scan(scope).stream()
                        .filter(c -> !c.value.equals("unchanged"))
                        .collect(java.util.stream.Collectors.toList());
        assertEquals(2, ParameterReplacement.replace(scope, chosen, true));
        assertEquals(
                original.replace("12345", "${id}"),
                http.getArguments().getArgument(0).getValue());
    }

    @Test
    void boundaryCountsAgreeWithActualReplacementsAcrossProperties() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.addArgument("id", "1234");
        http.setPath("/1234/1234?no=12345&embedded=abc1234x&underscore=_1234&dynamic=${1234}");
        JMeterTreeNode scope = new JMeterTreeNode(http, null);
        ParameterScanner.Candidate candidate =
                ParameterScanner.scan(scope).stream()
                        .filter(c -> c.value.equals("1234"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(3, candidate.occurrences);
        assertEquals(3, ParameterReplacement.replace(scope, List.of(candidate), true));
        assertEquals("/${id}/${id}", http.getPath());
        assertEquals("${id}", http.getArguments().getArgument(0).getValue());
        assertEquals("12345", http.getArguments().getArgument(1).getValue());
        assertEquals("abc1234x", http.getArguments().getArgument(2).getValue());
        assertEquals("_1234", http.getArguments().getArgument(3).getValue());
        assertEquals("${1234}", http.getArguments().getArgument(4).getValue());
    }

    @Test
    void noMatchesAndMalformedJsonAreUnchanged() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setPostBodyRaw(true);
        http.addNonEncodedArgument("", "{ malformed 1234", "");
        JMeterTreeNode scope = new JMeterTreeNode(http, null);
        assertEquals(
                0,
                ParameterReplacement.replace(
                        scope, List.of(new ParameterScanner.Candidate("id", "1234", "id")), true));
        assertEquals("{ malformed 1234", http.getArguments().getArgument(0).getValue());
        assertEquals(0, ParameterReplacement.replace(scope, List.of(), true));
    }

    @Test
    void failedPlanRestoresExistingCsvBytes() throws Exception {
        Path file = directory.resolve("existing.csv");
        byte[] before = new byte[] {1, 2, 13, 10, 3};
        Files.write(file, before);
        assertThrows(
                IOException.class,
                () ->
                        ParameterTransaction.apply(
                                file,
                                () -> {
                                    Files.writeString(file, "changed");
                                    return 0;
                                },
                                () -> {
                                    throw new IOException("plan failed");
                                }));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void failedPlanDeletesNewCsvAndSuccessfulPlanKeepsIt() throws Exception {
        Path file = directory.resolve("new.csv");
        assertThrows(
                IOException.class,
                () ->
                        ParameterTransaction.apply(
                                file,
                                () -> {
                                    Files.writeString(file, "new");
                                    return 0;
                                },
                                () -> {
                                    throw new IOException("plan failed");
                                }));
        assertFalse(Files.exists(file));
        assertEquals(
                7,
                ParameterTransaction.apply(
                        file,
                        () -> {
                            Files.writeString(file, "success");
                            return 0;
                        },
                        () -> 7));
        assertEquals("success", Files.readString(file));
    }

    @Test
    void writeFailureNeverInvokesPlan() {
        Path file = directory.resolve("new.csv");
        assertThrows(
                IOException.class,
                () ->
                        ParameterTransaction.apply(
                                file,
                                () -> {
                                    throw new IOException("write failed");
                                },
                                () -> {
                                    fail("Plan must not run after a write failure");
                                    return 0;
                                }));
    }

    @Test
    void planFailureRestoresValuesAndRemovesInsertedConfig() throws Exception {
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.addArgument("username", "alice");
        JMeterTreeNode scope = new JMeterTreeNode(http, null);
        group.add(scope);
        when(model.addComponent(any(), eq(group)))
                .thenAnswer(
                        invocation -> {
                            JMeterTreeNode added =
                                    new JMeterTreeNode(invocation.getArgument(0), null);
                            group.add(added);
                            return added;
                        });
        doThrow(new IllegalStateException("insertion failed"))
                .when(model)
                .insertNodeInto(any(), eq(group), eq(0));
        assertThrows(
                IllegalStateException.class,
                () ->
                        ParameterPlacement.apply(
                                model,
                                scope,
                                null,
                                "data.csv",
                                ",",
                                true,
                                false,
                                "shareMode.all",
                                ParameterScanner.scan(scope)));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertEquals(1, group.getChildCount());
    }

    @Test
    void themedControlsAndEmptyStateHaveNaturalSizes() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    JTable table =
                            new JTable(
                                    new DefaultTableModel(
                                            new String[] {
                                                "Use", "Field", "Value", "Variable", "Occurrences"
                                            },
                                            0));
                    ParameterizeThisDialog.styleTable(table);
                    assertTrue(
                            table.getRowHeight()
                                    >= table.getFontMetrics(table.getFont()).getHeight() + 14);
                    assertEquals(ThemeColors.selectedBackground(), table.getSelectionBackground());
                    assertEquals(
                            ThemeColors.subtleSurface(), table.getTableHeader().getBackground());
                    assertFalse(table.getShowVerticalLines());
                    JPanel empty = ParameterizeThisDialog.emptyState();
                    assertEquals(
                            "No hard-coded values found",
                            ((JLabel) empty.getComponent(0)).getText());
                    JTextField input = new JTextField("data/users.csv", 20);
                    assertTrue(
                            ParameterDialogControls.field(input).getPreferredSize().height
                                    >= input.getPreferredSize().height);
                    JButton button = ParameterDialogControls.button("Apply variables", true);
                    assertTrue(
                            button.getPreferredSize().width
                                    > button.getFontMetrics(button.getFont())
                                            .stringWidth(button.getText()));
                    assertFalse(button.isPreferredSizeSet());
                });
    }
}
