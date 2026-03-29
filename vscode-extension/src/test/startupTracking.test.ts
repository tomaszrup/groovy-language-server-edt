import { strict as assert } from 'assert';
import {
    getClasspathTargetId,
    InitialClasspathStartupTracker,
    type StartupClasspathTarget,
} from '../startupTracking';

describe('startupTracking', () => {
    it('resolves imported output-only targets once import is confirmed', () => {
        const tracker = new InitialClasspathStartupTracker();
        const pendingTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const importedTarget = createTarget({
            requestUri: 'file:///workspace/app/',
            projectUri: 'file:///workspace/app/',
            source: 'onDidProjectsImport',
        });

        tracker.replaceDiscoveredTargets([pendingTarget], tracker.getPendingTargetsSnapshot());
        tracker.markTargetsImportConfirmed([importedTarget]);
        const [importedHandle] = tracker.mergePendingTargets([importedTarget]);

        assert.equal(getClasspathTargetId(pendingTarget), getClasspathTargetId(importedTarget));
        assert.notEqual(importedHandle, undefined);
        assert.equal(tracker.isStartupClasspathResolved(importedTarget, false), true);
        assert.equal(tracker.getPendingTargetCount(), 1);
        assert.equal(tracker.getPendingTargets()[0].source, 'onDidProjectsImport');
        assert.equal(tracker.resolvePendingTarget(importedHandle!), true);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('normalizes remote Windows project URIs to the same startup target id', () => {
        const pendingTarget = createTarget({
            requestUri: 'file:///C:/Work/Proj',
            projectUri: 'file:///C:/Work/Proj',
            projectPath: 'C:/Work/Proj',
            source: 'java.project.getAll',
        });
        const importedTarget = createTarget({
            requestUri: 'vscode-remote://ssh-remote+dev/C%3A/Work/Proj/src/App.groovy',
            projectUri: 'vscode-remote://ssh-remote+dev/C%3A/Work/Proj',
            source: 'onDidProjectsImport',
        });

        assert.equal(getClasspathTargetId(pendingTarget), getClasspathTargetId(importedTarget));
    });

    it('keeps java-project output-only targets pending before jars arrive', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        assert.equal(tracker.isStartupClasspathResolved(target, false), false);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);
        assert.equal(tracker.getPendingTargetCount(), 1);
    });

    it('keeps build-file probes with output-only classpaths pending', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app/src/main/groovy/App.groovy',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'build-file-scan',
        });

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        assert.equal(tracker.isStartupClasspathResolved(target, false), false);
        assert.equal(tracker.getPendingTargetCount(), 1);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);
    });

    it('keeps workspace-folder output-only targets pending before import completion', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'workspace-folder-fallback',
        });

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        assert.equal(tracker.isStartupClasspathResolved(target, false), false);
        assert.equal(tracker.getPendingTargetCount(), 1);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);
    });

    it('allows delivered output-only targets to complete the initial batch', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        const attemptedSnapshot = tracker.getPendingTargetsSnapshot();
        tracker.reconcilePendingTargets(attemptedSnapshot, []);

        assert.equal(tracker.isStartupClasspathResolved(target, false), false);
        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('keeps undelivered output-only targets pending after reconciliation', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'workspace-folder-fallback',
        });

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        const attemptedSnapshot = tracker.getPendingTargetsSnapshot();
        tracker.reconcilePendingTargets(attemptedSnapshot, [target]);

        assert.equal(tracker.getPendingTargetCount(), 1);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);
    });

    it('does not complete startup from merged targets before authoritative discovery', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidClasspathUpdate',
        });

        const [targetHandle] = tracker.mergePendingTargets([target]);

        assert.equal(tracker.isStartupClasspathResolved(target, true), true);
    assert.notEqual(targetHandle, undefined);
    assert.equal(tracker.resolvePendingTarget(targetHandle!), false);
        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);
    });

    it('completes jar-bearing startup targets even when no import event is observed', () => {
        const tracker = new InitialClasspathStartupTracker();
        const firstTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const secondTarget = createTarget({
            requestUri: 'file:///workspace/lib',
            projectUri: 'file:///workspace/lib',
            projectPath: '/workspace/lib',
            source: 'java.project.getAll',
        });

        tracker.replaceDiscoveredTargets([firstTarget, secondTarget], tracker.getPendingTargetsSnapshot());

        assert.equal(tracker.isStartupClasspathResolved(firstTarget, true), true);
        assert.equal(tracker.isStartupClasspathResolved(secondTarget, true), true);

        tracker.markInitialTargetResolved(firstTarget);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);

        tracker.markInitialTargetResolved(secondTarget);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), true);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('completes startup when the latest same-project revision resolves after discovery', () => {
        const tracker = new InitialClasspathStartupTracker();
        const initialTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const refreshedTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidClasspathUpdate',
        });

        tracker.replaceDiscoveredTargets([initialTarget], tracker.getPendingTargetsSnapshot());
        const [refreshedHandle] = tracker.mergePendingTargets([refreshedTarget]);

        assert.notEqual(refreshedHandle, undefined);
        assert.equal(tracker.resolvePendingTarget(refreshedHandle!), true);
        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('keeps batch completion blocked until every startup target resolves', () => {
        const tracker = new InitialClasspathStartupTracker();
        const readyTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const stillPendingTarget = createTarget({
            requestUri: 'file:///workspace/lib',
            projectUri: 'file:///workspace/lib',
            projectPath: '/workspace/lib',
            source: 'java.project.getAll',
        });

        tracker.replaceDiscoveredTargets([readyTarget, stillPendingTarget], tracker.getPendingTargetsSnapshot());
        tracker.markInitialTargetResolved(readyTarget);

        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);
        assert.equal(tracker.getPendingTargetCount(), 1);
    });

    it('keeps batch completion blocked while no target is startup-ready', () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);
    });

    it('serializes queued batch attempts before allowing completion', async () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const order: string[] = [];
        const firstGate = createDeferred<void>();
        const secondGate = createDeferred<void>();

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());
        tracker.markInitialTargetResolved(target);

        const firstAttempt = tracker.runSerializedBatchAttempt(async () => {
            order.push('first-start');
            await firstGate.promise;
            order.push('first-end');
        });
        const secondAttempt = tracker.runSerializedBatchAttempt(async () => {
            order.push('second-start');
            await secondGate.promise;
            order.push('second-end');
        });

        await flushPromises();
        assert.deepEqual(order, ['first-start']);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);

        firstGate.resolve();
        await flushPromises();
        assert.deepEqual(order, ['first-start', 'first-end', 'second-start']);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);

        secondGate.resolve();
        await Promise.all([firstAttempt, secondAttempt]);

        assert.deepEqual(order, ['first-start', 'first-end', 'second-start', 'second-end']);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), true);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('only resolves startup immediately when no batch attempt is active', async () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const gate = createDeferred<void>();

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());
        const [targetHandle] = tracker.mergePendingTargets([target]);

        const attempt = tracker.runSerializedBatchAttempt(async () => {
            await gate.promise;
        });

        await flushPromises();
        assert.notEqual(targetHandle, undefined);
        assert.equal(tracker.resolvePendingTarget(targetHandle!), false);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);

        gate.resolve();
        await attempt;

        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('queues empty-import fallback behind an active retry attempt', async () => {
        const tracker = new InitialClasspathStartupTracker();
        const target = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'workspace-folder-fallback',
        });
        const order: string[] = [];
        const retryGate = createDeferred<void>();
        const fallbackGate = createDeferred<void>();

        tracker.replaceDiscoveredTargets([target], tracker.getPendingTargetsSnapshot());

        const retryAttempt = tracker.runSerializedBatchAttempt(async () => {
            order.push('retry-start');
            await retryGate.promise;
            order.push(`retry-ready:${tracker.isStartupClasspathResolved(target, false)}`);
        });

        const emptyImportFallback = tracker.runSerializedBatchAttempt(async () => {
            order.push('fallback-start');
            assert.equal(tracker.isStartupClasspathResolved(target, false), false);
            await fallbackGate.promise;
            order.push('fallback-end');
        });

        await flushPromises();
        assert.deepEqual(order, ['retry-start']);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);

        retryGate.resolve();
        await flushPromises();
        assert.deepEqual(order, ['retry-start', 'retry-ready:false', 'fallback-start']);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);

        fallbackGate.resolve();
        await Promise.all([retryAttempt, emptyImportFallback]);

        assert.deepEqual(order, ['retry-start', 'retry-ready:false', 'fallback-start', 'fallback-end']);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);
        assert.equal(tracker.getPendingTargetCount(), 1);
    });

    it('preserves concurrently merged targets when replacing discovered targets', () => {
        const tracker = new InitialClasspathStartupTracker();
        const initialTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const concurrentTarget = createTarget({
            requestUri: 'file:///workspace/lib',
            projectUri: 'file:///workspace/lib',
            projectPath: '/workspace/lib',
            source: 'onDidProjectsImport',
        });

        tracker.replaceDiscoveredTargets([initialTarget], tracker.getPendingTargetsSnapshot());
        const baselineSnapshot = tracker.getPendingTargetsSnapshot();

        tracker.mergePendingTargets([concurrentTarget]);
        tracker.replaceDiscoveredTargets([initialTarget], baselineSnapshot);

        assert.equal(tracker.getPendingTargetCount(), 2);
        const pendingTargetsById = new Map(
            tracker.getPendingTargets().map(target => [getClasspathTargetId(target), target])
        );
        assert.equal(pendingTargetsById.get(getClasspathTargetId(initialTarget))?.source, 'java.project.getAll');
        assert.equal(pendingTargetsById.get(getClasspathTargetId(concurrentTarget))?.source, 'onDidProjectsImport');
    });

    it('preserves import confirmation across replacement and same-project reconciliation', () => {
        const tracker = new InitialClasspathStartupTracker();
        const initialTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const rediscoveredTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'build-file-scan',
        });
        const refreshedTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidClasspathUpdate',
        });

        tracker.replaceDiscoveredTargets([initialTarget], tracker.getPendingTargetsSnapshot());
        tracker.markTargetsImportConfirmed([initialTarget]);

        const replacementSnapshot = tracker.getPendingTargetsSnapshot();
        tracker.replaceDiscoveredTargets([rediscoveredTarget], replacementSnapshot);
        assert.equal(tracker.isStartupClasspathResolved(rediscoveredTarget, false), true);

        const attemptedSnapshot = tracker.getPendingTargetsSnapshot();

        tracker.mergePendingTargets([refreshedTarget]);
        tracker.reconcilePendingTargets(attemptedSnapshot, []);

        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
        assert.equal(tracker.isStartupClasspathResolved(refreshedTarget, false), true);
    });

    it('does not reopen initial delivery when a newer same-project revision arrives', () => {
        const tracker = new InitialClasspathStartupTracker();
        const initialTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const refreshedTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidClasspathUpdate',
        });

        tracker.replaceDiscoveredTargets([initialTarget], tracker.getPendingTargetsSnapshot());

        const attemptedSnapshot = tracker.getPendingTargetsSnapshot();
        const [refreshedHandle] = tracker.mergePendingTargets([refreshedTarget]);
        tracker.reconcilePendingTargets(attemptedSnapshot, []);

        assert.notEqual(refreshedHandle, undefined);
        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.resolvePendingTarget(refreshedHandle!), false);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('keeps newer same-project revisions pending when an older handle resolves', () => {
        const tracker = new InitialClasspathStartupTracker();
        const initialTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const firstRefresh = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidClasspathUpdate',
        });
        const secondRefresh = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidProjectsImport',
        });

        tracker.replaceDiscoveredTargets([initialTarget], tracker.getPendingTargetsSnapshot());
        const [firstHandle] = tracker.mergePendingTargets([firstRefresh]);
        const [secondHandle] = tracker.mergePendingTargets([secondRefresh]);

        assert.notEqual(firstHandle, undefined);
        assert.notEqual(secondHandle, undefined);
        assert.equal(tracker.resolvePendingTarget(firstHandle!), false);
        assert.equal(tracker.getPendingTargetCount(), 1);
        assert.equal(tracker.getPendingTargets()[0].source, 'onDidProjectsImport');
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), false);

        assert.equal(tracker.resolvePendingTarget(secondHandle!), true);
        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('does not let a stale handle complete startup after the latest revision resolves', () => {
        const tracker = new InitialClasspathStartupTracker();
        const initialTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'java.project.getAll',
        });
        const firstRefresh = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidClasspathUpdate',
        });
        const secondRefresh = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'onDidProjectsImport',
        });

        tracker.replaceDiscoveredTargets([initialTarget], tracker.getPendingTargetsSnapshot());
        const [firstHandle] = tracker.mergePendingTargets([firstRefresh]);
        const [secondHandle] = tracker.mergePendingTargets([secondRefresh]);

        assert.notEqual(firstHandle, undefined);
        assert.notEqual(secondHandle, undefined);
        assert.equal(tracker.resolvePendingTarget(secondHandle!), true);
        assert.equal(tracker.getPendingTargetCount(), 0);
        assert.equal(tracker.resolvePendingTarget(firstHandle!), false);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });

    it('allows current and later-discovered output-only targets after empty import fallback', () => {
        const tracker = new InitialClasspathStartupTracker();
        const currentTarget = createTarget({
            requestUri: 'file:///workspace/app',
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            source: 'workspace-folder-fallback',
        });
        const lateTarget = createTarget({
            requestUri: 'file:///workspace/lib',
            projectUri: 'file:///workspace/lib',
            projectPath: '/workspace/lib',
            source: 'java.project.getAll',
        });

        tracker.replaceDiscoveredTargets([currentTarget], tracker.getPendingTargetsSnapshot());
        tracker.enableEmptyImportFallback();

        assert.equal(tracker.isStartupClasspathResolved(currentTarget, false), true);

        const replacementSnapshot = tracker.getPendingTargetsSnapshot();
        tracker.replaceDiscoveredTargets([currentTarget, lateTarget], replacementSnapshot);

        assert.equal(tracker.isStartupClasspathResolved(lateTarget, false), true);

        tracker.markInitialTargetResolved(currentTarget);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), false);

        tracker.markInitialTargetResolved(lateTarget);
        assert.equal(tracker.canCompleteInitialClasspathBatch(), true);
        assert.equal(tracker.shouldSendInitialClasspathBatchComplete(), true);
    });
});

function createTarget(overrides: Partial<StartupClasspathTarget>): StartupClasspathTarget {
    return {
        requestUri: 'file:///workspace/default',
        projectUri: 'file:///workspace/default',
        source: 'test',
        ...overrides,
    };
}

function createDeferred<T>(): {
    promise: Promise<T>;
    resolve: (value: T | PromiseLike<T>) => void;
    reject: (reason?: unknown) => void;
} {
    let resolve!: (value: T | PromiseLike<T>) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
    });
    return { promise, resolve, reject };
}

async function flushPromises(): Promise<void> {
    await new Promise<void>(resolve => setImmediate(resolve));
}
