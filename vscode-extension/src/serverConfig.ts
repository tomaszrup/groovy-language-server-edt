import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { getConfigNameForPlatform } from './utils';

const CONFIG_STATE_VERSION = 1;
const CONFIG_STATE_FILE = 'groovy_config_state.json';
const WRITABLE_CONFIG_DIR = 'groovy_equinox_config';

export interface EquinoxConfigPreparation {
    bundledConfigDir: string;
    configDir: string;
    resetConfig: boolean;
    reason: string;
}

interface EquinoxConfigState {
    version?: number;
    extensionVersion?: string;
    templateFingerprint?: string;
}

interface PrepareWritableConfigDirOptions {
    serverDir: string;
    storagePath: string;
    platform: NodeJS.Platform;
    extensionVersion: string;
}

export function prepareWritableConfigDir(options: PrepareWritableConfigDirOptions): EquinoxConfigPreparation {
    const bundledConfigDir = path.join(options.serverDir, getConfigNameForPlatform(options.platform));
    const configDir = path.join(options.storagePath, WRITABLE_CONFIG_DIR);
    const stateFile = path.join(options.storagePath, CONFIG_STATE_FILE);
    const templateFingerprint = fingerprintDirectory(bundledConfigDir);
    const existingConfigIni = path.join(configDir, 'config.ini');

    let resetConfig = false;
    let reason = '';

    if (!fs.existsSync(existingConfigIni)) {
        resetConfig = fs.existsSync(configDir);
        reason = 'missing writable Equinox config';
    } else if (!fs.existsSync(stateFile)) {
        resetConfig = true;
        reason = 'missing Equinox config marker';
    } else {
        try {
            const state = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as EquinoxConfigState;
            if (state.version !== CONFIG_STATE_VERSION) {
                resetConfig = true;
                reason = `config state version ${state.version ?? 'unknown'} -> ${CONFIG_STATE_VERSION}`;
            } else if (state.extensionVersion !== options.extensionVersion) {
                resetConfig = true;
                reason = `extension version ${state.extensionVersion ?? 'unknown'} -> ${options.extensionVersion}`;
            } else if (state.templateFingerprint !== templateFingerprint) {
                resetConfig = true;
                reason = 'bundled Equinox config changed';
            }
        } catch {
            resetConfig = true;
            reason = 'invalid Equinox config marker';
        }
    }

    if (resetConfig && fs.existsSync(configDir)) {
        fs.rmSync(configDir, { recursive: true, force: true });
    }

    fs.mkdirSync(options.storagePath, { recursive: true });

    if (!fs.existsSync(existingConfigIni) || resetConfig) {
        fs.cpSync(bundledConfigDir, configDir, { recursive: true, force: true });
    }

    fs.writeFileSync(
        stateFile,
        JSON.stringify({
            version: CONFIG_STATE_VERSION,
            extensionVersion: options.extensionVersion,
            templateFingerprint,
            updatedAt: new Date().toISOString(),
        }, null, 2),
        'utf8'
    );

    return {
        bundledConfigDir,
        configDir,
        resetConfig,
        reason,
    };
}

function fingerprintDirectory(dir: string): string {
    const hash = crypto.createHash('sha256');
    visitDirectory(hash, dir, dir);
    return hash.digest('hex');
}

function visitDirectory(hash: crypto.Hash, rootDir: string, currentDir: string): void {
    const entries = fs.readdirSync(currentDir, { withFileTypes: true })
        .sort((left, right) => left.name.localeCompare(right.name));

    for (const entry of entries) {
        const fullPath = path.join(currentDir, entry.name);
        const relativePath = path.relative(rootDir, fullPath).replaceAll(path.sep, '/');

        if (entry.isDirectory()) {
            hash.update(`dir:${relativePath}\n`);
            visitDirectory(hash, rootDir, fullPath);
            continue;
        }

        hash.update(`file:${relativePath}\n`);
        hash.update(fs.readFileSync(fullPath));
        hash.update('\n');
    }
}
