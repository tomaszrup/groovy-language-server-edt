import * as fs from 'node:fs';
import * as path from 'node:path';

const WORKSPACE_LOG_FILES = ['.log', '.bak_0.log'];

export function detectWorkspaceRestoreCorruption(dataDir: string): string | undefined {
    const metadataDir = path.join(dataDir, '.metadata');
    if (!fs.existsSync(metadataDir)) {
        return undefined;
    }

    for (const logFileName of WORKSPACE_LOG_FILES) {
        const logFile = path.join(metadataDir, logFileName);
        if (!fs.existsSync(logFile)) {
            continue;
        }

        let contents = '';
        try {
            contents = fs.readFileSync(logFile, 'utf8');
        } catch {
            continue;
        }

        const hasObjectNotFound = contents.includes('org.eclipse.core.internal.dtree.ObjectNotFoundException');
        const hasRestoreFailure = contents.includes('SaveManager.restore')
            || contents.includes('ResourcesPlugin.start()')
            || contents.includes('FrameworkEvent ERROR');

        if (hasObjectNotFound && hasRestoreFailure) {
            return `corrupt Eclipse workspace restore state detected in ${logFileName}`;
        }
    }

    return undefined;
}
