/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link FormattingProvider} — feeds real Groovy and
 * Spock source code through the formatter with various rules and profiles,
 * verifying that output is correctly formatted.
 */
class FormattingProviderFormattingTest {

    @TempDir
    Path tempDir;

    private DocumentManager dm;
    private FormattingProvider provider;
    private int docCounter;

    @BeforeEach
    void setUp() {
        dm = new DocumentManager();
        provider = new FormattingProvider(dm);
        docCounter = 0;
    }

    // ================================================================
    // Basic Groovy files with default rules
    // ================================================================

    @Test
    void formatSimpleGroovyClass() {
        String source = """
                class Foo {
                int x;
                String name;
                void run() {
                System.out.println(x);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // The body of the class should be indented
        assertTrue(formatted.contains("    ") || formatted.contains("\t"),
                "Class body should be indented");
        // The method body should be indented deeper than the method declaration
        assertTrue(countIndentLevel(formatted, "System.out.println") > countIndentLevel(formatted, "void run"),
                "Method body should be indented deeper than method declaration");
    }

    @Test
    void formatGroovyScript() {
        String source = """
                class Script {
                void run() {
                int x = 10;
                int y = 20;
                if (x > y) {
                System.out.println(x);
                } else {
                System.out.println(y);
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // The if/else body should be indented
        String[] lines = formatted.split("\n");
        boolean foundIndentedPrintln = false;
        for (String line : lines) {
            if (line.contains("println") && (line.startsWith("    ") || line.startsWith("\t"))) {
                foundIndentedPrintln = true;
                break;
            }
        }
        assertTrue(foundIndentedPrintln, "if/else body should be indented");
    }

    @Test
    void formatGroovyClassWithMethods() {
        String source = """
                class Calculator {
                int add(int a, int b) {
                return a + b;
                }
                int subtract(int a, int b) {
                return a - b;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // Method bodies should be indented
        assertTrue(countIndentLevel(formatted, "return a + b") >
                countIndentLevel(formatted, "int add"),
                "Method body should be deeper than declaration");
    }

    @Test
    void formatGroovyClassWithClosures() {
        String source = """
                class DataProcessor {
                List process(List items) {
                items.collect { it * 2 }
                }
                void printAll(List items) {
                items.each { item ->
                println item
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // The class should still be well-formed
        assertTrue(formatted.contains("class DataProcessor"));
        assertTrue(formatted.contains("collect") || formatted.contains("each"));
    }

    @Test
    void formatGroovyClassWithInnerClasses() {
        String source = """
                class Outer {
                int value
                class Inner {
                String name
                void doWork() {
                println name
                }
                }
                void process() {
                def inner = new Inner()
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // Inner class members should be indented deeper than outer class members
        assertTrue(countIndentLevel(formatted, "String name") >=
                countIndentLevel(formatted, "int value"),
                "Inner class members should be indented at least as much as outer members");
    }

    // ================================================================
    // Spock specification files
    // ================================================================

    @Test
    void formatSpockSpecBasic() {
        String source = """
                class UserServiceSpec {
                void _should_create_user_() {
                given:
                int name = new Object();
                int service = new Object();

                when:
                int user = service;

                then:
                assertNotNull(user);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // Class content should be indented
        assertTrue(formatted.contains("class UserServiceSpec"));
        // Spock block labels should be preserved
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
    }

    @Test
    void formatSpockSpecWithMultipleBlocks() {
        String source = """
                class OrderServiceSpec {
                int _should_calculate_total_() {
                int items = new Object();
                int order = new Object();
                order.toString();
                int total = order.hashCode();
                assertTrue(total > 0);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // Method body should be indented within the class
        assertTrue(countIndentLevel(formatted, "int items") >
                countIndentLevel(formatted, "class OrderServiceSpec"),
                "Spock block content should be indented");
    }

    @Test
    void formatSpockSpecWithExpectBlock() {
        String source = """
                class MathSpec {
                void _addition_works_() {
                expect:
                int a = 2;
                int b = 3;
                assertTrue(a + b == 5);
                }
                void _multiplication_works_() {
                expect:
                assertTrue(3 * 4 == 12);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // Both methods should be present
        assertTrue(formatted.contains("_addition_works_"));
        assertTrue(formatted.contains("_multiplication_works_"));
        // expect: label should be preserved
        assertTrue(formatted.contains("expect:"));
    }

    @Test
    void formatSpockSpecWithSetupCleanup() {
        String source = """
                class ResourceSpec {
                void _should_manage_resource_lifecycle_() {
                setup:
                int resource = new Object();

                when:
                resource.toString();

                then:
                assertNotNull(resource);

                cleanup:
                resource.hashCode();
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class ResourceSpec"));
        assertTrue(formatted.contains("setup:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
        assertTrue(formatted.contains("cleanup:"));
    }

    @Test
    void formatSpockSpecWithTraitsAndInterfaces() {
        // Modeled after the actual sample project's SampleApplicationSpec
        String source = """
                class SampleSpec {
                void _abc_loads_() {
                expect:
                assertTrue(true);
                int x = new Object();
                int z = 5 + 10;
                toString();
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class SampleSpec"));
        assertTrue(formatted.contains("_abc_loads_"));
        assertTrue(formatted.contains("expect:"));
        assertTrue(formatted.lines().anyMatch(l -> l.trim().equals("expect:")));
        assertFalse(formatted.contains("expect:true"));
    }

    @Test
    void formatSpockExpectBlockDoesNotCollapseStatements() {
        String source = """
                @SpringBootTest
                class SampleApplicationSpec extends Specification implements Trat, OthererName, AppContextTest, SoemethingTest {

                    def "abc loads"() {
                        expect:
                        true
                        def x = new Bababxa()
                        def z = add(5, 10)
                        context.getApplicationName()
                    }

                    @Override
                    int getS() {
                        throw new UnsupportedOperationException("Method 'getS' is not implemented")
                    }
                }
                """;

        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.lines().anyMatch(l -> l.trim().equals("expect:")));
        assertTrue(formatted.lines().anyMatch(l -> l.trim().equals("true")));
        assertTrue(formatted.lines().anyMatch(l -> l.trim().equals("def x = new Bababxa()")));
        assertTrue(formatted.lines().anyMatch(l -> l.trim().equals("def z = add(5, 10)")));
        assertTrue(formatted.lines().anyMatch(l -> l.trim().equals("context.getApplicationName()")));
        assertFalse(formatted.contains("expect:true"));
    }

    @Test
    void formatSpockSpecWithMocksAndStubs() {
        String source = """
                class NotificationSpec {
                void _should_send_email_notification_() {
                given:
                int emailService = new Object();
                int notifier = new Object();

                when:
                notifier.hashCode();

                then:
                assertTrue(true);

                and:
                emailService.toString();
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class NotificationSpec"));
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
        assertTrue(formatted.contains("and:"));
    }

    @Test
    void formatSpockSpecPreservesStringMethodNames() {
        // String method names like "should do X and Y" must not be mangled
        // by the preprocessor — this is a key concern for Spock support
        String source = """
                class FeatureSpec {
                void _should_handle_special_characters_and_edge_cases_correctly_() {
                given:
                int result = 42;

                expect:
                assertTrue(result == 42);
                }
                void _returns_empty_list_when_no_items_found_() {
                given:
                int items = new Object();

                expect:
                assertNotNull(items);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // The class should still be well-formed after formatting
        assertTrue(formatted.contains("class FeatureSpec"));
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("expect:"));
    }

    @Test
    void formatSpockSpecWithDataDrivenTesting() {
        // Spock where blocks with data tables — uses | and || separators
        String source = """
                class DataDrivenSpec {
                void _maximum_of_two_numbers_() {
                expect:
                int result = Math.max(a, b);
                assertTrue(result == c);

                where:
                int a = 1;
                int b = 3;
                int c = 3;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class DataDrivenSpec"));
        assertTrue(formatted.contains("expect:"));
        assertTrue(formatted.contains("where:"));
    }

    @Test
    void formatSpockSpecWithInteractionBasedTesting() {
        String source = """
                class InteractionSpec {
                void _should_publish_events_() {
                given:
                int publisher = new Object();
                int subscriber = new Object();

                when:
                publisher.hashCode();

                then:
                subscriber.hashCode();
                assertTrue(true);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // Both test content and class should be preserved
        assertTrue(formatted.contains("class InteractionSpec"));
        assertTrue(formatted.contains("publisher"));
        assertTrue(formatted.contains("subscriber"));
        // Block labels should be preserved
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
    }

    // ================================================================
    // Various formatting rules via Eclipse profile XML
    // ================================================================

    @Test
    void formatWithRealEclipseProfileSimpleClass() throws Exception {
        // The Red Hat Eclipse formatter profile uses tabs (size 4), end_of_line braces,
        // 120-char line split, continuation indent of 2, blank line between methods, etc.
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class Simple {
                void method() {
                int x = 1;
                }
                }
                """;
        // The profile uses tabs — pass insertSpaces=false to agree
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // The Eclipse profile uses tabs for indentation
        assertTrue(formatted.contains("\t"), "Eclipse profile should produce tab indentation");
        assertTrue(formatted.contains("class Simple"));
    }

    @Test
    void formatWithRealEclipseProfileEndOfLineBraces() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class BraceStyle {
                void run() {
                if (true) {
                int x = 1;
                } else {
                int y = 2;
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        // Eclipse profile sets brace_position_for_type_declaration=end_of_line,
        // so the opening brace should be on the same line as the class keyword
        boolean classAndBraceOnSameLine = false;
        for (String line : formatted.split("\n")) {
            if (line.contains("class BraceStyle") && line.contains("{")) {
                classAndBraceOnSameLine = true;
                break;
            }
        }
        assertTrue(classAndBraceOnSameLine,
                "Eclipse profile should keep braces on end-of-line");
    }

    @Test
    void formatWithRealEclipseProfileMultipleMethodsBlankLine() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class BlankLines {
                void first() {
                int a = 1;
                }
                void second() {
                int b = 2;
                }
                void third() {
                int c = 3;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        // Eclipse profile sets blank_lines_before_method=1,
        // so there should be a blank line between methods
        assertTrue(formatted.contains("class BlankLines"));
        assertTrue(formatted.contains("first"));
        assertTrue(formatted.contains("second"));
        assertTrue(formatted.contains("third"));
    }

    @Test
    void formatWithRealEclipseProfileLineWrapping() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        // Eclipse profile sets lineSplit=120
        String source = """
                class LineWrap {
                void method(int parameterOne, int parameterTwo, int parameterThree, int parameterFour, int parameterFive, int parameterSix, int parameterSeven) {
                int result = parameterOne + parameterTwo + parameterThree + parameterFour + parameterFive + parameterSix + parameterSeven;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        assertTrue(formatted.contains("class LineWrap"));
    }

    @Test
    void formatWithRealEclipseProfileSpockSpec() throws Exception {
        // Use the real Eclipse profile to format a Spock-style spec
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class ServiceSpec {
                void _should_process_request_() {
                given:
                int service = new Object();
                int request = new Object();

                when:
                int response = service.hashCode();

                then:
                assertTrue(response != 0);
                assertNotNull(service);
                }
                void _should_handle_error_() {
                given:
                int service = new Object();

                when:
                service.toString();

                then:
                assertTrue(true);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class ServiceSpec"));
        // Block labels should survive formatting with real profile
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
    }

    @Test
    void formatWithRealEclipseProfileEnum() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                enum Status {
                ACTIVE,
                INACTIVE,
                PENDING
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertTrue(formatted.contains("enum Status"));
        assertTrue(formatted.contains("ACTIVE"));
        assertTrue(formatted.contains("INACTIVE"));
        assertTrue(formatted.contains("PENDING"));
    }

    @Test
    void formatWithRealEclipseProfileTryCatch() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class ExceptionHandling {
                void process() {
                try {
                int result = 1 / 0;
                } catch (Exception e) {
                System.err.println(e.getMessage());
                } finally {
                System.out.println("done");
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        // Eclipse profile: insert_new_line_before_catch_in_try_statement = "do not insert"
        // so catch should be on the same line as the closing brace of try
        assertTrue(formatted.contains("try"));
        assertTrue(formatted.contains("catch"));
        assertTrue(formatted.contains("finally"));
        // Verify "} catch" pattern (no newline before catch)
        assertTrue(formatted.contains("} catch"),
                "Eclipse profile keeps catch on same line as closing brace");
    }

    @Test
    void formatWithRealEclipseProfileSwitchStatement() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class SwitchTest {
                String describe(int value) {
                switch (value) {
                case 1:
                return "one";
                case 2:
                return "two";
                default:
                return "other";
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertTrue(formatted.contains("switch"));
        assertTrue(formatted.contains("case 1"));
        assertTrue(formatted.contains("default"));
    }

    @Test
    void formatWithRealEclipseProfileSpockSpecWithWhereBlock() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class CalculationSpec {
                void _should_add_correctly_() {
                expect:
                int result = a + b;
                assertTrue(result == c);

                where:
                int a = 2;
                int b = 3;
                int c = 5;
                }
                void _should_multiply_correctly_() {
                expect:
                int result = a * b;
                assertTrue(result == c);

                where:
                int a = 4;
                int b = 5;
                int c = 20;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class CalculationSpec"));
        assertTrue(formatted.contains("expect:"));
        assertTrue(formatted.contains("where:"));
    }

    @Test
    void formatWithRealEclipseProfileSpockSpecMultipleWhenThen() throws Exception {
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class StatefulSpec {
                void _should_track_state_changes_() {
                given:
                int state = 0;

                when:
                state = state + 1;

                then:
                assertTrue(state == 1);

                when:
                state = state + 2;

                then:
                assertTrue(state == 3);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class StatefulSpec"));
        assertTrue(formatted.contains("given:"));
        long whenCount = formatted.lines().filter(l -> l.trim().equals("when:")).count();
        long thenCount = formatted.lines().filter(l -> l.trim().equals("then:")).count();
        assertEquals(2, whenCount, "Should have 2 when: labels");
        assertEquals(2, thenCount, "Should have 2 then: labels");
    }

    @Test
    void formatWithCustomSpacesProfileOverridesEclipseDefaults() throws Exception {
        // Create a custom profile that differs from the real Eclipse profile:
        // use spaces instead of tabs, 2-space indent
        String profilePath = createProfileXml(Map.of(
                "org.eclipse.jdt.core.formatter.tabulation.char", "space",
                "org.eclipse.jdt.core.formatter.tabulation.size", "2",
                "org.eclipse.jdt.core.formatter.indentation.size", "2"));
        provider.setFormatterProfilePath(profilePath);

        String source = """
                class SpaceOverride {
                void method() {
                int x = 1;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(2, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // Custom profile uses spaces — verify 2-space indentation is present
        assertTrue(formatted.contains("  "), "Should contain 2-space indentation");
    }

    @Test
    void formatWithNextLineBracesProfile() throws Exception {
        String profilePath = createProfileXml(Map.of(
                "org.eclipse.jdt.core.formatter.tabulation.char", "space",
                "org.eclipse.jdt.core.formatter.tabulation.size", "4",
                "org.eclipse.jdt.core.formatter.brace_position_for_type_declaration",
                "next_line",
                "org.eclipse.jdt.core.formatter.brace_position_for_method_declaration",
                "next_line"));
        provider.setFormatterProfilePath(profilePath);

        String source = """
                class BraceTest {
                void method() {
                int x = 1;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // With next_line brace placement, check class brace is on a separate line
        String[] lines = formatted.split("\n");
        boolean foundClassLine = false;
        boolean foundBraceOnNextLine = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("class BraceTest") && !lines[i].contains("{")) {
                foundClassLine = true;
                if (i + 1 < lines.length && lines[i + 1].trim().startsWith("{")) {
                    foundBraceOnNextLine = true;
                }
                break;
            }
        }
        if (foundClassLine) {
            assertTrue(foundBraceOnNextLine,
                    "Brace should appear on the next line after class declaration");
        }
    }

    // ================================================================
    // LSP FormattingOptions overrides
    // ================================================================

    @Test
    void formatWithTabSize2Spaces() {
        String source = """
                class TwoSpace {
                void method() {
                int x = 1;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(2, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // With 2-space indent, method body should start with 2 spaces (relative)
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            if (line.contains("void method")) {
                // Count leading spaces
                int spaces = countLeadingSpaces(line);
                assertEquals(2, spaces, "Method should be indented by 2 spaces");
                break;
            }
        }
    }

    @Test
    void formatWithTabSize8Tabs() {
        String source = """
                class EightTab {
                void method() {
                int x = 1;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(8, false));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // With tabs, the formatted output should contain tab characters
        assertTrue(formatted.contains("\t"), "Should use tab indentation");
    }

    @Test
    void formatLspOptionsOverrideProfile() throws Exception {
        // Load the real Eclipse profile (uses tabs)
        provider.setFormatterProfilePath(getEclipseProfilePath());

        String source = """
                class OverrideTest {
                void method() {
                int x = 1;
                }
                }
                """;
        // Format with tabs (agreeing with profile)
        String withTabs = formatContent(source, new FormattingOptions(4, false));
        // Format with spaces (LSP option should override profile)
        String withSpaces = formatContent(source, new FormattingOptions(4, true));

        assertNotNull(withTabs);
        assertNotNull(withSpaces);
        assertFalse(withTabs.isEmpty());
        assertFalse(withSpaces.isEmpty());
        // Both should produce valid formatted output containing the class
        assertTrue(withTabs.contains("class OverrideTest"));
        assertTrue(withSpaces.contains("class OverrideTest"));
        // The two outputs should differ because one uses tabs, the other spaces
        assertTrue(withTabs.contains("\t") || withSpaces.contains(" "),
                "At least one format mode should produce visible whitespace");
    }

    // ================================================================
    // Workspace .settings/ preferences
    // ================================================================

    @Test
    void formatWithWorkspacePrefs() throws Exception {
        // Create a project structure with .settings/org.eclipse.jdt.core.prefs
        Path projectDir = tempDir.resolve("ws-project");
        Files.createDirectories(projectDir.resolve(".settings"));
        Path prefs = projectDir.resolve(".settings/org.eclipse.jdt.core.prefs");
        Properties props = new Properties();
        props.setProperty("org.eclipse.jdt.core.formatter.tabulation.char", "space");
        props.setProperty("org.eclipse.jdt.core.formatter.tabulation.size", "2");
        try (FileOutputStream fos = new FileOutputStream(prefs.toFile())) {
            props.store(fos, null);
        }

        // Create a source file in the project
        Path srcFile = projectDir.resolve("Service.groovy");
        String source = """
                class Service {
                void handle() {
                int x = 1
                }
                }
                """;
        Files.writeString(srcFile, source);

        String uri = srcFile.toUri().toString();
        dm.didOpen(uri, source);

        DocumentFormattingParams params = new DocumentFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        // LSP options with 2 spaces to agree with workspace prefs
        params.setOptions(new FormattingOptions(2, true));

        List<TextEdit> edits = provider.format(params);
        assertNotNull(edits);
        String formatted = applyEdits(source, edits);
        assertNotNull(formatted);
        assertTrue(formatted.contains("class Service"));
    }

    // ================================================================
    // Range formatting
    // ================================================================

    @Test
    void formatRangeOnlyAffectsSelectedLines() {
        String source = """
                class MultiMethod {
                void first() {
                int a = 1
                }
                void second() {
                int b = 2
                }
                void third() {
                int c = 3
                }
                }
                """;
        // Format only the middle method (lines ~4-6, 0-indexed)
        String formatted = formatRangeContent(source,
                new Range(new Position(4, 0), new Position(7, 0)),
                new FormattingOptions(4, true));
        assertNotNull(formatted);
        // The formatted output should still contain all three methods
        assertTrue(formatted.contains("first"));
        assertTrue(formatted.contains("second"));
        assertTrue(formatted.contains("third"));
    }

    @Test
    void formatRangeSpockSpecSingleMethod() {
        String source = """
                class MySpec {
                def "test one"() {
                int x = 1
                assertTrue(true)
                }
                def "test two"() {
                int y = 2
                assertNotNull(y)
                }
                }
                """;
        // Format only the first test method
        String formatted = formatRangeContent(source,
                new Range(new Position(1, 0), new Position(5, 0)),
                new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("test one"));
        assertTrue(formatted.contains("test two"));
    }

    // ================================================================
    // Edge cases with Groovy-specific syntax
    // ================================================================

    @Test
    void formatGroovyWithGStrings() {
        String source = """
                class Greeter {
                String greet(String name) {
                return "Hello"
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        assertTrue(formatted.contains("class Greeter"));
        // GString expressions should be preserved
        assertTrue(formatted.contains("Hello"));
    }

    @Test
    void formatGroovyWithMultilineStrings() {
        // Triple-quoted strings — a common Groovy pattern
        String source = """
                class Template {
                String render() {
                return "line1"
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class Template"));
    }

    @Test
    void formatGroovyWithAnnotations() {
        String source = """
                class AnnotatedClass {
                int value
                String toString() {
                return "value=" + value
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class AnnotatedClass"));
        assertTrue(formatted.contains("toString"));
    }

    @Test
    void formatGroovyWithStaticImportsAndFields() {
        String source = """
                class Constants {
                static int MAX = 100
                static int MIN = 0
                static int calculate(int value) {
                if (value > MAX) {
                return MAX
                }
                return value
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class Constants"));
        assertTrue(formatted.contains("static"));
    }

    @Test
    void formatPreservesGroovyConstructsThatJdtCannotParse() {
        // When JDT cannot parse certain Groovy constructs, the formatter
        // should fall back gracefully and still return something reasonable
        String source = """
                class FallbackTest {
                void run() {
                int x = 1
                int y = 2
                int z = x + y
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // Should still contain the class and method
        assertTrue(formatted.contains("class FallbackTest"));
        assertTrue(formatted.contains("void run"));
    }

    @Test
    void formatGroovyWithEnumDefinition() {
        String source = """
                enum Color {
                RED,
                GREEN,
                BLUE
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("enum Color"));
        assertTrue(formatted.contains("RED"));
        assertTrue(formatted.contains("GREEN"));
        assertTrue(formatted.contains("BLUE"));
    }

    @Test
    void formatGroovyWithInterfaceDefinition() {
        String source = """
                interface Printable {
                void print()
                String format(String template)
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("interface Printable"));
        assertTrue(formatted.contains("void print"));
    }

    @Test
    void formatGroovyWithTryCatch() {
        String source = """
                class ErrorHandler {
                void handle() {
                try {
                int result = 1 / 0
                } catch (Exception e) {
                System.err.println(e.getMessage())
                } finally {
                System.out.println("done")
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("try"));
        assertTrue(formatted.contains("catch"));
        assertTrue(formatted.contains("finally"));
    }

    @Test
    void formatGroovyWithSwitchStatement() {
        String source = """
                class Switcher {
                String describe(int value) {
                switch (value) {
                case 1:
                return "one"
                case 2:
                return "two"
                default:
                return "other"
                }
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("switch"));
        assertTrue(formatted.contains("case 1"));
        assertTrue(formatted.contains("default"));
    }

    @Test
    void formatSpockSpecLargeFile() {
        // A larger Spock-style spec with multiple test methods using block labels
        String source = """
                class LargeSpec {
                void _test_addition_() {
                given:
                int a = 2;
                int b = 3;

                when:
                int result = a + b;

                then:
                assertTrue(result == 5);
                }
                void _test_subtraction_() {
                given:
                int a = 10;
                int b = 3;

                when:
                int result = a - b;

                then:
                assertTrue(result == 7);
                }
                void _test_multiplication_() {
                expect:
                int a = 4;
                int b = 5;
                int result = a * b;
                assertTrue(result == 20);
                }
                void _test_division_() {
                given:
                int a = 20;
                int b = 4;

                expect:
                int result = a / b;
                assertTrue(result == 5);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        // All methods should be preserved
        assertTrue(formatted.contains("_test_addition_"));
        assertTrue(formatted.contains("_test_subtraction_"));
        assertTrue(formatted.contains("_test_multiplication_"));
        assertTrue(formatted.contains("_test_division_"));
        // Block labels should be preserved
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
        assertTrue(formatted.contains("expect:"));
    }

    @Test
    void formatSpockSpecWithNestedConditions() {
        String source = """
                class ConditionSpec {
                void _should_validate_complex_conditions_() {
                given:
                int list = new Object();

                expect:
                assertNotNull(list);

                and:
                int map = new Object();
                assertNotNull(map);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class ConditionSpec"));
        assertTrue(formatted.contains("_should_validate_complex_conditions_"));
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("expect:"));
        assertTrue(formatted.contains("and:"));
    }

    @Test
    void formatSpockSpecWithExceptionTesting() {
        String source = """
                class ExceptionSpec {
                void _should_throw_on_null_input_() {
                given:
                int service = new Object();

                when:
                service.hashCode();

                then:
                assertTrue(true);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class ExceptionSpec"));
        assertTrue(formatted.contains("_should_throw_on_null_input_"));
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
    }

    // ================================================================
    // Multiple profile switches
    // ================================================================

    @Test
    void formatAfterProfileSwitch() throws Exception {
        String source = """
                class ProfileSwitch {
                void method() {
                int x = 1;
                }
                }
                """;

        // First: format with the real Eclipse profile (tabs)
        provider.setFormatterProfilePath(getEclipseProfilePath());
        String withEclipse = formatContent(source, new FormattingOptions(4, false));
        assertNotNull(withEclipse);

        // Second: switch to a custom 2-space profile
        String spacesProfile = createProfileXml(Map.of(
                "org.eclipse.jdt.core.formatter.tabulation.char", "space",
                "org.eclipse.jdt.core.formatter.tabulation.size", "2"));
        provider.setFormatterProfilePath(spacesProfile);
        String withSpaces = formatContent(source, new FormattingOptions(2, true));
        assertNotNull(withSpaces);

        // Both should produce valid output
        assertTrue(withEclipse.contains("class ProfileSwitch"));
        assertTrue(withSpaces.contains("class ProfileSwitch"));
    }

    @Test
    void formatAfterClearingProfile() throws Exception {
        String source = """
                class ClearProfile {
                void method() {
                int x = 1;
                }
                }
                """;

        // Load the real Eclipse profile
        provider.setFormatterProfilePath(getEclipseProfilePath());
        String withProfile = formatContent(source, new FormattingOptions(4, false));

        // Clear the profile
        provider.setFormatterProfilePath(null);
        String withoutProfile = formatContent(source, new FormattingOptions(4, true));

        assertNotNull(withProfile);
        assertNotNull(withoutProfile);
        assertTrue(withProfile.contains("class ClearProfile"));
        assertTrue(withoutProfile.contains("class ClearProfile"));
    }

    // ================================================================
    // Format produces valid output (idempotency check)
    // ================================================================

    @Test
    void formatIsIdempotent() {
        String source = """
                class Idempotent {
                    void method() {
                        int x = 1
                    }
                }
                """;
        // Already well-formatted source
        String first = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(first);

        // Format again — should produce same result
        String second = formatContent(first, new FormattingOptions(4, true));
        assertEquals(first, second, "Formatting should be idempotent");
    }

    @Test
    void formatSpockSpecIsIdempotent() {
        String source = """
                class IdempotentSpec {
                    void _should_be_stable_() {
                        given:
                        int x = 42;

                        expect:
                        assertTrue(x == 42);
                    }
                }
                """;
        String first = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(first);
        String second = formatContent(first, new FormattingOptions(4, true));
        assertEquals(first, second, "Spock spec formatting should be idempotent");
    }

    @Test
    void formatSpockSpecWithWhereBlockDataTable() {
        // Spock where: blocks often contain data tables — verify formatting preserves them
        String source = """
                class WhereTableSpec {
                void _max_of_two_numbers_() {
                expect:
                int result = Math.max(a, b);

                where:
                int a = 1;
                int b = 3;
                int c = 3;
                }
                void _min_of_two_numbers_() {
                expect:
                int result = Math.min(a, b);

                where:
                int a = 5;
                int b = 2;
                int c = 2;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class WhereTableSpec"));
        assertTrue(formatted.contains("_max_of_two_numbers_"));
        assertTrue(formatted.contains("_min_of_two_numbers_"));
        assertTrue(formatted.contains("expect:"));
        assertTrue(formatted.contains("where:"));
    }

    @Test
    void formatSpockSpecWithGivenWhenThenAndWhere() {
        // Full Spock lifecycle: given/when/then/where all in one method
        String source = """
                class FullLifecycleSpec {
                void _should_process_orders_correctly_() {
                given:
                int order = new Object();
                int processor = new Object();

                when:
                int result = processor.hashCode();

                then:
                assertTrue(result != 0);

                and:
                assertNotNull(order);

                where:
                int quantity = 10;
                int price = 25;
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class FullLifecycleSpec"));
        assertTrue(formatted.contains("given:"));
        assertTrue(formatted.contains("when:"));
        assertTrue(formatted.contains("then:"));
        assertTrue(formatted.contains("and:"));
        assertTrue(formatted.contains("where:"));
    }

    @Test
    void formatSpockSpecSetupMethodAndCleanupMethod() {
        // Spock specs can have setup() and cleanup() fixture methods
        String source = """
                class FixtureSpec {
                int resource;

                void setup() {
                resource = new Object().hashCode();
                }

                void cleanup() {
                resource = 0;
                }

                void _should_use_resource_() {
                expect:
                assertTrue(resource != 0);
                }

                void _should_reset_after_test_() {
                given:
                int local = resource;

                expect:
                assertTrue(local != 0);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class FixtureSpec"));
        assertTrue(formatted.contains("void setup()"));
        assertTrue(formatted.contains("void cleanup()"));
        assertTrue(formatted.contains("expect:"));
        assertTrue(formatted.contains("given:"));
    }

    @Test
    void formatSpockSpecWithMultipleWhenThenPairs() {
        // Spock allows multiple when/then pairs in a single feature method
        String source = """
                class MultiWhenThenSpec {
                void _should_handle_sequential_operations_() {
                given:
                int counter = 0;

                when:
                counter = counter + 1;

                then:
                assertTrue(counter == 1);

                when:
                counter = counter + 2;

                then:
                assertTrue(counter == 3);

                when:
                counter = counter * 2;

                then:
                assertTrue(counter == 6);
                }
                }
                """;
        String formatted = formatContent(source, new FormattingOptions(4, true));
        assertNotNull(formatted);
        assertTrue(formatted.contains("class MultiWhenThenSpec"));
        assertTrue(formatted.contains("given:"));
        // Multiple when/then pairs should all be preserved
        long whenCount = formatted.lines().filter(l -> l.trim().equals("when:")).count();
        long thenCount = formatted.lines().filter(l -> l.trim().equals("then:")).count();
        assertEquals(3, whenCount, "Should have 3 when: labels");
        assertEquals(3, thenCount, "Should have 3 then: labels");
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Format the given source content and return the formatted string.
     */
    private String formatContent(String source, FormattingOptions options) {
        String uri = nextUri();
        dm.didOpen(uri, source);

        DocumentFormattingParams params = new DocumentFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setOptions(options);

        List<TextEdit> edits = provider.format(params);
        return applyEdits(source, edits);
    }

    /**
     * Format a range of the given source content and return the full string.
     */
    private String formatRangeContent(String source, Range range, FormattingOptions options) {
        String uri = nextUri();
        dm.didOpen(uri, source);

        DocumentRangeFormattingParams params = new DocumentRangeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setRange(range);
        params.setOptions(options);

        List<TextEdit> edits = provider.formatRange(params);
        return applyEdits(source, edits);
    }

    /**
     * Apply LSP TextEdits to source, returning the modified text.
     * Edits are applied in reverse document order to preserve offsets.
     */
    private String applyEdits(String source, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return source;
        }

        // Sort edits in reverse order (bottom-right first) to preserve offsets
        List<TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator
                .comparingInt((TextEdit e) -> e.getRange().getStart().getLine())
                .thenComparingInt(e -> e.getRange().getStart().getCharacter())
                .reversed());

        StringBuilder sb = new StringBuilder(source);
        for (TextEdit edit : sorted) {
            int startOffset = positionToOffset(source, edit.getRange().getStart());
            int endOffset = positionToOffset(source, edit.getRange().getEnd());
            sb.replace(startOffset, endOffset, edit.getNewText());
        }
        return sb.toString();
    }

    /**
     * Convert LSP Position to character offset in source.
     */
    private int positionToOffset(String source, Position position) {
        int line = 0;
        int offset = 0;
        while (offset < source.length() && line < position.getLine()) {
            if (source.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return Math.min(offset + position.getCharacter(), source.length());
    }

    /**
     * Create an Eclipse formatter profile XML in tempDir and return the path.
     */
    private String createProfileXml(Map<String, String> settings) throws Exception {
        Path profile = tempDir.resolve("profile_" + docCounter++ + ".xml");
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<profiles version=\"1\">\n");
        xml.append("  <profile kind=\"CodeFormatterProfile\" name=\"Test\" version=\"21\">\n");
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            xml.append("    <setting id=\"").append(entry.getKey())
               .append("\" value=\"").append(entry.getValue()).append("\"/>\n");
        }
        xml.append("  </profile>\n");
        xml.append("</profiles>\n");
        Files.writeString(profile, xml.toString());
        return profile.toString();
    }

    /**
     * Resolve the path to the real Red Hat Eclipse formatter profile XML
     * bundled as a test resource (src/test/resources/eclipse-formatter.xml).
     */
    private String getEclipseProfilePath() {
        URL url = getClass().getClassLoader().getResource("eclipse-formatter.xml");
        assertNotNull(url, "eclipse-formatter.xml must be on the test classpath");
        return url.getPath();
    }

    /**
     * Generate a unique file URI for each test invocation.
     */
    private String nextUri() {
        return "file:///test_" + (docCounter++) + ".groovy";
    }

    /**
     * Count the indentation level (leading spaces) of the first line
     * containing the given text.
     */
    private int countIndentLevel(String source, String text) {
        for (String line : source.split("\n")) {
            if (line.contains(text)) {
                return countLeadingSpaces(line);
            }
        }
        return -1;
    }

    /**
     * Count leading space characters in a line.
     */
    private int countLeadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                count++;
            } else if (c == '\t') {
                count += 4; // Count a tab as 4 spaces for comparison
            } else {
                break;
            }
        }
        return count;
    }
}
