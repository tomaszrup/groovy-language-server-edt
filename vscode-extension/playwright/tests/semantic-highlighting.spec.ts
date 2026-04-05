import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
    waitForBlockingNotificationsToClear,
    waitForGroovyReady,
} from '../support/vscodeHarness';
import type { TemporaryWorkspaceCopy, VsCodeSession } from '../support/vscodeHarness';

type RenderedSegment = {
    text: string;
    className: string;
};

const FIXTURE_PATH = 'src/test/groovy/com/example/sample/SemanticColorFixture.groovy';
const FEATURE_FIXTURE_PATH = 'src/test/groovy/com/example/sample/FeatureNameSpec.groovy';
const SEMANTIC_USER_SETTINGS = {
    'workbench.colorTheme': 'Default Dark+',
    'editor.semanticHighlighting.enabled': true,
    'groovy.inlayHints.variableTypes.enabled': false,
    'groovy.inlayHints.parameterNames.enabled': false,
    'groovy.inlayHints.closureParameterTypes.enabled': false,
    'groovy.inlayHints.methodReturnTypes.enabled': false,
};
const FIXTURE_SOURCE = `package com.example.sample

import spock.lang.Specification

class SemanticColorFixture extends Specification {
    @Deprecated
    String value = "x"

    def "feature() name"() {
        expect:
        def created = new StringBuilder("Test Name")
        def upper = value.toUpperCase()
        true
    }
}
`;

test.describe('semantic highlighting fixture coverage', () => {
    test.describe.configure({ mode: 'serial' });

    let workspace: TemporaryWorkspaceCopy;
    let session: VsCodeSession;

    test.beforeAll(async () => {
        workspace = createWorkspaceCopy();
        workspace.writeFile(FIXTURE_PATH, FIXTURE_SOURCE);

        session = await launchVsCode({
            workspacePath: workspace.workspacePath,
            userSettings: SEMANTIC_USER_SETTINGS,
        });
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await openFile(session.page, FIXTURE_PATH);
    });

    test.afterAll(async () => {
        await session.close();
        workspace.dispose();
    });

    test('highlights declaration keywords separately from type names', async () => {
        const segments = await getRenderedLineSegments(
            session.page,
            'class SemanticColorFixture extends Specification {'
        );

        expect(segments.map(segment => segment.text)).toEqual([
            'class',
            'SemanticColorFixture',
            'extends',
            'Specification',
            '{',
        ]);
        expect(segments[0].className).toBe(segments[2].className);
        expect(segments[1].className).toBe(segments[3].className);
        expect(segments[0].className).not.toBe(segments[1].className);
        expect(segments[4].className).toContain('bracket-highlighting');
    });

    test('highlights annotations and typed properties distinctly', async () => {
        const annotationSegments = await getRenderedLineSegments(session.page, '@Deprecated');
        const propertySegments = await getRenderedLineSegments(session.page, 'String value = "x"');

        expect(annotationSegments.map(segment => segment.text)).toEqual(['@Deprecated']);
        expect(propertySegments.map(segment => segment.text)).toEqual(['String', 'value', '=', '"x"']);
        expect(propertySegments[0].className).not.toBe(propertySegments[1].className);
        expect(propertySegments[0].className).not.toBe(propertySegments[3].className);
        expect(propertySegments[1].className).not.toBe(propertySegments[3].className);
    });

    test('keeps Spock feature names and labels tokenized cleanly', async () => {
        await assertFeatureNameRendering(session.page, 'def "feature() name"() {', '"feature() name"');

        const labelSegments = await getRenderedLineSegments(session.page, 'expect:');
        expect(labelSegments.map(segment => segment.text)).toEqual(['expect', ':']);
        expect(labelSegments[0].className).toBe(labelSegments[1].className);
    });

    test('highlights constructor types and method calls separately from variables', async () => {
        const constructorSegments = await getRenderedLineSegments(
            session.page,
            'def created = new StringBuilder("Test Name")'
        );
        const methodCallSegments = await getRenderedLineSegments(
            session.page,
            'def upper = value.toUpperCase()'
        );

        expect(constructorSegments.map(segment => segment.text)).toEqual([
            'def',
            'created',
            '=',
            'new',
            'StringBuilder',
            '(',
            '"Test Name"',
            ')',
        ]);
        expect(constructorSegments[4].className).not.toBe(constructorSegments[1].className);
        expect(constructorSegments[4].className).not.toBe(constructorSegments[0].className);
        expect(constructorSegments[6].className).not.toBe(constructorSegments[4].className);
        expect(constructorSegments[5].className).toContain('bracket-highlighting');
        expect(constructorSegments[7].className).toContain('bracket-highlighting');

        expect(methodCallSegments.map(segment => segment.text)).toEqual([
            'def',
            'upper',
            '=',
            'value',
            '.',
            'toUpperCase',
            '(',
            ')',
        ]);
        expect(methodCallSegments[5].className).not.toBe(methodCallSegments[3].className);
        expect(methodCallSegments[5].className).not.toBe(methodCallSegments[0].className);
        expect(methodCallSegments[6].className).toContain('bracket-highlighting');
        expect(methodCallSegments[7].className).toContain('bracket-highlighting');
    });
});

