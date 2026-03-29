import { strict as assert } from 'assert';
import {
    getInitialClasspathImportRefreshAction,
    getInitialClasspathRetryAction,
} from '../startupRetryPolicy';

describe('startupRetryPolicy', () => {
    it('re-enumerates projects after an empty import payload', () => {
        const action = getInitialClasspathImportRefreshAction({
            importedProjectCount: 0,
        });

        assert.equal(action, 're-enumerate-projects');
    });

    it('refreshes only explicitly imported projects when JDT provides them', () => {
        const action = getInitialClasspathImportRefreshAction({
            importedProjectCount: 2,
        });

        assert.equal(action, 'refresh-imported-projects');
    });

    it('keeps the first output-only startup attempt in retry mode', () => {
        const action = getInitialClasspathRetryAction({
            attempt: 1,
            maxAttempts: 6,
            canSendBatchComplete: false,
        });

        assert.equal(action, 'force-config-and-retry');
    });

    it('completes once a jar-bearing classpath makes startup ready', () => {
        const action = getInitialClasspathRetryAction({
            attempt: 2,
            maxAttempts: 6,
            canSendBatchComplete: true,
        });

        assert.equal(action, 'batch-complete');
    });

    it('stops retrying after the configured maximum attempt', () => {
        const action = getInitialClasspathRetryAction({
            attempt: 6,
            maxAttempts: 6,
            canSendBatchComplete: false,
        });

        assert.equal(action, 'max-attempts');
    });
});
