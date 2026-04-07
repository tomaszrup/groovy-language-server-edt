import * as assert from 'node:assert/strict';
import { classifyDevHostNotifications, hasSampleClasspathReadyOutput } from '../playwrightReadiness';

describe('playwrightReadiness', () => {
    it('treats Java project import progress as dismissible instead of blocking', () => {
        const result = classifyDevHostNotifications([
            'Opening Java Projects: check details',
            'Background task finished',
        ]);

        assert.deepEqual(result.blocking, []);
        assert.deepEqual(result.dismissible, ['Opening Java Projects: check details']);
    });

    it('keeps dev-host prompts classified as blocking', () => {
        const result = classifyDevHostNotifications([
            'Would you like to help improve Java by allowing collect usage data?',
            'Do you want to open the repository?',
        ]);

        assert.deepEqual(result.blocking, [
            'Would you like to help improve Java by allowing collect usage data?',
            'Do you want to open the repository?',
        ]);
        assert.deepEqual(result.dismissible, []);
    });

    it('accepts the usable classpath summary output', () => {
        assert.equal(
            hasSampleClasspathReadyOutput('[java-ext] Sent usable classpath for 1/1 project(s) (1 fully resolved, 0 still awaiting initial delivery)'),
            true
        );
    });

    it('accepts the delivered classpath batch complete output', () => {
        assert.equal(
            hasSampleClasspathReadyOutput('[java-ext] Sent groovy/classpathBatchComplete to server (delivered 1 project(s) on attempt 2).'),
            true
        );
    });

    it('rejects max-attempts fallback output for sample classpath readiness', () => {
        assert.equal(
            hasSampleClasspathReadyOutput('[java-ext] Sent groovy/classpathBatchComplete to server (max attempts reached (6)).'),
            false
        );
    });
});