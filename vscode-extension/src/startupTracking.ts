import { normalizeFsPath, uriToFsPath } from './utils';

export interface StartupClasspathTarget {
    requestUri: string;
    projectUri: string;
    projectPath?: string;
    source: string;
}

export interface PendingStartupClasspathSnapshot {
    targets: StartupClasspathTarget[];
    revisionsById: ReadonlyMap<string, number>;
}

export interface PendingStartupClasspathTargetHandle {
    targetId: string;
    revision: number;
}

interface PendingStartupClasspathTargetEntry {
    target: StartupClasspathTarget;
    revision: number;
    importConfirmed: boolean;
}

function normalizeUriPath(uriValue: string): string | undefined {
    const fsPath = uriToFsPath(uriValue);
    return fsPath ? normalizeFsPath(fsPath) : undefined;
}

export function getClasspathTargetId(target: StartupClasspathTarget): string {
    if (target.projectPath) {
        return `path:${normalizeFsPath(target.projectPath)}`;
    }

    const normalizedProjectUriPath = normalizeUriPath(target.projectUri);
    if (normalizedProjectUriPath) {
        return `path:${normalizedProjectUriPath}`;
    }

    const normalizedRequestUriPath = normalizeUriPath(target.requestUri);
    if (normalizedRequestUriPath) {
        return `path:${normalizedRequestUriPath}`;
    }

    return `uri:${target.projectUri || target.requestUri}`;
}

export class InitialClasspathStartupTracker {
    private pendingTargetsById = new Map<string, PendingStartupClasspathTargetEntry>();
    private deliveredTargetIds = new Set<string>();
    private importConfirmedTargetIds = new Set<string>();
    private hasDiscoveredInitialTargets = false;
    private allowOutputOnlyTargetsAfterEmptyImport = false;
    private queuedBatchAttempts = 0;
    private runningBatchAttempts = 0;
    private batchAttemptTail: Promise<void> = Promise.resolve();
    private pendingTargetRevision = 0;

    reset(): void {
        this.pendingTargetsById.clear();
        this.deliveredTargetIds.clear();
        this.importConfirmedTargetIds.clear();
        this.hasDiscoveredInitialTargets = false;
        this.allowOutputOnlyTargetsAfterEmptyImport = false;
        this.queuedBatchAttempts = 0;
        this.runningBatchAttempts = 0;
        this.batchAttemptTail = Promise.resolve();
        this.pendingTargetRevision = 0;
    }

    replaceDiscoveredTargets(
        targets: StartupClasspathTarget[],
        baselineSnapshot: PendingStartupClasspathSnapshot
    ): void {
        const nextPendingTargetsById = new Map<string, PendingStartupClasspathTargetEntry>();

        for (const target of targets) {
            const targetId = getClasspathTargetId(target);
            if (this.deliveredTargetIds.has(targetId)) {
                continue;
            }

            const currentEntry = this.pendingTargetsById.get(targetId);
            const baselineRevision = baselineSnapshot.revisionsById.get(targetId);
            if (currentEntry && (baselineRevision === undefined || currentEntry.revision !== baselineRevision)) {
                nextPendingTargetsById.set(targetId, currentEntry);
                continue;
            }

            nextPendingTargetsById.set(
                targetId,
                this.createPendingTargetEntry(
                    target,
                    currentEntry?.importConfirmed ?? this.importConfirmedTargetIds.has(targetId)
                )
            );
        }

        for (const [targetId, entry] of this.pendingTargetsById.entries()) {
            if (this.deliveredTargetIds.has(targetId)) {
                continue;
            }

            if (nextPendingTargetsById.has(targetId)) {
                continue;
            }

            const baselineRevision = baselineSnapshot.revisionsById.get(targetId);
            if (baselineRevision === undefined || entry.revision !== baselineRevision) {
                nextPendingTargetsById.set(targetId, entry);
            }
        }

        this.pendingTargetsById = nextPendingTargetsById;
        if (targets.length > 0) {
            this.hasDiscoveredInitialTargets = true;
        }
    }

    clearPendingTargets(): void {
        this.pendingTargetsById.clear();
    }

    mergePendingTargets(targets: StartupClasspathTarget[]): Array<PendingStartupClasspathTargetHandle | undefined> {
        if (targets.length === 0) {
            return [];
        }

        const handles: Array<PendingStartupClasspathTargetHandle | undefined> = [];
        for (const target of targets) {
            const targetId = getClasspathTargetId(target);
            if (this.deliveredTargetIds.has(targetId)) {
                handles.push(undefined);
                continue;
            }

            const currentEntry = this.pendingTargetsById.get(targetId);
            const nextEntry = this.createPendingTargetEntry(
                target,
                currentEntry?.importConfirmed ?? this.importConfirmedTargetIds.has(targetId)
            );
            this.pendingTargetsById.set(targetId, nextEntry);
            handles.push(
                this.createPendingTargetHandle(targetId, nextEntry)
            );
        }
        return handles;
    }

