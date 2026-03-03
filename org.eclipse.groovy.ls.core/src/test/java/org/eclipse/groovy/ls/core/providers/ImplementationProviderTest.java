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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ImplementationProvider}.
 */
class ImplementationProviderTest {

    private ImplementationProvider provider;
    private DocumentManager documentManager;

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new ImplementationProvider(documentManager);
    }

    // ---- getImplementations: missing document ----

    @Test
    void returnsEmptyForMissingDocument() {
        ImplementationParams params = new ImplementationParams(
                new TextDocumentIdentifier("file:///Missing.groovy"),
                new Position(0, 0));
        List<Location> locations = provider.getImplementations(params);
        assertNotNull(locations);
        assertTrue(locations.isEmpty());
    }

    // ---- getImplementations: no working copy ----

    @Test
    void returnsEmptyWhenNoWorkingCopy() {
        String uri = "file:///NoWC.groovy";
        documentManager.didOpen(uri, """
                interface Greeter {
                    void greet()
                }
                class Hello implements Greeter {
                    void greet() { println 'hi' }
                }
                """);

        ImplementationParams params = new ImplementationParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 11)); // "Greeter"
        List<Location> locations = provider.getImplementations(params);
        // No JDT working copy → empty
        assertNotNull(locations);
        assertTrue(locations.isEmpty());

        documentManager.didClose(uri);
    }

    // ---- offsetToPosition / positionToOffset helpers ----

    @Test
    void offsetToPositionFirstLine() {
        Position pos = provider.offsetToPosition("hello\nworld", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() {
        Position pos = provider.offsetToPosition("hello\nworld", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionClamped() {
        Position pos = provider.offsetToPosition("hi", 99);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void positionToOffsetFirstLine() {
        int offset = provider.positionToOffset("hello\nworld", new Position(0, 3));
        assertEquals(3, offset);
    }

    @Test
    void positionToOffsetSecondLine() {
        int offset = provider.positionToOffset("hello\nworld", new Position(1, 2));
        assertEquals(8, offset);
    }

    @Test
    void positionToOffsetClampedBeyondEnd() {
        int offset = provider.positionToOffset("hi", new Position(5, 5));
        assertTrue(offset <= 2);
    }

    // ================================================================
    // toLocation tests (via reflection)
    // ================================================================

    @Test
    void toLocationWithResource() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///ImplTarget.groovy"));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(0);
        when(match.getLength()).thenReturn(5);

        java.lang.reflect.Method m = ImplementationProvider.class.getDeclaredMethod(
                "toLocation", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Location loc = (org.eclipse.lsp4j.Location) m.invoke(provider, match);

        assertNotNull(loc);
        assertEquals("file:///ImplTarget.groovy", loc.getUri());
    }

    @Test
    void toLocationWithContentReturnsProperRange() throws Exception {
        String uri = "file:///ImplContent.groovy";
        documentManager.didOpen(uri, "class Foo {}");

        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(new java.net.URI(uri));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(6);
        when(match.getLength()).thenReturn(3);

        java.lang.reflect.Method m = ImplementationProvider.class.getDeclaredMethod(
                "toLocation", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Location loc = (org.eclipse.lsp4j.Location) m.invoke(provider, match);

        assertNotNull(loc);
        assertEquals(uri, loc.getUri());
        // offset 6 is "Foo" in "class Foo {}"
        assertEquals(0, loc.getRange().getStart().getLine());
        assertEquals(6, loc.getRange().getStart().getCharacter());
        documentManager.didClose(uri);
    }

    @Test
    void toLocationReturnsNullForNullResource() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getResource()).thenReturn(null);

        java.lang.reflect.Method m = ImplementationProvider.class.getDeclaredMethod(
                "toLocation", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Location loc = (org.eclipse.lsp4j.Location) m.invoke(provider, match);

        assertNull(loc);
    }

    @Test
    void toLocationReturnsNullForNullLocationURI() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(null);
        when(match.getResource()).thenReturn(resource);

        java.lang.reflect.Method m = ImplementationProvider.class.getDeclaredMethod(
                "toLocation", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Location loc = (org.eclipse.lsp4j.Location) m.invoke(provider, match);

        assertNull(loc);
    }

    @Test
    void toLocationReturnsDefaultRangeForNullContent() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///NoContent.groovy"));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(10);
        when(match.getLength()).thenReturn(5);

        java.lang.reflect.Method m = ImplementationProvider.class.getDeclaredMethod(
                "toLocation", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Location loc = (org.eclipse.lsp4j.Location) m.invoke(provider, match);

        assertNotNull(loc);
        // No content available -> default range (0,0)-(0,0)
        assertEquals(0, loc.getRange().getStart().getLine());
        assertEquals(0, loc.getRange().getStart().getCharacter());
    }
}
