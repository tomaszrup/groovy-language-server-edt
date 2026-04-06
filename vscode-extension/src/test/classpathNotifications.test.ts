import { strict as assert } from 'assert';
import {
    recordClasspathNotificationFingerprint,
    type ClasspathNotificationPayload,
} from '../classpathNotifications';

describe('classpathNotifications', () => {
    it('records the first classpath payload and skips exact duplicates', () => {
        const sentFingerprints = new Map<string, string>();
        const payload: ClasspathNotificationPayload = {
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            entries: ['/workspace/libs/b.jar', '/workspace/libs/a.jar'],
            hasJarEntries: true,
        };

        assert.equal(recordClasspathNotificationFingerprint(sentFingerprints, payload), true);
        assert.equal(recordClasspathNotificationFingerprint(sentFingerprints, {
            ...payload,
            entries: ['/workspace/libs/a.jar', '/workspace/libs/b.jar'],
        }), false);
    });

    it('records changed payloads for the same project key', () => {
        const sentFingerprints = new Map<string, string>();
        const payload: ClasspathNotificationPayload = {
            projectUri: 'file:///workspace/app',
            projectPath: '/workspace/app',
            entries: ['/workspace/libs/a.jar'],
            hasJarEntries: true,
        };

        assert.equal(recordClasspathNotificationFingerprint(sentFingerprints, payload), true);
        assert.equal(recordClasspathNotificationFingerprint(sentFingerprints, {
            ...payload,
            hasJarEntries: false,
        }), true);
    });
});