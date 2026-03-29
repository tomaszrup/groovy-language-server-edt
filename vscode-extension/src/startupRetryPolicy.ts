export type InitialClasspathRetryAction =
    | 'batch-complete'
    | 'force-config-and-retry'
    | 'retry'
    | 'max-attempts';

export type InitialClasspathImportRefreshAction =
    | 're-enumerate-projects'
    | 'refresh-imported-projects';

interface InitialClasspathRetryActionInput {
    attempt: number;
    maxAttempts: number;
    canSendBatchComplete: boolean;
}

interface InitialClasspathImportRefreshActionInput {
    importedProjectCount: number;
}

export function getInitialClasspathRetryAction(
    input: InitialClasspathRetryActionInput
): InitialClasspathRetryAction {
    if (input.canSendBatchComplete) {
        return 'batch-complete';
    }

    if (input.attempt >= input.maxAttempts) {
        return 'max-attempts';
    }

    return input.attempt === 1 ? 'force-config-and-retry' : 'retry';
}

export function getInitialClasspathImportRefreshAction(
    input: InitialClasspathImportRefreshActionInput
): InitialClasspathImportRefreshAction {
    return input.importedProjectCount === 0
        ? 're-enumerate-projects'
        : 'refresh-imported-projects';
}
