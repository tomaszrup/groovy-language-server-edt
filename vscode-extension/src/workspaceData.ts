import * as fs from 'node:fs';
import * as path from 'node:path';
import { detectWorkspaceRestoreCorruption } from './workspaceRecovery';

const WORKSPACE_DATA_VERSION = 2;
const WORKSPACE_DATA_DIR = 'groovy_ws';
const WORKSPACE_DATA_STATE_FILE = 'groovy_ws_state.json';

export interface WorkspaceDataPreparation {
    dataDir: string;
    resetWorkspace: boolean;
    reason: string;
}

interface PrepareWorkspaceDataDirOptions {
    storagePath: string;
    log?: (message: string) => void;
}

export function prepareWorkspaceDataDir(options: PrepareWorkspaceDataDirOptions): WorkspaceDataPreparation {
    const dataDir = path.join(options.storagePath, WORKSPACE_DATA_DIR);
    const stateFile = path.join(options.storagePath, WORKSPACE_DATA_STATE_FILE);
    let resetWorkspace = false;
    let resetReason = '';

    if (fs.existsSync(stateFile)) {
        try {
            const state = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as { version?: number };
            if (state.version !== WORKSPACE_DATA_VERSION) {
                resetWorkspace = true;
                resetReason = `workspace data version ${state.version ?? 'unknown'} -> ${WORKSPACE_DATA_VERSION}`;
            }
        } catch {
            resetWorkspace = true;
            resetReason = 'invalid workspace data marker';
        }
    } else if (fs.existsSync(dataDir)) {
        resetWorkspace = true;
        resetReason = 'missing workspace data marker';
    }

    if (!resetWorkspace) {
        const corruptionReason = detectWorkspaceRestoreCorruption(dataDir);
        if (corruptionReason) {
            resetWorkspace = true;
            resetReason = corruptionReason;
        }
    }

    if (resetWorkspace && fs.existsSync(dataDir)) {
        try {
            fs.rmSync(dataDir, { recursive: true, force: true });
        } catch (error) {
            options.log?.(`Warning: failed to reset workspace data: ${error}`);
        }
    }

    fs.mkdirSync(dataDir, { recursive: true });
    fs.writeFileSync(
        stateFile,
        JSON.stringify({ version: WORKSPACE_DATA_VERSION, updatedAt: new Date().toISOString() }, null, 2),
        'utf8'
    );

    return {
        dataDir,
        resetWorkspace,
        reason: resetReason,
    };
}