    markTargetsImportConfirmed(targets: StartupClasspathTarget[]): void {
        for (const target of targets) {
            const targetId = getClasspathTargetId(target);
            this.importConfirmedTargetIds.add(targetId);

            const currentEntry = this.pendingTargetsById.get(targetId);
            if (currentEntry && !currentEntry.importConfirmed) {
                this.pendingTargetsById.set(
                    targetId,
                    this.createPendingTargetEntry(currentEntry.target, true)
                );
            }
        }
    }

    enableEmptyImportFallback(): void {
        this.allowOutputOnlyTargetsAfterEmptyImport = true;
    }

    reconcilePendingTargets(
        attemptedSnapshot: PendingStartupClasspathSnapshot,
        remainingTargets: StartupClasspathTarget[]
    ): void {
        const remainingTargetIds = new Set(remainingTargets.map(getClasspathTargetId));
        const deliveredTargetIds = new Set<string>();
        for (const targetId of attemptedSnapshot.revisionsById.keys()) {
            if (!remainingTargetIds.has(targetId)) {
                deliveredTargetIds.add(targetId);
            }
        }

        deliveredTargetIds.forEach(targetId => this.deliveredTargetIds.add(targetId));
        const nextPendingTargetsById = new Map<string, PendingStartupClasspathTargetEntry>();

        for (const [targetId, entry] of this.pendingTargetsById.entries()) {
            if (deliveredTargetIds.has(targetId)) {
                continue;
            }

            const attemptedRevision = attemptedSnapshot.revisionsById.get(targetId);
            if (attemptedRevision === undefined || entry.revision !== attemptedRevision) {
                nextPendingTargetsById.set(targetId, entry);
                continue;
            }

            if (remainingTargetIds.has(targetId)) {
                nextPendingTargetsById.set(targetId, entry);
            }
        }

        this.pendingTargetsById = nextPendingTargetsById;
    }

    markInitialTargetResolved(target: StartupClasspathTarget): void {
        const targetId = getClasspathTargetId(target);
        this.deliveredTargetIds.add(targetId);
        this.pendingTargetsById.delete(targetId);
    }

    resolvePendingTarget(handle: PendingStartupClasspathTargetHandle): boolean {
        const currentEntry = this.pendingTargetsById.get(handle.targetId);
        if (!currentEntry || currentEntry.revision !== handle.revision) {
            return false;
        }

        this.pendingTargetsById.delete(handle.targetId);
        return this.canCompleteInitialClasspathBatch();
    }

    isStartupClasspathResolved(target: StartupClasspathTarget, hasJarEntries: boolean): boolean {
        return hasJarEntries
            || this.allowOutputOnlyTargetsAfterEmptyImport
            || this.isTargetImportConfirmed(target);
    }

    canCompleteInitialClasspathBatch(): boolean {
        return !this.hasBatchAttemptWork()
            && this.hasDiscoveredInitialTargets
            && this.pendingTargetsById.size === 0;
    }

    shouldSendInitialClasspathBatchComplete(): boolean {
        return this.canCompleteInitialClasspathBatch();
    }

    hasBatchAttemptWork(): boolean {
        return this.queuedBatchAttempts > 0 || this.runningBatchAttempts > 0;
    }

    getPendingTargetCount(): number {
        return this.pendingTargetsById.size;
    }

    getPendingTargets(): StartupClasspathTarget[] {
        return this.getPendingTargetsSnapshot().targets;
    }

    getPendingTargetsSnapshot(): PendingStartupClasspathSnapshot {
        const targets: StartupClasspathTarget[] = [];
        const revisionsById = new Map<string, number>();

        for (const [targetId, entry] of this.pendingTargetsById.entries()) {
            targets.push(entry.target);
            revisionsById.set(targetId, entry.revision);
        }

        return {
            targets,
            revisionsById,
        };
    }

    runSerializedBatchAttempt<T>(operation: () => Promise<T>): Promise<T> {
        this.queuedBatchAttempts++;
        const runOperation = async (): Promise<T> => {
            this.queuedBatchAttempts--;
            this.runningBatchAttempts++;
            try {
                return await operation();
            } finally {
                this.runningBatchAttempts--;
            }
        };

        const result = this.batchAttemptTail.then(runOperation, runOperation);
        this.batchAttemptTail = result.then(() => undefined, () => undefined);
        return result;
    }

    private isTargetImportConfirmed(target: StartupClasspathTarget): boolean {
        const targetId = getClasspathTargetId(target);
        const currentEntry = this.pendingTargetsById.get(targetId);
        return currentEntry?.importConfirmed ?? this.importConfirmedTargetIds.has(targetId);
    }

    private createPendingTargetEntry(
        target: StartupClasspathTarget,
        importConfirmed: boolean = false
    ): PendingStartupClasspathTargetEntry {
        this.pendingTargetRevision++;
        return {
            target,
            revision: this.pendingTargetRevision,
            importConfirmed,
        };
    }

    private createPendingTargetHandle(
        targetId: string,
        entry: PendingStartupClasspathTargetEntry
    ): PendingStartupClasspathTargetHandle {
        return {
            targetId,
            revision: entry.revision,
        };
    }
}