test('keeps parentheses inside quoted Spock feature names as a single string token', async () => {
    const workspace = createWorkspaceCopy();
    workspace.writeFile(FEATURE_FIXTURE_PATH, createFeatureSpecSource('abc loads()'));
    const session = await launchVsCode({
        workspacePath: workspace.workspacePath,
        userSettings: SEMANTIC_USER_SETTINGS,
    });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await openFile(session.page, FEATURE_FIXTURE_PATH);

        await assertFeatureNameRendering(session.page, 'def "abc loads()"() {', '"abc loads()"');
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('keeps unmatched opening parentheses inside quoted Spock feature names as a single string token', async () => {
    const workspace = createWorkspaceCopy();
    workspace.writeFile(FEATURE_FIXTURE_PATH, createFeatureSpecSource('abc loads('));

    const session = await launchVsCode({
        workspacePath: workspace.workspacePath,
        userSettings: SEMANTIC_USER_SETTINGS,
    });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await openFile(session.page, FEATURE_FIXTURE_PATH);

        await assertFeatureNameRendering(session.page, 'def "abc loads("() {', '"abc loads("');
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('keeps nested parentheses text inside quoted Spock feature names as a single string token', async () => {
    const workspace = createWorkspaceCopy();
    workspace.writeFile(FEATURE_FIXTURE_PATH, createFeatureSpecSource('abc loads(Asas)'));

    const session = await launchVsCode({
        workspacePath: workspace.workspacePath,
        userSettings: SEMANTIC_USER_SETTINGS,
    });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await openFile(session.page, FEATURE_FIXTURE_PATH);

        await assertFeatureNameRendering(session.page, 'def "abc loads(Asas)"() {', '"abc loads(Asas)"');
    } finally {
        await session.close();
        workspace.dispose();
    }
});

async function assertFeatureNameRendering(
    page: Page,
    renderedLine: string,
    expectedStringToken: string,
): Promise<void> {
    const segments = await getRenderedLineSegments(page, renderedLine);
    const openingParenIndex = segments.findIndex(segment =>
        segment.text === '(' && segment.className.includes('bracket-highlighting'));
    expect(openingParenIndex).toBeGreaterThan(1);

    const signatureSegments = segments.slice(0, openingParenIndex);
    expect(signatureSegments[0]?.text).toBe('def');

    const quotedSegments = signatureSegments.slice(1);
    expect(quotedSegments.every(segment => !segment.className.includes('bracket-highlighting')))
        .toBe(true);
    expect(quotedSegments.map(segment => segment.text).join('')).toBe(expectedStringToken);

    expect(segments.some(segment => segment.text === '(' && segment.className.includes('bracket-highlighting')))
        .toBe(true);
    expect(segments.some(segment => segment.text === ')' && segment.className.includes('bracket-highlighting')))
        .toBe(true);
}

async function getRenderedLineSegments(page: Page, renderedLine: string): Promise<RenderedSegment[]> {
    await expect(page.locator('.view-lines')).toContainText(renderedLine, {
        timeout: 30_000,
    });

    let renderedSegments: RenderedSegment[] | null = null;
    await expect.poll(async () => {
        renderedSegments = await page.locator('.view-lines .view-line').evaluateAll((nodes, expectedLine) => {
            const target = nodes.find(node =>
                (node.textContent ?? '').replaceAll('\u00a0', ' ').includes(expectedLine));
            if (!target) {
                return null;
            }

            return Array.from(target.querySelectorAll(':scope > span > span'))
                .map(node => ({
                    text: (node.textContent ?? '').replaceAll('\u00a0', ' '),
                    className: node.className,
                }))
                .filter(segment => segment.text.trim().length > 0);
        }, renderedLine);
        return renderedSegments ? 'ready' : 'missing';
    }, {
        timeout: 30_000,
    }).toBe('ready');

    return renderedSegments as RenderedSegment[];
}

function createFeatureSpecSource(featureName: string): string {
    return `package com.example.sample

import spock.lang.Specification

class FeatureNameSpec extends Specification {
    def "${featureName}"() {
        expect:
        true
    }
}
`;
}